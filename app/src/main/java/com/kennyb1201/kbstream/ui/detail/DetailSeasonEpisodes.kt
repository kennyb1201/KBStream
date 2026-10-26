package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import java.time.LocalDate

/**
 * The episode numbers a season-wide watched action may cover, for BOTH screens
 * and for the ViewModel that writes them.
 *
 * The loaded season is the easy half: its listing is in hand, so aired episodes
 * are the rows whose dates have passed (unless the season's dates are
 * contradicted by the show-level pointer - see [staleSeasons] - in which case
 * the listing is left intact rather than guessed at).
 *
 * Every OTHER season used to fall back to TMDB's `episode_count`, i.e. `1..n`,
 * which is a lie for a season that is still airing: "Mark Entire Series as
 * Watched" (or a season chip on an unloaded, currently-airing season) wrote a
 * completed row for next week's episode and pushed it to Simkl, MDBList and
 * Trakt. No extra request is needed to do better: `next_episode_to_air` names
 * the next unaired episode, so that season covers `1..(next - 1)`, seasons
 * after it have aired nothing, and seasons before it are complete. Only when
 * TMDB gives no pointer (a finished show) does the count stand for the whole
 * range. A season whose own `air_date` is still in the future is refused even
 * without a pointer.
 *
 * Null means "no source knows how many episodes there are"; callers keep their
 * old behaviour rather than inventing a range.
 */
internal object DetailSeasonEpisodes {

    fun numbers(
        season: Int,
        loadedSeason: Int?,
        episodes: List<ResolvedEpisode>,
        episodeCount: Int?,
        seasonAirDate: String?,
        nextEpisodeToAir: TmdbEpisodeAirInfo?,
        airDatesTrusted: Boolean,
        today: LocalDate = LocalDate.now()
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

        // A season that has not premiered yet has no episodes to mark, however
        // many it is scheduled to have. Same date rule as an unaired episode
        // (a missing date proves nothing, so it is not treated as future).
        if (isEpisodeUnavailable(seasonAirDate, today)) return emptyList()

        val pointer = nextEpisodeToAir
        val pointerSeason = pointer?.seasonNumber?.takeIf { it > 0 }
        if (pointerSeason != null) {
            // A season after the one the next unaired episode belongs to has
            // not started, so none of its episodes have aired.
            if (season > pointerSeason) return emptyList()
            if (season == pointerSeason) {
                val next = pointer.episodeNumber?.takeIf { it > 0 }
                    ?: return emptyList()
                // The pointer is the season's next episode: everything before
                // it has aired, and it plus everything after it has not.
                return if (next > 1) (1 until next).toList() else emptyList()
            }
        }

        return (1..count).toList()
    }

    /**
     * The seasons whose season-row air date disagrees with the show-level
     * `next_episode_to_air` pointer, i.e. the seasons whose row is the stale
     * source.
     *
     * Per-episode dates for those seasons cannot be trusted either, so the
     * episode cards keep their date but drop the UNAVAILABLE verdict, and a
     * season mark/unmark covers the whole listing.
     */
    fun staleSeasons(detail: TmdbDetail?): Set<Int> {
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
