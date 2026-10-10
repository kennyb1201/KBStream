package com.kennyb1201.kbstream.ui.home

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six kids rails are English-original content only, and the raw-row budget
 * behind them grew to keep them full (see `HomeViewModel.loadPinnedKidsRails`).
 *
 * The problem this pins: the six built-in kids rails - Top Kids Movies/Shows,
 * New Kids Movies/Shows ([KidsNewRailRules]) and Trending Kids Movies/Shows
 * ([KidsTrendingRailRules]) - came back short, because `deepenPinnedRail`'s
 * loops read eight TMDB pages (160 raw rows) and the digital-release and
 * certification-ceiling passes cut that to a partial rail; and because nothing
 * restricted the query by language, so a French animated film with an English
 * dub sat in a kids row as a foreign title.
 *
 * The fix is two lines per query - `withOriginalLanguage = "en"` on all six
 * `KBFilters`, and a per-query page cap raised to twelve for the kids path only
 * - and every way it can silently regress is pinned here:
 *
 *  - a query that drops the language field reads English rows AND foreign
 *    ones, which no count on screen would ever show;
 *  - a language filter applied AFTER the fetch (a post-check over the returned
 *    items) would empty the same 160 raw rows the ceiling already thins, which
 *    is why the filter must be a REQUEST parameter on both discover endpoints;
 *  - the raised page cap must be the kids path's own - raising the shared
 *    default would change every other rail's network cost, and dropping the
 *    per-query argument leaves the kids rows short again;
 *  - and the language has to be part of the discover cache key: a cache that
 *    keyed only on media type and sort would hand an unfiltered visitor's page
 *    to an English kids query, and vice versa.
 *
 * What this file cannot measure is the live rail length (it needs TMDB and a
 * device) or the per-item `original_language` of a real response. Both are the
 * consequence of the request parameter asserted here - TMDB filters
 * `with_original_language=en` in the discover query itself, so the raw rows are
 * English before `deepenPinnedRail` ever sees one, and the extra pages are what
 * pushes the surviving count back up to the unchanged target.
 */
class KidsRailsEnglishOnlyContractTest {

    /** A fixed "today", as the sibling kids-rail tests use. */
    private val today = LocalDate.of(2026, 10, 8)

    /** The four rail queries that live in their own rule objects. */
    private val ruleQueries =
        KidsNewRailRules.queries(today) + KidsTrendingRailRules.queries(today)

    // ------------------------------------------------- the language filter --

    @Test
    fun `all four rule-built kids queries ask for English originals`() {
        assertEquals(
            "two recency rails and two trending rails, one query each per media type",
            listOf(
                "new_kids_movies",
                "new_kids_shows",
                "trending_kids_movies",
                "trending_kids_shows"
            ),
            ruleQueries.map { it.catalogId }
        )
        ruleQueries.forEach { query ->
            assertEquals(
                "rail ${query.catalogId} must be English-original only",
                "en",
                query.filters.withOriginalLanguage
            )
        }
    }

    @Test
    fun `the two standing kids queries are English originals too`() {
        // These two are built inline in loadPinnedKidsRails rather than by a
        // rule object, so they are read from the source: both `KBFilters` in
        // that function - and ONLY those - carry the literal "en".
        val home = squash(source(HOME_VM))
        val kids = between(
            home,
            "private suspend fun loadPinnedKidsRails(",
            "private suspend fun loadPinnedGuestRails("
        )

        assertEquals(
            "one language filter per standing query: Top Kids Movies and Top Kids Shows",
            2,
            Regex("withOriginalLanguage = \"en\"").findAll(kids).count()
        )
        assertTrue(
            "and they are the standing pair, not some other row in the function",
            kids.contains("catalogId = \"top_kids_movies\"") &&
                kids.contains("catalogId = \"top_kids_shows\"")
        )
        // The floor and the genre sets are untouched by this change.
        assertEquals(2, Regex("voteCountGte = 20").findAll(kids).count())
        assertTrue(kids.contains("withGenres = \"16|10751\""))
        assertTrue(kids.contains("withGenres = \"10762|16\""))
    }

    @Test
    fun `the language is a request parameter on both discover endpoints, never a post-fetch check`() {
        // The chain is model field -> query parameter -> discoverKB named
        // argument. A filter applied here in Kotlin instead would be a
        // post-fetch check, which is exactly what the fix avoids: it would thin
        // the same small raw pool the ceiling already cuts, instead of asking
        // TMDB for an English-only one.
        val api = source(TMDB_API)
        val movieEndpoint = between(api, "suspend fun discoverMovieGeneric(", "): TmdbDiscoverResponse")
        val tvEndpoint = between(api, "suspend fun discoverTvGeneric(", "): TmdbDiscoverResponse")

        listOf("movie" to movieEndpoint, "tv" to tvEndpoint).forEach { (which, body) ->
            assertTrue(
                "the $which discover has no with_original_language query",
                body.contains("@Query(\"with_original_language\") withOriginalLanguage: String? = null")
            )
        }

        val discover = squash(source(TMDB_REPO))
        val tvCall = between(discover, "api.discoverTvGeneric(", "api.discoverMovieGeneric(")
        val movieCall = between(discover, "api.discoverMovieGeneric(", "}.results")
        assertTrue(
            "the /discover/tv call must send the language through",
            tvCall.contains("withOriginalLanguage = filters?.withOriginalLanguage")
        )
        assertTrue(
            "the /discover/movie call must send the language through",
            movieCall.contains("withOriginalLanguage = filters?.withOriginalLanguage")
        )
        // And the kids path must reach discoverKB with the query's own filters,
        // or the field would never get as far as the request.
        val home = squash(source(HOME_VM))
        val kids = between(
            home,
            "private suspend fun loadPinnedKidsRails(",
            "private suspend fun loadPinnedGuestRails("
        )
        assertTrue(kids.contains("filters = query.filters"))
    }

    // -------------------------------------------------------- the page cap --

    @Test
    fun `only the kids path raises the page cap, and it is raised`() {
        val home = squash(source(HOME_VM))

        assertEquals(
            "exactly one call site may raise the cap - the kids rails' own",
            1,
            Regex("maxPage = KIDS_RAIL_MAX_PAGE").findAll(home).count()
        )
        assertEquals(
            "twelve pages = 240 raw rows, against the shared eight",
            12,
            intConstant(home, "KIDS_RAIL_MAX_PAGE")
        )
        assertEquals(
            "eight pages = 160 raw rows, unchanged for every other rail",
            8,
            intConstant(home, "PINNED_RAIL_MAX_PAGE")
        )
        assertTrue(
            "the shared default is what every other caller keeps",
            home.contains("maxPage: Int = PINNED_RAIL_MAX_PAGE")
        )
        assertTrue(
            "the loop reads up to the caller's cap",
            home.contains("while (page <= maxPage && kept.size < target)")
        )
    }

    @Test
    fun `the kids rails pass the raised cap and the guest rails do not`() {
        val home = squash(source(HOME_VM))
        val kids = between(
            home,
            "private suspend fun loadPinnedKidsRails(",
            "private suspend fun loadPinnedGuestRails("
        )
        val guest = home.substringAfter("private suspend fun loadPinnedGuestRails(")

        assertTrue(
            "the six kids rails must take the larger budget",
            kids.contains("maxPage = KIDS_RAIL_MAX_PAGE")
        )
        assertFalse(
            "an unfiltered non-kids rail has no reason to read more pages than before",
            guest.substringBefore("private suspend fun ").contains("maxPage =")
        )
    }

    @Test
    fun `the 120-item target and the existing filters are unchanged`() {
        val home = squash(source(HOME_VM))

        assertTrue(
            "only the raw-row budget grew; the target did not",
            home.contains("PINNED_RAIL_TARGET_ITEMS = 120")
        )
        assertTrue(
            "the target is still what the kids rails ask deepenPinnedRail for",
            between(
                home,
                "private suspend fun loadPinnedKidsRails(",
                "private suspend fun loadPinnedGuestRails("
            ).contains("target = PINNED_RAIL_TARGET_ITEMS")
        )
        // The ceiling and digital-release passes stay the gate: an empty rail
        // is omitted rather than backfilled with rejected titles.
        val kids = between(
            home,
            "private suspend fun loadPinnedKidsRails(",
            "private suspend fun loadPinnedGuestRails("
        )
        assertTrue(kids.contains("tmdbRepository.kidsFilterMetas("))
        assertTrue(kids.contains("applyDigitalAvailabilityFilter("))
        assertTrue(
            "a rail that could not fill still shows what survived - never a backfill",
            kids.contains("if (filtered.isEmpty()) return@async null")
        )
    }

    // ------------------------------------------------------- the cache key --

    @Test
    fun `the discover cache key composes the whole filter set, so English and unfiltered queries cannot collide`() {
        val discover = squash(source(TMDB_REPO))

        assertTrue(
            "the key must be built from the filters, language included",
            discover.contains("val cacheKey = \"discover|\$mediaType|\$sortBy|\$filters\"")
        )
        // ...which separates the queries only if KBFilters renders its fields
        // into its own identity: it is a plain data class (no custom toString to
        // drop the language) and withOriginalLanguage is one of its properties.
        val models = source(KB_MODELS)
        val filters = between(models, "data class KBFilters(", ") {")
        assertTrue(
            "the language must be a field of the filter set the key is built from",
            filters.contains("val withOriginalLanguage: String? = null")
        )
        assertFalse(
            "a custom toString could drop a field out of the cache key",
            models.contains("override fun toString")
        )

        // The kids path is deliberately not behind that shared page cache - it
        // calls discoverKB directly - so it can neither write an English row
        // into a non-English key nor read one back out.
        val home = squash(source(HOME_VM))
        val kids = between(
            home,
            "private suspend fun loadPinnedKidsRails(",
            "private suspend fun loadPinnedGuestRails("
        )
        assertTrue(kids.contains("tmdbRepository.discoverKB("))
    }

    @Test
    fun `the filter render the cache key reads is stable and carries the language`() {
        // The key is a string interpolation of the data class, so this is the
        // property the separation actually rests on: two filters differing only
        // in language must render differently, and one filter must render the
        // same both times it is asked.
        val english = com.kennyb1201.kbstream.data.kb.KBFilters(
            withGenres = "16|10751",
            voteCountGte = 20,
            withOriginalLanguage = "en"
        )
        val unfiltered = english.copy(withOriginalLanguage = null)

        assertNotEquals(
            "an English kids query and its unfiltered twin must not share a cache key",
            english.toString(),
            unfiltered.toString()
        )
        assertEquals(
            "and the same query must key the same page every time",
            english.toString(),
            english.copy().toString()
        )
    }

    // --------------------------------------------------- test plumbing --

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The integer a squashed `const val NAME = <n>` spells, so the cap is asserted as a number. */
    private fun intConstant(source: String, name: String): Int {
        val match = Regex(Regex.escape(name) + " = (\\d+)").find(source)
        assertTrue("missing constant: $name", match != null)
        return match!!.groupValues[1].toInt()
    }

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
        const val TMDB_API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val KB_MODELS = "com/kennyb1201/kbstream/data/kb/KBModels.kt"
    }
}
