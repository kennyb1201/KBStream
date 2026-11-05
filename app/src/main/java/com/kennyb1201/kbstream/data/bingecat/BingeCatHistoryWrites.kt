package com.kennyb1201.kbstream.data.bingecat

import android.util.Log

/*
 * Outbound watched-history writes for [BingeCatRepository]: the
 * POST /api/v1/profiles/{id}/sync/history and .../sync/history/remove calls
 * behind "Mark as Watched" / "Mark as Unwatched", the season long-press, and
 * the player's completion scrobble (step 2).
 *
 * These live outside BingeCatRepository.kt only to keep that file readable -
 * they are still the repository's behavior, called through same-named members
 * on it. The shape is Simkl's, not Trakt's: BingeCat exposes no live
 * start/pause/stop endpoints, so a "scrobble" here means one completed play
 * written once, plus the two explicit mark paths.
 *
 * Every entry point logs and returns false instead of throwing, so playback and
 * UI actions are never blocked by tracking failures. The gate and the whole
 * failure contract (401 refresh, 429 park, 403 -> reconnect, cache invalidation)
 * live in [BingeCatRepository.submitHistory]; these functions only map an app
 * action onto the field of the batch that fits it:
 *
 *  - a finished movie      -> `movies[]`
 *  - a finished episode    -> `episodes[]` (one entry per covered episode)
 *  - a whole-show mark     -> `shows[]` (no seasons = every episode)
 *  - a season long-press   -> `seasons[]`
 *
 * One user action is one batch call, never a call per episode.
 */

private const val TAG = "BINGECAT_REPO"

/** A finished movie. */
internal suspend fun BingeCatRepository.pushWatchedMovieImpl(
    imdbId: String,
    title: String? = null,
    tmdbId: Int? = null
): Boolean = submitMovie(
    add = true,
    parentId = imdbId,
    tmdbId = tmdbId,
    label = "movie ${title ?: imdbId}"
)

/** A whole-show mark: the poster long-press, and the detail screen's series mark. */
internal suspend fun BingeCatRepository.pushWatchedShowImpl(
    showImdbId: String,
    title: String? = null,
    tmdbId: Int? = null
): Boolean {
    val ids = BingeCatWriteRules.writeIds(showImdbId, tmdbId)
    if (ids == null) return bail("show", showImdbId)
    return submitHistory(
        add = true,
        batch = BingeCatHistoryBatch(
            shows = listOf(
                BingeCatHistoryShow(ids = ids, watchedAt = BingeCatWriteRules.watchedAt())
            )
        ),
        label = "show ${title ?: showImdbId}"
    )
}

/** One finished episode. */
internal suspend fun BingeCatRepository.pushWatchedEpisodeImpl(
    showImdbId: String,
    season: Int,
    episode: Int,
    title: String? = null,
    tmdbId: Int? = null
): Boolean = pushWatchedEpisodesImpl(showImdbId, season, listOf(episode), title, tmdbId)

/**
 * The finished episodes of one show and season, as ONE batch.
 *
 * The covered-episode fan-out the players already run for Simkl and Trakt (one
 * file can hold two TMDB episodes): BingeCat takes the list in one call, so the
 * action stays one write.
 */
internal suspend fun BingeCatRepository.pushWatchedEpisodesImpl(
    showImdbId: String,
    season: Int,
    episodes: List<Int>,
    title: String? = null,
    tmdbId: Int? = null
): Boolean {
    val ids = BingeCatWriteRules.writeIds(showImdbId, tmdbId)
    if (ids == null) return bail("episode", showImdbId)
    val valid = episodes.filter { it > 0 }.distinct().sorted()
    if (valid.isEmpty()) return bail("episode", showImdbId)
    val watchedAt = BingeCatWriteRules.watchedAt()
    return submitHistory(
        add = true,
        batch = BingeCatHistoryBatch(
            episodes = valid.map { episode ->
                BingeCatHistoryEpisode(
                    ids = ids,
                    season = season,
                    episode = episode,
                    watchedAt = watchedAt
                )
            }
        ),
        label = "episode ${title ?: showImdbId} s=$season e=$valid"
    )
}

/** A whole season, which is what a season long-press means. */
internal suspend fun BingeCatRepository.pushWatchedSeasonImpl(
    showImdbId: String,
    season: Int,
    title: String? = null,
    tmdbId: Int? = null
): Boolean {
    val ids = BingeCatWriteRules.writeIds(showImdbId, tmdbId)
    if (ids == null) return bail("season", showImdbId)
    return submitHistory(
        add = true,
        batch = BingeCatHistoryBatch(
            seasons = listOf(
                BingeCatHistorySeason(
                    ids = ids,
                    season = season,
                    watchedAt = BingeCatWriteRules.watchedAt()
                )
            )
        ),
        label = "season ${title ?: showImdbId} s=$season"
    )
}

/** Marking a movie unwatched. */
internal suspend fun BingeCatRepository.removeWatchedMovieImpl(
    imdbId: String,
    tmdbId: Int? = null
): Boolean = submitMovie(add = false, parentId = imdbId, tmdbId = tmdbId, label = "movie $imdbId")

/** Marking a whole show unwatched. */
internal suspend fun BingeCatRepository.removeWatchedShowImpl(
    showImdbId: String,
    tmdbId: Int? = null
): Boolean {
    val ids = BingeCatWriteRules.writeIds(showImdbId, tmdbId)
    if (ids == null) return bail("show", showImdbId)
    return submitHistory(
        add = false,
        batch = BingeCatHistoryBatch(shows = listOf(BingeCatHistoryShow(ids = ids))),
        label = "show $showImdbId"
    )
}

/** Removing the plays of [episodes] of one season; one batch, like the mark. */
internal suspend fun BingeCatRepository.removeWatchedEpisodesImpl(
    showImdbId: String,
    season: Int,
    episodes: List<Int>,
    tmdbId: Int? = null
): Boolean {
    val ids = BingeCatWriteRules.writeIds(showImdbId, tmdbId)
    if (ids == null) return bail("episode", showImdbId)
    val valid = episodes.filter { it > 0 }.distinct().sorted()
    if (valid.isEmpty()) return bail("episode", showImdbId)
    return submitHistory(
        add = false,
        batch = BingeCatHistoryBatch(
            episodes = valid.map { episode ->
                BingeCatHistoryEpisode(ids = ids, season = season, episode = episode)
            }
        ),
        label = "episode $showImdbId s=$season e=$valid"
    )
}

/** Removing a whole season's plays. */
internal suspend fun BingeCatRepository.removeWatchedSeasonImpl(
    showImdbId: String,
    season: Int,
    tmdbId: Int? = null
): Boolean {
    val ids = BingeCatWriteRules.writeIds(showImdbId, tmdbId)
    if (ids == null) return bail("season", showImdbId)
    return submitHistory(
        add = false,
        batch = BingeCatHistoryBatch(
            seasons = listOf(BingeCatHistorySeason(ids = ids, season = season))
        ),
        label = "season $showImdbId s=$season"
    )
}

/** The movie half of the four verbs: one entry, either direction. */
private suspend fun BingeCatRepository.submitMovie(
    add: Boolean,
    parentId: String,
    tmdbId: Int?,
    label: String
): Boolean {
    val ids = BingeCatWriteRules.writeIds(parentId, tmdbId)
    if (ids == null) return bail("movie", parentId)
    return submitHistory(
        add = add,
        batch = BingeCatHistoryBatch(
            movies = listOf(
                BingeCatHistoryMovie(
                    ids = ids,
                    watchedAt = if (add) BingeCatWriteRules.watchedAt() else null
                )
            )
        ),
        label = label
    )
}

/**
 * The unparseable-id bail, shared with Simkl's writes.
 *
 * No request is attempted because there is nothing the server could resolve;
 * the play is dropped rather than sent under a guessed id.
 */
private fun bail(kind: String, parentId: String): Boolean {
    Log.i(TAG, "write skipped: unparseable $kind id=$parentId")
    return false
}
