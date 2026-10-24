package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import com.kennyb1201.kbstream.ui.detail.isEpisodeUnavailable
import java.time.LocalDate

/**
 * The watched-state arithmetic the phone's Detail menus need, with no Compose
 * in sight so it can be pinned by a JVM test.
 *
 * Every rule here is the TV screen's own rule, stated once for the touch
 * menus: which episode numbers a season mark/unmark covers, which seasons'
 * air dates can be trusted, and whether a season - or the show - is already
 * watched. The TV keeps its inline copies (the port's rule is that no TV
 * composable is edited in place), so the contract tests below are what keep
 * the two readings of the rule from drifting.
 */
internal object MobileDetailRules {

    /**
     * The episode numbers a season-wide action should cover.
     *
     * For the season on screen the resolved listing wins, because it is the
     * one the viewer is looking at - but UNAIRED episodes are dropped, so a
     * "mark season watched" never tells a tracker that next week's episode is
     * watched. For any other season TMDB's `episode_count` is the only number
     * there is. Null when neither source knows.
     *
     * [airDatesTrusted] false means the listing's dates are contradicted by
     * the show-level pointer (see [staleSeasonDates]): a future date there
     * says the ROW is stale, not that the episode has not aired, so the list
     * is left intact rather than guessed at.
     */
    fun seasonEpisodeNumbers(
        season: Int,
        loadedSeason: Int?,
        episodes: List<ResolvedEpisode>,
        episodeCount: Int?,
        airDatesTrusted: Boolean,
        today: LocalDate
    ): List<Int>? {
        if (season == loadedSeason && episodes.isNotEmpty()) {
            return episodes
                .filter { episode ->
                    !airDatesTrusted || !isEpisodeUnavailable(episode.airDate, today)
                }
                .map { it.episodeNumber }
                .filter { it > 0 }
                .distinct()
                .sorted()
        }
        val count = episodeCount?.takeIf { it > 0 } ?: return null
        return (1..count).toList()
    }

    /**
     * Every season's episode numbers, in season order, skipping the seasons
     * no source knows the size of - the shape the "entire series" action
     * takes.
     */
    fun wholeSeriesNumbers(
        seasons: List<Int>,
        numbersFor: (Int) -> List<Int>?
    ): List<Pair<Int, List<Int>>> =
        seasons.mapNotNull { season ->
            numbersFor(season)
                ?.takeIf { it.isNotEmpty() }
                ?.let { numbers -> season to numbers }
        }

    /** True when every number in [numbers] is watched; an empty season never is. */
    fun allWatched(numbers: List<Int>, watched: Set<Int>): Boolean =
        numbers.isNotEmpty() && numbers.all { it in watched }

    /**
     * True when every episode of every known season is watched - what decides
     * which way the whole-series row toggles.
     */
    fun wholeSeriesWatched(
        seriesNumbers: List<Pair<Int, List<Int>>>,
        watchedFor: (Int) -> Set<Int>
    ): Boolean =
        seriesNumbers.isNotEmpty() &&
            seriesNumbers.all { (season, numbers) -> allWatched(numbers, watchedFor(season)) }

    /**
     * The season chips' order: the numbered seasons ascending, then the
     * specials (TMDB's season 0) last.
     *
     * TMDB lists season 0 first, so before this the phone's chip row opened on
     * "Specials" for every show that has them - a bucket of promos, recaps and
     * webisodes parked in front of the show's actual run, and the default
     * season whenever nothing said otherwise. The numbering is what a viewer
     * navigates by, so it leads; specials stay reachable as the last chip. A
     * show whose only season is 0 keeps it as its only chip.
     */
    fun seasonsInChipOrder(seasons: List<TmdbSeasonSummary>): List<TmdbSeasonSummary> =
        seasons.sortedWith(
            compareBy<TmdbSeasonSummary> { it.seasonNumber == 0 }
                .thenBy { it.seasonNumber }
        )

    /**
     * The seasons whose season-row air date disagrees with the show-level
     * `next_episode_to_air` pointer, i.e. the seasons whose row is the stale
     * source.
     *
     * Per-episode dates for those seasons cannot be trusted either, so the
     * episode cards keep their date but drop the UNAVAILABLE verdict, and a
     * season mark/unmark covers the whole listing. Exactly the set the TV page
     * derives, from the same two TMDB fields.
     */
    fun staleSeasonDates(detail: TmdbDetail?): Set<Int> {
        val pointer = detail?.nextEpisodeToAir
        val seasonNumber = pointer?.seasonNumber ?: return emptySet()
        if (pointer.airDate.isNullOrBlank()) return emptySet()
        val rowDate = detail.seasons
            .firstOrNull { it.seasonNumber == seasonNumber }
            ?.airDate
            ?: return emptySet()
        return if (rowDate != pointer.airDate) setOf(seasonNumber) else emptySet()
    }
}
