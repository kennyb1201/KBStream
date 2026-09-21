package com.kennyb1201.kbstream.data.simkl

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/*
 * Playback-side Simkl tracking for [SimklRepository]:
 *
 *  - [SimklRepository.deletePlaybackSessionImpl] etc: delete the paused
 *    playback sessions behind the Continue Watching feed, so a title that
 *    was marked watched / removed stops coming back from /sync/playback.
 *  - [SimklRepository.scrobbleImpl]: live POST /scrobble/start|pause|stop.
 *
 * These live outside SimklRepository.kt only to keep that file readable.
 * The repository keeps same-named members that delegate here, so callers
 * are unchanged. Every entry point logs and returns false/0 instead of
 * throwing, so playback is never blocked by tracking failures.
 */

/**
 * HTTP codes that mean "there is no such playback session", i.e. the stop
 * succeeded in effect: Simkl returns 409 when the session was already ended
 * (the body repeats the session it dropped) and 404 when it never had one.
 * Anything else — 400 in particular — is a real failure and is left to log.
 */
private val TERMINAL_STOP_CODES = setOf(404, 409)

/*
 * Outbound "Remove from Continue Watching" for Simkl-backed cards:
 * deletes the paused playback session (the progress record behind the
 * Continue Watching feed) so the title stops coming back from the
 * remote feed even though watch history is stored separately. Used
 * together with the history removals in SimklHistoryWrites.kt.
 */
suspend fun SimklRepository.deletePlaybackSessionImpl(
    playbackId: Int?
): Boolean {

    if (
        !isConfigured() ||
        !hasToken() ||
        playbackId == null ||
        playbackId <= 0
    ) {
        Log.i(
            "SIMKL_REPO",
            "deletePlaybackSession skipped: " +
                "no valid playback id=$playbackId"
        )
        return false
    }

    return try {
        val response = api.deletePlaybackSession(
            id = playbackId,
            authorization = trackedAuthHeader()
        )

        val alreadyGone =
            response.code() == 404

        if (!response.isSuccessful && !alreadyGone) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e(
                "SIMKL_REPO",
                "deletePlaybackSession failed " +
                    "code=${response.code()} body=$errorText"
            )
        } else {
            // Drop the Continue Watching snapshot so the next rail
            // refresh sees the updated remote state instead of
            // resurrecting the deleted session from the stale copy.
            // 404 means the session is already gone (204 is the
            // success code) - count it as removed so a stale snapshot
            // can't keep a ghost card around until its disk TTL ends.
            clearContinueWatchingCache()

            Log.i(
                "SIMKL_REPO",
                "deletePlaybackSession ok id=$playbackId" +
                    if (alreadyGone) " (already gone)" else ""
            )
        }

        response.isSuccessful || alreadyGone
    } catch (e: Exception) {
        Log.e(
            "SIMKL_REPO",
            "deletePlaybackSession error: ${e.message}",
            e
        )
        false
    }
}

/**
 * Deletes every open Simkl playback session whose show/movie matches the
 * given parent, regardless of which deduped card surfaced it. A paused
 * title is usually backed by BOTH a local resume row and a Simkl paused
 * session; when the local twin wins the rail dedupe the visible card
 * carries no playbackId, so deleting only the card's own session (or none
 * at all) leaves the remote session open and the title gets re-added from
 * /sync/playback on the very next feed refresh. Returns how many sessions
 * were removed (including ones already gone server-side).
 */
suspend fun SimklRepository.deletePlaybackSessionsForParentImpl(
    parentId: String,
    title: String? = null
): Int {

    if (
        !isConfigured() ||
        !hasToken()
    ) {
        Log.i(
            "SIMKL_REPO",
            "deletePlaybackSessionsForParent skipped: " +
                "not configured/authenticated"
        )
        return 0
    }

    val ref =
        parsePlaybackIds(
            parentId
        )
            ?: return 0

    return try {

        val matchingSessions =
            getPlaybackItems()
                .filter { item ->
                    playbackItemMatchesParent(
                        item = item,
                        imdb = ref.imdb,
                        tmdb = ref.tmdb,
                        title = title
                    )
                }

        if (matchingSessions.isEmpty()) {
            Log.i(
                "SIMKL_REPO",
                "deletePlaybackSessionsForParent: no open session " +
                    "for parent=$parentId"
            )
            return 0
        }

        var removed =
            0

        matchingSessions.forEach { item ->
            if (
                deletePlaybackSession(
                    item.id
                )
            ) {
                removed += 1
            }
        }

        Log.i(
            "SIMKL_REPO",
            "deletePlaybackSessionsForParent parent=$parentId " +
                "matched=${matchingSessions.size} removed=$removed"
        )

        removed
    } catch (
        e: kotlinx.coroutines.CancellationException
    ) {
        throw e
    } catch (e: Exception) {
        Log.e(
            "SIMKL_REPO",
            "deletePlaybackSessionsForParent error: ${e.message}",
            e
        )
        0
    }
}

/**
 * Deletes every open Simkl playback session for a title whose watched
 * position has passed the given threshold. Called right after a title
 * (or a specific episode of a show) is marked completed so the paused
 * pre-completion session record can't keep resurfacing in Continue
 * Watching at its old progress (e.g. "99% watched"). Returns the number
 * of sessions removed. Failures are logged, never thrown.
 */
suspend fun SimklRepository.deleteOpenPlaybackSessionsForWatchedImpl(
    parentId: String,
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    episodes: List<Int>? = null,
    minProgress: Float = 95f
): Int {

    if (
        !isConfigured() ||
        !hasToken()
    ) {
        return 0
    }

    val ref =
        parsePlaybackIds(parentId, tmdbId)
            ?: return 0

    val episodeSet =
        episodes?.toSet()

    return try {
        val matchingSessions =
            getPlaybackItems()
                .filter { item ->
                    playbackItemMatchesParent(
                        item = item,
                        imdb = ref.imdb,
                        tmdb = ref.tmdb,
                        title = null
                    ) &&
                        // When specific episode(s) were completed, only
                        // those episodes' sessions are stale; a paused
                        // session on another episode must survive.
                        when {
                            episodeSet != null && season != null ->
                                item.episode?.season == season &&
                                    item.episode?.episode
                                        ?.let { it in episodeSet } == true

                            season != null && episode != null ->
                                item.episode?.season == season &&
                                    item.episode?.episode == episode

                            else -> true
                        }
                }
                .filter { item ->
                    (item.progress ?: 0f) >= minProgress
                }

        var removed = 0

        matchingSessions.forEach { item ->
            if (deletePlaybackSession(item.id)) {
                removed += 1
            }
        }

        if (removed > 0) {
            Log.i(
                "SIMKL_REPO",
                "deleteOpenPlaybackSessionsForWatched " +
                    "parent=$parentId s=$season e=$episode removed=$removed"
            )
        }

        removed
    } catch (
        e: kotlinx.coroutines.CancellationException
    ) {
        throw e
    } catch (e: Exception) {
        Log.e(
            "SIMKL_REPO",
            "deleteOpenPlaybackSessionsForWatched error: ${e.message}",
            e
        )
        0
    }
}

private fun playbackItemMatchesParent(
    item: SimklPlaybackItem,
    imdb: String?,
    tmdb: Int?,
    title: String?
): Boolean {

    // A session is exactly one movie OR one show, and the two model
    // types share no interface, so read ids/title off each side
    // instead of combining them (item.movie ?: item.show would infer
    // Any and make these members unresolvable).
    val ids =
        item.movie?.ids
            ?: item.show?.ids

    if (
        imdb != null &&
        ids?.imdb?.equals(
            imdb,
            ignoreCase = true
        ) == true
    ) {
        return true
    }

    if (
        tmdb != null &&
        ids?.tmdb == tmdb
    ) {
        return true
    }

    // The Simkl id is not in the ref (it is resolved from the remote
    // feed, never derived from a local parent id); only fall back to
    // the title when the session carries no ids at all.
    if (
        ids == null &&
        title != null
    ) {
        val mediaTitle =
            item.movie?.title
                ?: item.show?.title

        return mediaTitle
            ?.equals(
                title,
                ignoreCase = true
            ) == true
    }

    return false
}

/*
 * Runs [block]'s suspending work on a job the caller cannot cancel, so an
 * in-flight scrobble request survives the player cancelling the coroutine
 * that launched it. Matches MdbListClient.postScrobble().
 */
private suspend fun <T> uncancellable(block: suspend () -> T): T =
    withContext(NonCancellable) { block() }

/*
 * Live scrobble (POST /scrobble/start|pause|stop). Called from the
 * player on play/pause/end so Simkl records in-progress playback and
 * extrapolates the watch between events.
 */
suspend fun SimklRepository.scrobbleImpl(
    action: String,
    parentId: String,
    parentType: String,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    progress: Double,
    tmdbId: Int? = null
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "scrobble/$action skipped: not configured/authenticated")
        return false
    }

    val ids = parsePlaybackIds(parentId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "scrobble/$action skipped: unparseable id=$parentId")
        return false
    }

    val isMovie =
        parentType.lowercase() == "movie"

    val body =
        SimklScrobbleRequest(
            progress = progress,
            movie =
                if (isMovie) {
                    SimklScrobbleMovie(
                        title = title,
                        ids = ids
                    )
                } else {
                    null
                },
            show =
                if (!isMovie) {
                    SimklScrobbleShow(
                        title = title,
                        ids = ids
                    )
                } else {
                    null
                },
            episode =
                if (!isMovie && season != null && episode != null) {
                    SimklScrobbleEpisode(
                        season = season,
                        number = episode
                    )
                } else {
                    null
                }
        )

    return try {
        // The player runs these on a job it cancels the moment the next
        // playback event arrives (buffering -> playing toggles more than once
        // on a slow start), and cancellation killed the request mid-flight:
        // the log showed "scrobble/start error: x0 was cancelled" a fraction
        // of a second after the start was sent, so Simkl was never told the
        // session began and the title never showed as now-playing — while the
        // independently-sent MDBList mirror did get through (its postScrobble
        // is already uncancellable) and merely failed its own validation.
        // Let the request finish out of cancellation's reach: /scrobble/start
        // replaces any existing session, so a late duplicate is harmless.
        val response =
            uncancellable {
                when (action) {
                    "pause" ->
                        api.scrobblePause(
                            authorization =
                                trackedAuthHeader(),

                            body =
                                body
                        )

                    "stop" ->
                        api.scrobbleStop(
                            authorization =
                                trackedAuthHeader(),

                            body =
                                body
                        )

                    else ->
                        api.scrobbleStart(
                            authorization =
                                trackedAuthHeader(),

                            body =
                                body
                        )
                }
            }

        // A stop for a session Simkl has ALREADY ended answers 409 Conflict,
        // with the session it dropped in the body (and 404 when it never saw
        // one). That is the ordinary end of a finished episode, not a failure:
        // logging it as an error put a red line in the log per playback, and
        // reporting it as a failure told the caller to retry a stop that can
        // never succeed. Success here means "the session is over", which is
        // exactly what this call is for.
        if (action == "stop" && response.code() in TERMINAL_STOP_CODES) {
            Log.i(
                "SIMKL_REPO",
                "scrobble/stop already ended " +
                    "code=${response.code()}"
            )

            return true
        }

        if (!response.isSuccessful) {
            val errorText =
                try {
                    response
                        .errorBody()
                        ?.string()
                } catch (e: Exception) {
                    "unreadable: " +
                        e.message
                }

            Log.e(
                "SIMKL_REPO",
                "scrobble/$action failed " +
                    "code=${response.code()} " +
                    "body=$errorText"
            )
        } else {
            Log.i(
                "SIMKL_REPO",
                "scrobble/$action ok " +
                    "id=$parentId tmdb=$tmdbId progress=$progress"
            )
        }

        response.isSuccessful
    } catch (e: Exception) {
        Log.e(
            "SIMKL_REPO",
            "scrobble/$action error: " +
                e.message,
            e
        )

        false
    }
}
