package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
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

        // Animation (16) OR Family (10751), spelled the way TMDB's OR is: one
        // comma-separated param. An AND here - or the wrong ids - narrows the
        // rail instead of widening it, which reads as an empty row.
        assertEquals("16,10751", movie.filters.withGenres)

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
        assertEquals("10762,16", show.filters.withGenres)
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
                KBHomeOrderPrefs.BUILTIN_NEW_KIDS_SHOWS
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
        const val TMDB_API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
