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
 * The two "new" kids rails: "New Kids Movies" and "New Kids Shows" (see
 * `HomeViewModel.loadPinnedKidsRails` and [KidsNewRailRules]).
 *
 * The rails themselves live inside a ViewModel that needs an Activity to build,
 * so - like the rest of Home's rail wiring - the query is pinned as the pure
 * rule it is, and the join from that rule to the request is pinned by reading
 * the sources. That split matters here because every way this breaks is silent:
 *
 *  - a genre string that is an AND instead of an OR narrows the rail instead of
 *    widening it (a comma is TMDB's OR);
 *  - `certification` instead of `certification.lte` looks like "PG or milder"
 *    and is not - it EXCLUDES G, the mildest rating there is - and a rail whose
 *    only age rule is the ceiling has to keep the ceiling, because the app's
 *    own check cannot stand in for it on the movie endpoint;
 *  - a date window sent to the wrong field (primary_release_date on a series)
 *    is a parameter /discover/tv ignores, so the rail looks "new" and is not;
 *  - a rail keyed by an add-on URL rather than a built-in key renders somewhere
 *    the home manager can neither list nor move.
 */
class KidsNewRailsContractTest {

    /** A fixed "today" so the ninety-day window is asserted, not computed twice. */
    private val today = LocalDate.of(2026, 10, 8)

    private val queries = KidsNewRailRules.queries(today)
    private val movie = queries.first { it.mediaType == "movie" }
    private val show = queries.first { it.mediaType == "tv" }

    // ------------------------------------------------------------- the query --

    @Test
    fun `the movie rail discovers animation or family, PG or milder, from the last ninety days`() {
        assertEquals("movie", movie.mediaType)
        assertEquals("movie", movie.type)
        assertEquals("new_kids_movies", movie.catalogId)
        assertEquals("New Kids Movies", movie.catalogName)
        assertEquals("primary_release_date.desc", movie.sortBy)

        // Animation (16) OR Family (10751), spelled the way TMDB's OR is: a
        // PIPE. A comma is an AND on TMDB's side, which narrows the rail
        // instead of widening it - and over the ninety-day window below an AND
        // is a query with zero results, so the row disappears from Home
        // entirely (verified against the live API; see KidsNewRailRules).
        assertEquals("16|10751", movie.filters.withGenres)

        // The age rule is the CEILING, and there is no exact match beside it:
        // `certification=PG` would exclude every G title, which is the opposite
        // of what "or milder" means.
        assertEquals("US", movie.filters.certificationCountry)
        assertEquals("PG", movie.filters.certificationLte)
        assertNull(
            "an exact match would exclude G, the safest rating there is",
            movie.filters.certification
        )

        // A recency row's vote floor has to be low: a title released this month
        // has had no time to collect votes, and the standing rows' floor of 20
        // starves this one to empty.
        assertEquals(5, movie.filters.voteCountGte)

        assertEquals("2026-07-10", movie.filters.releaseDateGte)
        assertEquals("2026-10-08", movie.filters.releaseDateLte)
        assertEquals(KidsNewRailRules.WINDOW_DAYS, 90L)
        assertEquals(today.minusDays(90).toString(), KidsNewRailRules.windowStartIso(today))
    }

    @Test
    fun `the show rail discovers kids or animation over the same window`() {
        assertEquals("tv", show.mediaType)
        assertEquals("series", show.type)
        assertEquals("new_kids_shows", show.catalogId)
        assertEquals("New Kids Shows", show.catalogName)

        // TV sorts and windows on its own fields; the filters carry the same
        // release bounds because discoverKB maps them onto first_air_date.
        assertEquals("first_air_date.desc", show.sortBy)
        // Kids OR Animation, a pipe for the reason on the movie rail above.
        assertEquals("10762|16", show.filters.withGenres)
        assertEquals("US", show.filters.certificationCountry)
        assertEquals("PG", show.filters.certificationLte)
        assertNull(show.filters.certification)
        assertEquals(5, show.filters.voteCountGte)
        assertEquals(movie.filters.releaseDateGte, show.filters.releaseDateGte)
        assertEquals(today.toString(), show.filters.releaseDateLte)
    }

    @Test
    fun `both rails are built in the order Home draws them`() {
        // Movies first, then shows, matching the standing pair above them.
        assertEquals(
            listOf("new_kids_movies", "new_kids_shows"),
            queries.map { it.catalogId }
        )
        assertEquals(listOf("movie", "tv"), queries.map { it.mediaType })
    }

    // ----------------------------------------------------- the ceiling itself --

    @Test
    fun `a G-rated title is inside the ceiling and a PG-13 one is outside it`() {
        // TMDB compares `certification.lte` on the country's own scale, and the
        // app's own ceiling runs over the same ratings (KidsMode is the ordinal
        // scale the rail's belt-and-braces filter uses). So this is what the
        // rail means: G is allowed, PG is allowed, PG-13 is not.
        assertTrue(
            "a G-rated title is exactly what an exact-match PG would have excluded",
            KidsMode.allowed(KidsMode.CEIL_PG, "G")
        )
        assertTrue(KidsMode.allowed(KidsMode.CEIL_PG, "PG"))
        assertFalse(
            "a PG-13 title must not reach either rail",
            KidsMode.allowed(KidsMode.CEIL_PG, "PG-13")
        )
        // The value the rails send is a real rating on that scale, so TMDB has
        // something to compare - a value off the scale is a rule the endpoint
        // silently cannot honour.
        assertEquals(
            KidsMode.CEIL_PG,
            KidsMode.ratingToAge(KidsNewRailRules.CERTIFICATION_CEILING)
        )
    }

    // ------------------------------------------------------------- the wiring --

    @Test
    fun `the rails are fed into discover and built with their own ids`() {
        val home = squash(source(HOME_VM))

        assertTrue(
            "the two new rails must come from the shared rule",
            home.contains("KidsNewRailRules.queries(LocalDate.now())")
        )
        // The query has to actually reach the request, or the rail is the
        // standing one drawn twice.
        assertTrue(home.contains("mediaType = query.mediaType"))
        assertTrue(home.contains("sortBy = query.sortBy"))
        assertTrue(home.contains("filters = query.filters"))
        // And the rail has to be identifiable: a stable catalog id (which is
        // also its arrangement key) under the kids add-on name.
        assertTrue(home.contains("catalogId = query.catalogId"))
        assertTrue(home.contains("catalogName = query.catalogName"))
        assertTrue(home.contains("addonName = KIDS_ADDON_NAME"))
        // A rail that comes back empty after filtering is omitted, as the
        // standing rows are.
        assertTrue(home.contains("if (filtered.isEmpty()) return@async null"))
    }

    // ------------------------------------------------- rename / move / hide --

    /**
     * The list the manager builds for a kids profile, and the one the move core
     * walks: the existing pair first, then the two new rows, then whatever else
     * the profile has arranged (a browse rail, a catalog and a collection here) -
     * the new rows are not the last rails on the screen, and the last one has to
     * be able to move DOWN, not only within its own block.
     */
    private val kidsDefaults =
        KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7) +
            listOf("browse:1", "addon:a", "kb:c")

    private val newMovies = KBHomeOrderPrefs.BUILTIN_NEW_KIDS_MOVIES
    private val newShows = KBHomeOrderPrefs.BUILTIN_NEW_KIDS_SHOWS

    @Test
    fun `both new rails can be renamed, and fall back to their own name`() {
        val renamed = KBHomeOrderPrefs.withRename(
            KBHomeOrderPrefs.withRename(KBHomeOrder(), newMovies, "Fresh for the Kids"),
            newShows,
            "New Cartoons"
        )

        assertEquals(
            "Fresh for the Kids",
            KBHomeOrderPrefs.railTitle(renamed, newMovies, "New Kids Movies")
        )
        assertEquals(
            "New Cartoons",
            KBHomeOrderPrefs.railTitle(renamed, newShows, "New Kids Shows")
        )
        // The default a rename falls back to is the rail's own name - the same
        // string the loader builds it with - and an override on one row never
        // leaks into another.
        assertEquals("New Kids Movies", KBHomeOrderPrefs.builtinDefaultTitle(newMovies))
        assertEquals("New Kids Shows", KBHomeOrderPrefs.builtinDefaultTitle(newShows))
        assertEquals(
            "an unrelated row keeps its default",
            "Top Kids Movies",
            KBHomeOrderPrefs.railTitle(renamed, KBHomeOrderPrefs.BUILTIN_TOP_KIDS_MOVIES, "Top Kids Movies")
        )
    }

    @Test
    fun `a blank name clears the override, as it does everywhere else`() {
        val cleared = KBHomeOrderPrefs.withRename(
            KBHomeOrderPrefs.withRename(KBHomeOrder(), newShows, "Cartoons"),
            newShows,
            "   "
        )
        assertTrue("a blank name must not be stored", cleared.renames.isEmpty())
        assertEquals(
            "New Kids Shows",
            KBHomeOrderPrefs.railTitle(cleared, newShows, "New Kids Shows")
        )
    }

    @Test
    fun `the rename a new kids rail carries reaches the drawn rail`() {
        // A rename is keyed by the ARRANGEMENT key, so it reaches a kids rail
        // only if the rail's catalog id resolves to that key - the same join
        // that is what makes it movable and hideable. Both halves are one line
        // each and silently draw the default name if either is dropped.
        assertEquals(newMovies, KBHomeOrderPrefs.builtinKeyForCatalogId("new_kids_movies"))
        assertEquals(newShows, KBHomeOrderPrefs.builtinKeyForCatalogId("new_kids_shows"))

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
    fun `both new rails move within the kids arrangement like the standing pair`() {
        // A row is offered its arrows exactly when a press changes the order -
        // the manager's enabled state comes from this same rule.
        listOf(newMovies, newShows).forEach { key ->
            assertTrue(
                "UP on $key must do something",
                railMoveChangesOrder(KBHomeOrder(), kidsDefaults, key, -1)
            )
            assertTrue(
                "DOWN on $key must do something",
                railMoveChangesOrder(KBHomeOrder(), kidsDefaults, key, +1)
            )
        }

        // ...and the move lands one drawn slot up, exactly as the manager draws
        // the list - the new rows interleave with the standing pair and with
        // whatever follows them, not only within their own block.
        val drawn = mergedHomeRailKeys(KBHomeOrder(), kidsDefaults)
        val from = drawn.indexOf(newShows)
        val expected = drawn.toMutableList().also { it.add(from - 1, it.removeAt(from)) }
        assertEquals(
            expected,
            mergedHomeRailKeys(
                moveRailInMergedOrder(KBHomeOrder(), kidsDefaults, newShows, -1),
                kidsDefaults
            )
        )
    }

    @Test
    fun `a new kids rail can be hidden and shown again at its own slot`() {
        val drawn = mergedHomeRailKeys(KBHomeOrder(), kidsDefaults)

        val hidden = KBHomeOrderPrefs.toggleBuiltinHidden(KBHomeOrder(), newMovies)
        assertTrue("the flag marks it hidden", newMovies in hidden.hiddenSet)
        // Hidden is a filter over the order, not a removal from it: the visible
        // list drops the row, the stored order keeps its slot, and showing it
        // again restores the layout untouched.
        val visible = mergedHomeRailKeys(hidden, kidsDefaults).filter { it !in hidden.hiddenSet }
        assertFalse(newMovies in visible)
        assertTrue(newMovies in mergedHomeRailKeys(hidden, kidsDefaults))

        val shown = KBHomeOrderPrefs.toggleBuiltinHidden(hidden, newMovies)
        assertFalse(newMovies in shown.hiddenSet)
        assertEquals(drawn, mergedHomeRailKeys(shown, kidsDefaults))
    }

    @Test
    fun `the new rails are arrangeable built-ins, not add-on rails`() {
        // A rail keyed by an add-on URL is a rail no manifest describes: the
        // manager could not list it and the viewer could not move or rename it.
        assertEquals(
            KBHomeOrderPrefs.BUILTIN_NEW_KIDS_MOVIES,
            KBHomeOrderPrefs.builtinKeyForCatalogId("new_kids_movies")
        )
        assertEquals(
            KBHomeOrderPrefs.BUILTIN_NEW_KIDS_SHOWS,
            KBHomeOrderPrefs.builtinKeyForCatalogId("new_kids_shows")
        )
        assertTrue(KBHomeOrderPrefs.BUILTIN_NEW_KIDS_MOVIES in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(KBHomeOrderPrefs.BUILTIN_NEW_KIDS_SHOWS in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertEquals(
            "the new rows follow the standing pair for a kids profile",
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
    fun `the ceiling reaches the request, on both discover endpoints`() {
        // The chain is model field -> query param -> named argument in
        // discoverKB, and each link is one line that can be dropped silently.
        //
        // Both endpoints are asserted because both are SENT it - /discover/tv
        // documents no certification filter, so on a series row this param may
        // be ignored and the app's own ceiling check is what holds the rating.
        val api = source(TMDB_API)
        val tvEndpoint = between(api, "suspend fun discoverTvGeneric(", "): TmdbDiscoverResponse")
        val movieEndpoint = between(api, "suspend fun discoverMovieGeneric(", "): TmdbDiscoverResponse")

        listOf("tv" to tvEndpoint, "movie" to movieEndpoint).forEach { (which, body) ->
            assertTrue(
                "the $which discover has no certification.lte query",
                body.contains("@Query(\"certification.lte\") certificationLte")
            )
            assertTrue(
                "the exact-match certification param must stay untouched beside it",
                body.contains("@Query(\"certification\") certification")
            )
        }
    }

    @Test
    fun `a series windows on first_air_date and a film on primary_release_date`() {
        // One KBFilters field, two endpoints: /discover/tv has no
        // primary_release_date, and a window sent there is a parameter TMDB
        // ignores - which reads as a row that is no newer than the standing one.
        val discover = squash(source(TMDB_REPO))
        val tvCall = between(discover, "api.discoverTvGeneric(", "api.discoverMovieGeneric(")
        val movieCall = between(discover, "api.discoverMovieGeneric(", "}.results")

        assertTrue(
            "the series call must window on first_air_date",
            tvCall.contains("firstAirDateGte = dateGte") &&
                tvCall.contains("firstAirDateLte = dateLte")
        )
        assertFalse(
            "a movie date field on the TV endpoint is ignored by TMDB",
            tvCall.contains("primaryReleaseDateGte")
        )
        assertTrue(
            "the film call must window on primary_release_date",
            movieCall.contains("primaryReleaseDateGte = dateGte") &&
                movieCall.contains("primaryReleaseDateLte = dateLte")
        )
        assertFalse(
            "a TV date field on the movie endpoint is ignored by TMDB",
            movieCall.contains("firstAirDateGte")
        )
        // The window itself is the same field pair for both, so a rail's
        // releaseDateGte/Lte means the right thing on either endpoint.
        assertTrue(discover.contains("val dateGte = yearRange?.first ?: filters?.releaseDateGte"))
        assertTrue(discover.contains("val dateLte = yearRange?.second ?: filters?.releaseDateLte"))

        // And each named argument is the query parameter its endpoint expects,
        // which is the last line a URL can silently lose.
        val api = source(TMDB_API)
        val tvEndpoint = between(api, "suspend fun discoverTvGeneric(", "): TmdbDiscoverResponse")
        val movieEndpoint = between(api, "suspend fun discoverMovieGeneric(", "): TmdbDiscoverResponse")
        assertTrue(
            "the series window must go out as first_air_date.gte/lte",
            tvEndpoint.contains("@Query(\"first_air_date.gte\") firstAirDateGte") &&
                tvEndpoint.contains("@Query(\"first_air_date.lte\") firstAirDateLte")
        )
        assertTrue(
            "the film window must go out as primary_release_date.gte/lte",
            movieEndpoint.contains("@Query(\"primary_release_date.gte\") primaryReleaseDateGte") &&
                movieEndpoint.contains("@Query(\"primary_release_date.lte\") primaryReleaseDateLte")
        )
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

    /** The block between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
        const val TMDB_API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
