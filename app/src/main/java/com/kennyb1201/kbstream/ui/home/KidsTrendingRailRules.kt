package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.kb.KBFilters
import java.time.LocalDate

/**
 * The two "trending" kids rails: what kids content is popular RIGHT NOW, on
 * top of the standing "Top Kids" (all-time) and the "New Kids" (release-date)
 * rows.
 *
 * Why discover rather than TMDB's own /trending/{movie,tv}/week feed: the feed
 * carries no genre ids (see `TmdbDiscoverItem`), so it cannot be restricted to
 * kids content on this side - filtering the weekly chart down by age rating
 * alone would put the week's most popular PG thriller on a child's screen. A
 * genre-scoped discover is the one query that can express "popular AND a kids
 * title", so the rows are a popularity sort over a rolling window of recent
 * releases instead of the raw weekly chart.
 *
 * The window is wider than [KidsNewRailRules]'s half-year on purpose: these
 * rows mean "popular this year", not "released this week", so a title a
 * couple of months old that has taken off still qualifies. Everything else -
 * the genre sets, the age ceiling and the vote floor - is the new rows' own,
 * character for character, so both families draw from the same pool and share
 * the one ceiling/digital pipeline in `loadPinnedKidsRails`.
 */
internal object KidsTrendingRailRules {

    /**
     * How far back the popularity window reaches, in days.
     *
     * A year, not the half-year this started at. Measured against the live
     * API: 180 days of English-original kids/animation SERIES holds 15 titles
     * in total, which is a row of eight cards after the ceiling and
     * availability passes - and no page budget can widen a pool that small.
     * A year holds 73. Still the widest of the kids windows (see
     * [KidsNewRailRules.WINDOW_DAYS]).
     */
    const val WINDOW_DAYS = 365L

    /** The scale every value here is read against. */
    const val CERTIFICATION_COUNTRY = "US"

    /** "PG or milder" - see [KidsNewRailRules] for why this cannot be exact-match. */
    const val CERTIFICATION_CEILING = "PG"

    /**
     * The MOVIE floor - the new rows' own low 5, for the reason given there:
     * a title that grew popular this year may not have had the years of votes
     * the evergreen rows demand, and a higher floor starves this row.
     */
    const val MIN_VOTE_COUNT = 5

    /**
     * The SERIES floor, one vote rather than five - the same measurement as
     * [KidsNewRailRules.MIN_VOTE_COUNT_TV]: the series pool is a fraction of
     * the film pool, so the film floor starves this row.
     */
    const val MIN_VOTE_COUNT_TV = 1

    const val MOVIE_CATALOG_ID = "trending_kids_movies"
    const val SHOW_CATALOG_ID = "trending_kids_shows"

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
            sortBy = "popularity.desc",
            catalogId = MOVIE_CATALOG_ID,
            catalogName = "Trending Kids Movies",
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
            sortBy = "popularity.desc",
            catalogId = SHOW_CATALOG_ID,
            catalogName = "Trending Kids Shows",
            filters = KBFilters(
                withGenres = "10762|16",
                certificationCountry = CERTIFICATION_COUNTRY,
                certificationLte = CERTIFICATION_CEILING,
                // The series floor, not the film one - see [MIN_VOTE_COUNT_TV].
                voteCountGte = MIN_VOTE_COUNT_TV,
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
