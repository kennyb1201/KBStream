package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.kb.KBFilters
import java.time.LocalDate

/**
 * One built-in TMDB rail a kids profile draws on Home: the query that fetches
 * it, and the identity it is arranged by.
 *
 * The mapping, the ceiling/digital pipeline and the [Rail] build are the same
 * for every one of them (see `HomeViewModel.loadPinnedKidsRails`) - only the
 * query differs, which is why it is the thing that gets its own type.
 */
internal data class KidsRailQuery(
    /** "movie" or "tv" - the TMDB endpoint, not the [Rail]'s type. */
    val mediaType: String,
    /** TMDB `sort_by` for this media type. */
    val sortBy: String,
    /** Stable catalog id; also the arrangement key (see KBHomeOrderPrefs). */
    val catalogId: String,
    val catalogName: String,
    val filters: KBFilters
) {
    /** The [Rail] content type this media type maps to. */
    val type: String get() = if (mediaType == "tv") "series" else "movie"
}

/**
 * The two "new" kids rails: same discover, same ceilings and same pipeline as
 * the standing "Top Kids" rows, sorted by release date over a rolling window
 * instead of by popularity.
 *
 * Why a window rather than an unbounded `release_date.desc`: every discover
 * query sorts the whole corpus, so an unbounded one is dominated by whatever
 * TMDB has dated furthest in the future - announced titles with no release at
 * all. Bounding it at today, and starting it ninety days back, is what makes
 * the rows "new" rather than "not yet", and keeps them populated in the slow
 * months a shorter window would empty.
 *
 * The genre lists are the standing kids rows' own ("16|10751" for films,
 * "10762|16" for series) so the two families of row select from the same pool.
 *
 * Those are PIPES, and that is the whole fix for "New Kids Shows is missing":
 * TMDB reads a COMMA as an AND of the ids, not an OR, so "10762,16" asked for
 * series that are kids AND animation at once - and over a ninety-day window
 * that is a query with **zero** results (verified against the live API:
 * `discover/tv?with_genres=10762,16` over the last 90 days answers
 * `total_results: 0`, while the pipe answers eleven). The row was therefore
 * dropped from Home entirely, because an empty rail is not drawn. The kids
 * ceiling re-check below still drops anything the profile's age rating does
 * not allow, so the wider genre set cannot leak an adult title in.
 *
 * The age rule is a CEILING (`certification.lte`), never the exact-match
 * `certification`: TMDB reads the latter as one value, so `certification=PG`
 * would throw away every G title - the safest ones of all - while
 * `certification.lte=PG` is what a parent means by "PG or milder".
 *
 * The movie endpoint is the one that documents this filter; `/discover/tv`
 * lists no certification parameter at all, so the ceiling is sent there too
 * (a parameter TMDB ignores today is a filter already in place if it ever
 * hooks ratings up) but the TV rail does NOT depend on it: the ceiling that
 * actually holds a series row to the profile's rating is the app's own
 * `kidsFilterMetas` check every item passes through before it lands on a rail.
 *
 * The vote floor is deliberately low (5, against the standing rows' 20): a
 * title released this month has had no time to collect votes, and a floor high
 * enough to be meaningful on a popularity row starves a recency row to empty.
 */
internal object KidsNewRailRules {

    /** How far back the rails' window reaches, in days. */
    const val WINDOW_DAYS = 90L

    /** The scale every value here is read against. */
    const val CERTIFICATION_COUNTRY = "US"

    /** "PG or milder" - see the file doc for why this cannot be exact-match. */
    const val CERTIFICATION_CEILING = "PG"

    const val MIN_VOTE_COUNT = 5

    const val MOVIE_CATALOG_ID = "new_kids_movies"
    const val SHOW_CATALOG_ID = "new_kids_shows"

    /** The window's first day, as TMDB's `date.gte` spelling. */
    fun windowStartIso(today: LocalDate): String =
        today.minusDays(WINDOW_DAYS).toString()

    /**
     * Both rails' queries for [today]. Movies first, then shows, matching the
     * order Home draws them in.
     */
    fun queries(today: LocalDate): List<KidsRailQuery> = listOf(
        KidsRailQuery(
            mediaType = "movie",
            sortBy = "primary_release_date.desc",
            catalogId = MOVIE_CATALOG_ID,
            catalogName = "New Kids Movies",
            filters = KBFilters(
                withGenres = "16|10751",
                certificationCountry = CERTIFICATION_COUNTRY,
                certificationLte = CERTIFICATION_CEILING,
                voteCountGte = MIN_VOTE_COUNT,
                releaseDateGte = windowStartIso(today),
                releaseDateLte = today.toString(),
                // English-original only, server-side (with_original_language):
                // a foreign title with an English dub must not enter the row.
                withOriginalLanguage = "en"
            )
        ),
        KidsRailQuery(
            mediaType = "tv",
            sortBy = "first_air_date.desc",
            catalogId = SHOW_CATALOG_ID,
            catalogName = "New Kids Shows",
            filters = KBFilters(
                withGenres = "10762|16",
                certificationCountry = CERTIFICATION_COUNTRY,
                certificationLte = CERTIFICATION_CEILING,
                voteCountGte = MIN_VOTE_COUNT,
                // discoverKB maps these onto first_air_date for TV, so the same
                // two fields serve both rails.
                releaseDateGte = windowStartIso(today),
                releaseDateLte = today.toString(),
                // English-original only, server-side (with_original_language):
                // a foreign title with an English dub must not enter the row.
                withOriginalLanguage = "en"
            )
        )
    )
}
