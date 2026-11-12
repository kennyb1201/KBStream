package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.namedEpisodesOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-player season/episode picker's data, once for both engines.
 *
 * The player has never carried an episode list of its own - only the Up Next
 * countdown, which knows the ONE pair it is about to play. So the picker reads
 * the list the way Detail does, through the same repository calls and therefore
 * the same caches: a show's seasons from the enriched meta the player already
 * fetches for its clearlogo and its Up Next runtime, and a season's episodes
 * from [TmdbRepository.getSeasonEpisodes] - the twelve-hour memory / seven-day
 * disk season cache Detail's season browser reads. Switching back to a season
 * the viewer already opened is therefore a cache hit, not a second fetch.
 *
 * Deliberately NOT a DetailViewModel: that class is a screen's state machine
 * (episode chips, resume rows, mark-watched actions), and a player has none of
 * those. This is the data half only - seasons, one season's [ResolvedEpisode]
 * list, and which of them the history already calls watched - so the two
 * activities build rows from identical answers instead of drifting apart.
 *
 * `name`, `overview`, `thumbnail` and `airDate` all come from the same place
 * the Detail cards take them from, which is what the spec means by "the
 * description and thumbnail are already in the episode data": nothing here
 * adds a data source, an endpoint, or a field.
 */
internal object PlayerEpisodes {

    /**
     * The show's seasons, ascending, specials (season 0) dropped.
     *
     * Season 0 is TMDB's bucket for trailers and recaps - the same exclusion
     * [randomAiredEpisode] makes - and a picker that offers it would be
     * offering clips. A title TMDB lists no seasons for still answers the
     * season playing now, so a sparse entry browses the season it is in rather
     * than opening an empty panel.
     */
    suspend fun seasons(
        context: Context,
        parentId: String,
        parentType: String,
        playingSeason: Int?
    ): List<Int> = withContext(Dispatchers.IO) {
        if (parentId.isBlank()) return@withContext emptyList()
        val listed = runCatchingCancellable {
            TmdbRepository.getInstance(context.applicationContext)
                .fetchEnrichedMetaCached(parentId, parentType)
        }.getOrNull()
            ?.seasons
            .orEmpty()
            .mapNotNull { summary -> summary.seasonNumber }
            .filter { number -> number > 0 }
            .distinct()
            .sorted()
        listed.ifEmpty { listOfNotNull(playingSeason?.takeIf { it > 0 }) }
    }

    /**
     * One season's episodes, in TMDB's own order.
     *
     * The same list Detail's season browser shows, from the same cache: an
     * empty answer is a miss (see `TmdbRepository`'s "empty is a miss" rule),
     * so a failed lookup answers an empty list here and the picker keeps the
     * season rows rather than claiming the season has no episodes.
     */
    suspend fun season(
        context: Context,
        parentId: String,
        parentType: String,
        season: Int
    ): List<ResolvedEpisode> = withContext(Dispatchers.IO) {
        if (parentId.isBlank() || season < 0) return@withContext emptyList()
        val repository = TmdbRepository.getInstance(context.applicationContext)
        val tmdbId = runCatchingCancellable {
            PlaybackHistoryIds.resolveTmdbId(context.applicationContext, parentId, parentType)
        }.getOrNull() ?: return@withContext emptyList()
        runCatchingCancellable {
            repository.getSeasonEpisodes(tmdbId, season, parentId)
        }.getOrDefault(emptyList()).namedEpisodesOnly()
    }

    /**
     * The episodes of [season] the watch history already calls watched.
     *
     * The player has no watched union in scope (Detail keeps one as a
     * StateFlow; the player only ever writes rows), so this reads the same
     * local completed rows Detail's checkmarks are built from, through the
     * shared [WatchedEpisodeState] rules - the union's local half, without a
     * tracker round-trip on a panel press.
     */
    suspend fun watchedEpisodes(
        context: Context,
        parentId: String,
        season: Int
    ): Set<Int> = withContext(Dispatchers.IO) {
        if (parentId.isBlank()) return@withContext emptySet()
        val completed = runCatchingCancellable {
            WatchHistoryDatabase.getInstanceScoped(context.applicationContext)
                .watchHistoryDao()
                .getCompletedForParents(listOf(parentId))
        }.getOrDefault(emptyList())
        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = parentId,
            localCompletedEntries = completed,
            simklCompletedEpisodes = emptySet()
        )
        WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
            parentId = parentId,
            season = season,
            simklWatchedEpisodes = emptySet(),
            watchedEpisodeKeys = keys
        )
    }
}
