package com.kennyb1201.kbstream.ui.detail

import android.app.Application
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklPlaybackItem
import com.kennyb1201.kbstream.data.tmdb.displayRuntimeMinutes

/**
 * "RESUME + progress" rows for the Detail screen, derived from a tracker's
 * cloud playback session when local history has no in-progress position.
 *
 * Split out of [DetailViewModel] (a 2,000-line class that had grown past the
 * size where a single file stayed editable). Both sources share one contract:
 *
 *  - the row is SYNTHETIC and display-only — it is never written to disk, and
 *    its id is namespaced (`simkl-playback:` / `mdblist-playback:`) so it can
 *    never collide with a real history row;
 *  - the tracker reports a percentage, not a position, so the position is
 *    estimated from the TMDB runtime and skipped entirely when neither a
 *    position nor a duration can be worked out (a bar with no time reads as a
 *    bug);
 *  - nothing here throws: a tracker being down or unauthorised just means no
 *    resume row.
 */
internal object DetailPlaybackResume {

    /**
     * Short-TTL memo of the Simkl playback feed for the resume fallback ONLY.
     *
     * [SimklRepository.getPlaybackItems] is deliberately uncached — the delete
     * paths have to see the very session they are about to remove — but this
     * lookup sits on the Detail screen's critical path (the spinner waits on
     * it) for every title with no LOCAL resume row, which is almost every
     * first open. A minute of staleness on a display-only resume estimate is
     * invisible; a Simkl round-trip in front of the spinner is not. Keyed by
     * the account's access token so a profile switch can never show the other
     * account's progress. Only a successful answer is memoized, so a failure
     * stays a failure and the next open retries.
     */
    private const val SIMKL_PLAYBACK_TTL_MS = 60_000L

    @Volatile
    private var cachedSimklPlayback: List<SimklPlaybackItem>? = null

    @Volatile
    private var cachedSimklPlaybackAt = 0L

    @Volatile
    private var cachedSimklPlaybackToken = ""

    private suspend fun simklPlaybackItems(vm: DetailViewModel): List<SimklPlaybackItem> {
        val token = runCatching { vm.simklRepository.getSavedAccessToken() }
            .getOrNull()
            .orEmpty()

        val cached = cachedSimklPlayback
        if (
            cached != null &&
            cachedSimklPlaybackToken == token &&
            System.currentTimeMillis() - cachedSimklPlaybackAt < SIMKL_PLAYBACK_TTL_MS
        ) {
            return cached
        }

        val fresh = runCatching { vm.simklRepository.getPlaybackItems() }.getOrNull()
            ?: return emptyList()

        cachedSimklPlayback = fresh
        cachedSimklPlaybackAt = System.currentTimeMillis()
        cachedSimklPlaybackToken = token
        return fresh
    }

    /** Simkl's paused session for this title, if any. */
    suspend fun simkl(
        vm: DetailViewModel,
        id: String,
        type: String,
        tmdbId: Int?
    ): WatchHistoryEntity? {
        if (!vm.simklRepository.isConfigured() || !vm.simklRepository.hasToken()) {
            return null
        }

        val sessions = simklPlaybackItems(vm)

        val normalizedType = type.lowercase()
        val match = sessions.firstOrNull { session ->
            when (normalizedType) {
                "movie" -> {
                    val ids = session.movie?.ids
                    ids != null && (
                        ids.imdb?.equals(id, ignoreCase = true) == true ||
                            (tmdbId != null && ids.tmdb == tmdbId)
                        )
                }
                "series" -> {
                    val ids = session.show?.ids
                    val ep = session.episode
                    if (ids == null || ep == null) {
                        false
                    } else {
                        ep.season != null && ep.episode != null && (
                            ids.imdb?.equals(id, ignoreCase = true) == true ||
                                (tmdbId != null && ids.tmdb == tmdbId)
                            )
                    }
                }
                else -> false
            }
        } ?: return null

        val progress = (match.progress ?: return null)
            .takeIf { it > 0f && it < 100f }
            ?: return null

        val season: Int?
        val episode: Int?
        var episodeTitle: String? = null
        var name: String
        var runtimeMinutes: Int? = null

        if (normalizedType == "movie") {
            season = null
            episode = null
            name = match.movie?.title ?: "movie-$id"
            // Position estimate needs the movie runtime.
            val movieDetail = tmdbId?.let {
                runCatching { vm.tmdbRepository.getDetailByTmdbId(it, "movie") }.getOrNull()
            }
            runtimeMinutes = movieDetail?.displayRuntimeMinutes()
        } else {
            season = match.episode?.season
            episode = match.episode?.episode
            episodeTitle = match.episode?.title
            name = match.show?.title ?: "show-$id"
            // Position estimate needs an episode runtime; TMDB episode
            // runtime is often empty for TV, so also try the show-level
            // episode_run_time list.
            val detail = tmdbId?.let {
                runCatching { vm.tmdbRepository.getDetailByTmdbId(it, "tv") }.getOrNull()
            }
            runtimeMinutes = detail?.displayRuntimeMinutes()
        }

        val durationMs = runtimeMinutes?.times(60_000L)?.takeIf { it > 0L } ?: 0L
        val positionMs = if (durationMs > 0L) {
            (durationMs * (progress / 100f)).toLong()
        } else {
            0L
        }

        // Synthetic row: display-only. Keep positionMs even when the runtime
        // estimate is missing (positionMs = 0) ONLY when a duration exists;
        // otherwise the UI would show a bar with no time.
        if (positionMs <= 0L && durationMs <= 0L) {
            return null
        }

        val syntheticId =
            if (normalizedType == "movie") "simkl-playback:$id"
            else "simkl-playback:$id:$season:$episode"

        return WatchHistoryEntity(
            id = syntheticId,
            parentId = id,
            type = normalizedType,
            name = name,
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
            overview = null,
            clearLogo = null,
            backdropUrl = null,
            totalEpisodesInSeason = null,
            poster = null,
            streamUrl = null,
            season = season,
            episode = episode,
            // Same "imdbId:season:episode" convention TMDB-resolved
            // rows use, so the per-episode progress bar binds.
            episodeStreamId =
                if (normalizedType == "series" && season != null && episode != null) {
                    "$id:$season:$episode"
                } else {
                    null
                },
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
            isCompleted = false,
            completedAt = null
        )
    }

    /**
     * MDBList playback fallback for the Detail screen.
     *
     * Same synthetic-row contract as [simkl]: when local history and the Simkl
     * cloud session have no in-progress position for this title, derive a
     * display-only resume row from the paused MDBList playback session
     * (GET /sync/playback) so Detail shows RESUME + progress for
     * MDBList-tracked progress too.
     */
    suspend fun mdbList(
        vm: DetailViewModel,
        id: String,
        type: String,
        tmdbId: Int?
    ): WatchHistoryEntity? {
        val appContext = vm.getApplication<Application>()

        if (!MdbListClient.isConfigured(appContext)) {
            return null
        }

        val sessions = runCatching {
            MdbListClient.getPlaybackSessions(appContext)
        }.getOrDefault(emptyList())

        val normalizedType = type.lowercase()
        val match = sessions.firstOrNull { session ->
            val idMatch =
                session.imdbId?.equals(id, ignoreCase = true) == true ||
                    (tmdbId != null && session.tmdbId == tmdbId)

            when (normalizedType) {
                "movie" -> session.isMovie && idMatch
                "series" -> !session.isMovie &&
                    session.season != null &&
                    session.episode != null &&
                    idMatch
                else -> false
            }
        } ?: return null

        val progress = match.progress
            .takeIf { it > 0.0 && it < 100.0 }
            ?: return null

        val season: Int?
        val episode: Int?
        val name: String
        var runtimeMinutes: Int? = match.runtimeMinutes.takeIf { it > 0 }

        if (normalizedType == "movie") {
            season = null
            episode = null
            name = match.title ?: "movie-$id"
            // Position estimate needs the movie runtime.
            val movieDetail = tmdbId?.let {
                runCatching { vm.tmdbRepository.getDetailByTmdbId(it, "movie") }.getOrNull()
            }
            runtimeMinutes = movieDetail?.displayRuntimeMinutes()
                ?: runtimeMinutes
        } else {
            season = match.season
            episode = match.episode
            name = match.title ?: "show-$id"
            // Position estimate needs an episode runtime; TMDB episode
            // runtime is often empty for TV, so also try the show-level
            // episode_run_time list.
            val detail = tmdbId?.let {
                runCatching { vm.tmdbRepository.getDetailByTmdbId(it, "tv") }.getOrNull()
            }
            runtimeMinutes = detail?.displayRuntimeMinutes()
                ?: runtimeMinutes
        }

        val durationMs = runtimeMinutes?.times(60_000L)?.takeIf { it > 0L } ?: 0L
        val positionMs = if (durationMs > 0L) {
            (durationMs * (progress / 100.0)).toLong()
        } else {
            0L
        }

        // Synthetic row: display-only. Keep positionMs even when the
        // runtime estimate is missing (positionMs = 0) ONLY when a
        // duration exists; otherwise the UI would show a bar with no time.
        if (positionMs <= 0L && durationMs <= 0L) {
            return null
        }

        val syntheticId =
            if (normalizedType == "movie") "mdblist-playback:$id"
            else "mdblist-playback:$id:$season:$episode"

        return WatchHistoryEntity(
            id = syntheticId,
            parentId = id,
            type = normalizedType,
            name = name,
            episodeTitle = null,
            overview = null,
            clearLogo = null,
            backdropUrl = null,
            totalEpisodesInSeason = null,
            poster = null,
            streamUrl = null,
            season = season,
            episode = episode,
            // Same "imdbId:season:episode" convention TMDB-resolved
            // rows use, so the per-episode progress bar binds.
            episodeStreamId =
                if (normalizedType == "series" && season != null && episode != null) {
                    "$id:$season:$episode"
                } else {
                    null
                },
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
            isCompleted = false,
            completedAt = null
        )
    }
}
