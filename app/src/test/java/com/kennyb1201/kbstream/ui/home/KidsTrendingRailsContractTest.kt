package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.kb.KBHomeOrder
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.mergedHomeRailKeys
import com.kennyb1201.kbstream.data.kb.moveRailInMergedOrder
import com.kennyb1201.kbstream.data.kb.railMoveChangesOrder
import com.kennyb1201.kbstream.data.sync.KidsMode
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two "trending" kids rails: "Trending Kids Movies" and "Trending Kids
 * Shows" (see `HomeViewModel.loadPinnedKidsRails` and [KidsTrendingRailRules]).
 *
 * They are the third pair of app-built kids rows, and every way they can break
 * is silent - the same ways [KidsNewRailsContractTest] pins for the recency
 * pair, plus the one that is only theirs:
 *
 *  - a `sort_by` that is not a popularity sort makes "Trending" a duplicate of
 *    the recency pair (or of the evergreen Top rows), with nothing on screen to
 *    say which is which;
 *  - a genre string that is the wrong OR (or the wrong ids) narrows the rail
 *    instead of widening it;
 *  - `certification` instead of `certification.lte` looks like "PG or milder"
 *    and EXCLUDES G;
 *  - a window sent to the wrong date field (primary_release_date on a series) is
 *    a parameter /discover/tv ignores;
 *  - a rail keyed by an add-on URL rather than a built-in key renders somewhere
 *    the home manager can neither list, move, hide nor rename.
 */
class KidsTrendingRailsContractTest {

    /** A fixed "today" so the window is asserted, not computed twice. */
    private val today = LocalDate.of(2026, 10, 8)

    private val queries = KidsTrendingRailRules.queries(today)
    private val movie = queries.first { it.mediaType == "movie" }
    private val show = queries.first { it.mediaType == "tv" }

    // ------------------------------------------------------------- the query --

    @Test
    fun `the movie rail discovers animation or family, PG or milder, most popular first`() {
        assertEquals("movie", movie.mediaType)
        assertEquals("movie", movie.type)
        assertEquals("trending_kids_movies", movie.catalogId)
        assertEquals("Trending Kids Movies", movie.catalogName)
        // The whole point of the pair: a POPULARITY sort. A release-date sort
        // here would make this row the recency pair drawn twice.
        assertEquals("popularity.desc", movie.sortBy)

        // Animation (16) OR Family (10751), spelled the way TMDB's OR is: a
        // PIPE. A comma is an AND there, which narrows the row instead of
        // widening it - the bug that emptied "New Kids Shows" (see
        // KidsNewRailRules); it is the same query shape on both pairs.
        assertEquals("16|10751", movie.filters.withGenres)
        assertEquals("US", movie.filters.certificationCountry)
        assertEquals("PG", movie.filters.certificationLte)
        assertNull(
            "an exact match would exclude G, the safest rating there is",
            movie.filters.certification
        )
        assertEquals(5, movie.filters.voteCountGte)
        assertEquals("2026-04-11", movie.filters.releaseDateGte)
        assertEquals("2026-10-08", movie.filters.releaseDateLte)
        assertEquals(KidsTrendingRailRules.WINDOW_DAYS, 180L)
        assertEquals(today.minusDays(180).toString(), KidsTrendingRailRules.windowStartIso(today))
    }

    @Test
    fun `the show rail discovers kids or animation the same way`() {
        assertEquals("tv", show.mediaType)
        assertEquals("series", show.type)
        assertEquals("trending_kids_shows", show.catalogId)
        assertEquals("Trending Kids Shows", show.catalogName)
        assertEquals("popularity.desc", show.sortBy)
        assertEquals("10762|16", show.filters.withGenres)
        assertEquals("US", show.filters.certificationCountry)
        assertEquals("PG", show.filters.certificationLte)
        assertNull(show.filters.certification)
        assertEquals(5, show.filters.voteCountGte)
        assertEquals(movie.filters.releaseDateGte, show.filters.releaseDateGte)
        assertEquals(today.toString(), show.filters.releaseDateLte)
    }

    @Test
    fun `the trending window is wider than the new pair's, and both rails share it`() {
        // "New" is what shipped; "Trending" is what is popular over a longer
        // stretch, so the two rows are not the same list twice. Widening the
        // trending window past the new pair's is the whole distinction, and
        // narrowing it back would collapse them.
        assertTrue(
            "trending must reach further back than the recency pair",
            KidsTrendingRailRules.WINDOW_DAYS > KidsNewRailRules.WINDOW_DAYS
        )
    }

    @Test
    fun `both rails are built in the order Home draws them`() {
        assertEquals(
            listOf("trending_kids_movies", "trending_kids_shows"),
            queries.map { it.catalogId }
        )
        assertEquals(listOf("movie", "tv"), queries.map { it.mediaType })
    }

    // ----------------------------------------------------- the ceiling itself --

    @Test
    fun `a G-rated title is inside the ceiling and a PG-13 one is outside it`() {
        assertTrue(KidsMode.allowed(KidsMode.CEIL_PG, "G"))
        assertTrue(KidsMode.allowed(KidsMode.CEIL_PG, "PG"))
        assertFalse(
            "a PG-13 title must not reach either rail",
            KidsMode.allowed(KidsMode.CEIL_PG, "PG-13")
        )
        assertEquals(
            KidsMode.CEIL_PG,
            KidsMode.ratingToAge(KidsTrendingRailRules.CERTIFICATION_CEILING)
        )
    }

    // ------------------------------------------------------------- the wiring --

    @Test
    fun `the rails are fed into discover and built with their own ids`() {
        val home = squash(source(HOME_VM))

        assertTrue(
            "the two trending rails must come from the shared rule",
            home.contains("KidsTrendingRailRules.queries(LocalDate.now())")
        )
        // The query has to actually reach the request.
        assertTrue(home.contains("mediaType = query.mediaType"))
        assertTrue(home.contains("sortBy = query.sortBy"))
        assertTrue(home.contains("filters = query.filters"))
        assertTrue(home.contains("catalogId = query.catalogId"))
        assertTrue(home.contains("catalogName = query.catalogName"))
        assertTrue(home.contains("addonName = KIDS_ADDON_NAME"))
        assertTrue(home.contains("if (filtered.isEmpty()) return@async null"))
    }

    // ------------------------------------------------- rename / move / hide --

    /**
     * The list the manager builds for a kids profile, and the one the move core
     * walks: the two standing rows, then the new pair, then the trending pair,
     * then whatever else the profile has arranged.
     */
    private val kidsDefaults =
        KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7) +
            listOf("browse:1", "addon:a", "kb:c")

    private val trendMovies = KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_MOVIES
    private val trendShows = KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_SHOWS

    @Test
    fun `the trending rails are arrangeable built-ins, not add-on rails`() {
        assertEquals(
            KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_MOVIES,
            KBHomeOrderPrefs.builtinKeyForCatalogId("trending_kids_movies")
        )
        assertEquals(
            KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_SHOWS,
            KBHomeOrderPrefs.builtinKeyForCatalogId("trending_kids_shows")
        )
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(trendMovies))
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(trendShows))
        assertEquals("Trending Kids Movies", KBHomeOrderPrefs.builtinDefaultTitle(trendMovies))
        assertEquals("Trending Kids Shows", KBHomeOrderPrefs.builtinDefaultTitle(trendShows))
        assertEquals(
            "the trending rows follow the standing pair and the new pair",
            listOf(
                KBHomeOrderPrefs.BUILTIN_TOP_KIDS_MOVIES,
                KBHomeOrderPrefs.BUILTIN_TOP_KIDS_SHOWS,
                KBHomeOrderPrefs.BUILTIN_NEW_KIDS_MOVIES,
                KBHomeOrderPrefs.BUILTIN_NEW_KIDS_SHOWS,
                KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_MOVIES,
                KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_SHOWS
            ),
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7)
                .filter { it in KBHomeOrderPrefs.KIDS_BUILTIN_KEYS }
        )
    }

    @Test
    fun `both trending rails can be renamed, and fall back to their own name`() {
        val renamed = KBHomeOrderPrefs.withRename(
            KBHomeOrderPrefs.withRename(KBHomeOrder(), trendMovies, "Hot Right Now"),
            trendShows,
            "Popular Cartoons"
        )

        assertEquals(
            "Hot Right Now",
            KBHomeOrderPrefs.railTitle(renamed, trendMovies, "Trending Kids Movies")
        )
        assertEquals(
            "Popular Cartoons",
            KBHomeOrderPrefs.railTitle(renamed, trendShows, "Trending Kids Shows")
        )
        assertEquals(
            "an unset row keeps its default",
            "New Kids Movies",
            KBHomeOrderPrefs.railTitle(renamed, KBHomeOrderPrefs.BUILTIN_NEW_KIDS_MOVIES, "New Kids Movies")
        )
    }

    @Test
    fun `the rename a trending rail carries reaches the drawn rail`() {
        // The rename is keyed by the ARRANGEMENT key, so it reaches a trending
        // rail only if the rail's catalog id resolves to that key - the same
        // join that is what makes it movable and hideable.
        val slots = squash(source(SLOTS))
        assertTrue(
            "the merge must key an app-built rail by its catalog id",
            slots.contains("KBHomeOrderPrefs.builtinKeyForCatalogId(rail.catalogId)")
        )
        assertTrue(
            "and hand the arrangement's rename to the drawn rail",
            slots.contains("titleOverride = renamedTitle(key)")
        )
    }

    @Test
    fun `both trending rails move within the kids arrangement like the standing pair`() {
        listOf(trendMovies, trendShows).forEach { key ->
            assertTrue(
                "UP on $key must do something",
                railMoveChangesOrder(KBHomeOrder(), kidsDefaults, key, -1)
            )
            assertTrue(
                "DOWN on $key must do something",
                railMoveChangesOrder(KBHomeOrder(), kidsDefaults, key, +1)
            )
        }

        // The move lands one drawn slot up, interleaving with the rails around
        // it, not only within the trending pair.
        val drawn = mergedHomeRailKeys(KBHomeOrder(), kidsDefaults)
        val from = drawn.indexOf(trendShows)
        val expected = drawn.toMutableList().also { it.add(from - 1, it.removeAt(from)) }
        assertEquals(
            expected,
            mergedHomeRailKeys(
                moveRailInMergedOrder(KBHomeOrder(), kidsDefaults, trendShows, -1),
                kidsDefaults
            )
        )
    }

    @Test
    fun `a trending rail can be hidden and shown again at its own slot`() {
        val drawn = mergedHomeRailKeys(KBHomeOrder(), kidsDefaults)

        val hidden = KBHomeOrderPrefs.toggleBuiltinHidden(KBHomeOrder(), trendMovies)
        assertTrue("the flag marks it hidden", trendMovies in hidden.hiddenSet)
        val visible = mergedHomeRailKeys(hidden, kidsDefaults).filter { it !in hidden.hiddenSet }
        assertFalse(trendMovies in visible)
        assertTrue("the stored order keeps its slot", trendMovies in mergedHomeRailKeys(hidden, kidsDefaults))

        val shown = KBHomeOrderPrefs.toggleBuiltinHidden(hidden, trendMovies)
        assertFalse(trendMovies in shown.hiddenSet)
        assertEquals(drawn, mergedHomeRailKeys(shown, kidsDefaults))
    }

    // ------------------------------------------------------------- test plumbing --

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
