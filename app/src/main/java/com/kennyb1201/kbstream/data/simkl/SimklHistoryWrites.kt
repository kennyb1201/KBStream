package com.kennyb1201.kbstream.data.simkl

import android.util.Log
import com.kennyb1201.kbstream.data.runCatchingCancellable

/*
 * Outbound watched-history writes for [SimklRepository]: the POST
 * /sync/history and POST /sync/history/remove calls behind "Mark as
 * Watched" / "Mark as Unwatched" (poster long-press), season long-press,
 * and the player's completion scrobble.
 *
 * These live outside SimklRepository.kt only to keep that file readable —
 * they are still SimklRepository's behavior, called through same-named
 * members on the repository. Every entry point here logs and returns
 * false instead of throwing, so playback and UI actions are never blocked
 * by tracking failures.
 *
 * All of them share the same contract:
 *  - bail out (false) when Simkl is unconfigured, unauthenticated, or the
 *    ids don't resolve to a Simkl reference;
 *  - invalidate the cached watched snapshots so markers and the Continue
 *    Watching rail reflect the new state immediately;
 *  - close any open playback sessions for the just-marked titles so they
 *    can't resurface in Continue Watching at their pre-mark progress.
 */

suspend fun SimklRepository.pushWatchedMovieImpl(
    imdbId: String,
    title: String? = null,
    tmdbId: Int? = null,
    // False while a drain is replaying a queued entry (see
    // SimklCompletionSubmission): a parked impl must then answer "not sent"
    // instead of queueing itself again.
    queueWhenParked: Boolean = true
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "pushWatchedMovie skipped: not configured/authenticated")
        return false
    }

    val ids = parsePlaybackIds(imdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "pushWatchedMovie skipped: unparseable id=$imdbId")
        return false
    }

    // F1 (spec 2026-11-20): a park is checked BEFORE the request, exactly as the
    // scrobble path has done since spec C14. Without this gate every completion
    // retry spent a request to earn the same bare 429 for the whole session - and
    // the players' latch re-posts the completion on the next tick, so one parked
    // episode produced a stream of them. F2 is the other half: the write is
    // QUEUED rather than lost, and true tells the latch it was accepted.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i("SIMKL_REPO", "pushWatchedMovie skipped: parked for ${parkedMs}ms after a 429")
        return queueWhenParked && queueSimklCompletion(
            simklCompletionEntry(
                kind = SimklCompletionKind.PUSH_MOVIE,
                imdbId = imdbId,
                tmdbId = tmdbId,
                title = title
            )
        )
    }

    return try {
        val response = api.addToWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                movies = listOf(
                    SimklHistoryMovie(
                        title = title,
                        ids = ids
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            // A 429 is an instruction: park this leg for its Retry-After (a
            // minute by default) so the next event is held back instead of
            // earning the same bare 429, and say which happened in the log
            // (spec C14). The caller still sees a plain false - the completion
            // latch releases on it and retries in-session.
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "pushWatchedMovie " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
            // A 429 is a park, not a failure: the completion is still owed, so it
            // is queued for the drain and reported as accepted (F2).
            if (rateLimited && queueWhenParked) {
                if (
                    queueSimklCompletion(
                        simklCompletionEntry(
                            kind = SimklCompletionKind.PUSH_MOVIE,
                            imdbId = imdbId,
                            tmdbId = tmdbId,
                            title = title
                        )
                    )
                ) {
                    return true
                }
            }
        } else {
            // A fresh watched write invalidates the completed-movie
            // snapshot and the Continue Watching feed so watched markers
            // and the rail reflect the new state immediately.
            invalidateWatchedSnapshots()

            // Close any open playback session for the movie so the just-
            // watched title can't resurface in Continue Watching at its
            // pre-completion progress (e.g. "99% watched").
            runCatchingCancellable {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = imdbId,
                    tmdbId = tmdbId
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedMovie ok imdb=$imdbId")
            // A write that actually went out means the leg is open again, which is
            // the one moment a completion queued during a park can be delivered
            // (F2 drain trigger). Silent when nothing is queued.
            requestSimklCompletionDrain()
        }

        response.isSuccessful
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Cancellation is not a failure: rethrow so the scope that was
        // canceled stops here instead of logging it and carrying on as if the
        // work had simply failed.
        throw e
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "pushWatchedMovie error: ${e.message}", e)
        false
    }
}

/*
 * Record a WHOLE show as watched (POST /sync/history). Sending a show with
 * no seasons array makes Simkl implicitly auto-fill every episode as
 * watched — the documented "mark whole show watched" behavior. Used by the
 * poster long-press "Mark as Watched".
 */
suspend fun SimklRepository.pushWatchedShowImpl(
    showImdbId: String,
    title: String? = null,
    tmdbId: Int? = null,
    queueWhenParked: Boolean = true
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "pushWatchedShow skipped: not configured/authenticated")
        return false
    }

    val ids = parsePlaybackIds(showImdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "pushWatchedShow skipped: unparseable id=$showImdbId")
        return false
    }

    // F1: pre-fire park gate, as in pushWatchedMovie.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i("SIMKL_REPO", "pushWatchedShow skipped: parked for ${parkedMs}ms after a 429")
        return queueWhenParked && queueSimklCompletion(
            simklCompletionEntry(
                kind = SimklCompletionKind.PUSH_SHOW,
                imdbId = showImdbId,
                tmdbId = tmdbId,
                title = title
            )
        )
    }

    return try {
        val response = api.addToWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                shows = listOf(
                    SimklHistoryShow(
                        title = title,
                        ids = ids,
                        seasons = null
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "pushWatchedShow " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
            if (rateLimited && queueWhenParked) {
                if (
                    queueSimklCompletion(
                        simklCompletionEntry(
                            kind = SimklCompletionKind.PUSH_SHOW,
                            imdbId = showImdbId,
                            tmdbId = tmdbId,
                            title = title
                        )
                    )
                ) {
                    return true
                }
            }
        } else {
            // A fresh watched write invalidates the cached show library
            // and the Continue Watching feed so episode-watched filters
            // and the rail reflect the new state immediately.
            invalidateWatchedSnapshots()

            // Close any open playback sessions for the show so the just-
            // watched title can't resurface in Continue Watching at its
            // pre-completion progress (e.g. "99% watched").
            runCatchingCancellable {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = showImdbId,
                    tmdbId = tmdbId
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedShow ok show=$showImdbId")
            requestSimklCompletionDrain()
        }

        response.isSuccessful
    } catch (e: kotlinx.coroutines.CancellationException) {
        // See pushWatchedMovie.
        throw e
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "pushWatchedShow error: ${e.message}", e)
        false
    }
}

/*
 * "Mark unwatched": POST /sync/history/remove for a whole movie. Called
 * from the poster long-press "Mark as Unwatched" menu action so the title
 * leaves the user's Simkl history when it is unmarked locally.
 */
suspend fun SimklRepository.removeWatchedMovieImpl(
    imdbId: String,
    title: String? = null,
    tmdbId: Int? = null
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "removeWatchedMovie skipped: not configured/authenticated")
        return false
    }

    val ids = parsePlaybackIds(imdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "removeWatchedMovie skipped: unparseable id=$imdbId")
        return false
    }

    // An unmark takes back a queued push for the same title: a completion queued
    // during a park must never land after the viewer unmarked it (F2). Done
    // before the gate and before the request, because the intent - this title is
    // not watched - is what cancels the queue, not whether the removal itself
    // succeeds.
    cancelQueuedSimklCompletions(imdbId, SimklCompletionKind.PUSH_MOVIE)

    // F1: removals are gated too. Fired into a park they earn the same bare 429,
    // and a removal is never queued - a failed one is visible and user-retryable.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i("SIMKL_REPO", "removeWatchedMovie skipped: parked for ${parkedMs}ms after a 429")
        return false
    }

    return try {
        val response = api.removeFromWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                movies = listOf(
                    SimklHistoryMovie(
                        title = title,
                        ids = ids
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "removeWatchedMovie " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
        } else {
            // Drop every watched snapshot - memory and disk - so a later
            // preload re-fetches fresh remote state instead of resurrecting
            // this title from a stale completed-list snapshot or the 12h
            // show-library blob.
            invalidateWatchedSnapshots()

            Log.i("SIMKL_REPO", "removeWatchedMovie ok imdb=$imdbId")
        }

        response.isSuccessful
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "removeWatchedMovie error: ${e.message}", e)
        false
    }
}

/*
 * "Mark unwatched": POST /sync/history/remove for a WHOLE show. Sending the
 * show with no seasons array removes every episode of it from the user's
 * Simkl history at once, the mirror of [pushWatchedShowImpl].
 */
suspend fun SimklRepository.removeWatchedShowImpl(
    showImdbId: String,
    title: String? = null,
    tmdbId: Int? = null
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "removeWatchedShow skipped: not configured/authenticated")
        return false
    }

    val ids = parsePlaybackIds(showImdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "removeWatchedShow skipped: unparseable id=$showImdbId")
        return false
    }

    // A whole-show unmark cancels everything queued for that show (F2).
    cancelQueuedSimklCompletions(showImdbId, SimklCompletionKind.PUSH_SHOW)

    // F1: removals are gated too, and never queued.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i("SIMKL_REPO", "removeWatchedShow skipped: parked for ${parkedMs}ms after a 429")
        return false
    }

    return try {
        val response = api.removeFromWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                shows = listOf(
                    SimklHistoryShow(
                        title = title,
                        ids = ids,
                        seasons = null
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "removeWatchedShow " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
        } else {
            invalidateWatchedSnapshots()

            Log.i("SIMKL_REPO", "removeWatchedShow ok show=$showImdbId")
        }

        response.isSuccessful
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "removeWatchedShow error: ${e.message}", e)
        false
    }
}

/** Player completion scrobble for a single episode (POST /sync/history). */
suspend fun SimklRepository.pushWatchedEpisodeImpl(
    showImdbId: String,
    season: Int,
    episode: Int,
    title: String? = null,
    tmdbId: Int? = null,
    queueWhenParked: Boolean = true
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "pushWatchedEpisode skipped: not configured/authenticated")
        return false
    }

    if (showImdbId.isBlank() || season <= 0 || episode <= 0) {
        Log.i("SIMKL_REPO", "pushWatchedEpisode skipped: ids incomplete show=$showImdbId s=$season e=$episode")
        return false
    }

    val ids = parsePlaybackIds(showImdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "pushWatchedEpisode skipped: unparseable id=$showImdbId")
        return false
    }

    // F1 + F2: the completion that used to be lost with the session. Parked, it
    // is queued (and answered true, which releases the player's latch); a 429
    // below queues it too. The episode is identified by (parent, season,
    // episode) so a re-queue collapses and an unmark can cancel it.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i(
            "SIMKL_REPO",
            "pushWatchedEpisode skipped: parked for ${parkedMs}ms after a 429 " +
                "show=$showImdbId s=$season e=$episode"
        )
        return queueWhenParked && queueSimklCompletion(
            simklCompletionEntry(
                kind = SimklCompletionKind.PUSH_EPISODE,
                imdbId = showImdbId,
                tmdbId = tmdbId,
                title = title,
                season = season,
                episodes = listOf(episode)
            )
        )
    }

    return try {
        val response = api.addToWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                shows = listOf(
                    SimklHistoryShow(
                        title = title,
                        ids = ids,
                        seasons = listOf(
                            SimklHistorySeason(
                                number = season,
                                episodes = listOf(SimklHistoryEpisode(number = episode))
                            )
                        )
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "pushWatchedEpisode " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
            if (rateLimited && queueWhenParked) {
                if (
                    queueSimklCompletion(
                        simklCompletionEntry(
                            kind = SimklCompletionKind.PUSH_EPISODE,
                            imdbId = showImdbId,
                            tmdbId = tmdbId,
                            title = title,
                            season = season,
                            episodes = listOf(episode)
                        )
                    )
                ) {
                    return true
                }
            }
        } else {
            // A fresh watched write invalidates the cached show library
            // and the Continue Watching feed so episode-watched filters
            // and the rail reflect the new state immediately.
            invalidateWatchedSnapshots()

            // Close the open playback session for this episode so the
            // just-watched episode can't resurface in Continue Watching
            // at its pre-completion progress (e.g. "99% watched").
            runCatchingCancellable {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = showImdbId,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedEpisode ok show=$showImdbId s=$season e=$episode")
            requestSimklCompletionDrain()
        }

        response.isSuccessful
    } catch (e: kotlinx.coroutines.CancellationException) {
        // See pushWatchedMovie.
        throw e
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "pushWatchedEpisode error: ${e.message}", e)
        false
    }
}

/*
 * "Mark a whole season watched": POST /sync/history with one season listing
 * every episode number, so Simkl marks exactly that season instead of
 * auto-filling every season of the show (which is what sending a show with
 * no seasons array does). Used by the season-chip long-press "Mark as
 * Watched" action.
 */
suspend fun SimklRepository.pushWatchedSeasonImpl(
    showImdbId: String,
    season: Int,
    episodes: List<Int>,
    title: String? = null,
    tmdbId: Int? = null,
    queueWhenParked: Boolean = true
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "pushWatchedSeason skipped: not configured/authenticated")
        return false
    }

    val validEpisodes = episodes.filter { it > 0 }.distinct().sorted()
    if (showImdbId.isBlank() || season <= 0 || validEpisodes.isEmpty()) {
        Log.i("SIMKL_REPO", "pushWatchedSeason skipped: ids incomplete show=$showImdbId s=$season eps=${validEpisodes.size}")
        return false
    }

    val ids = parsePlaybackIds(showImdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "pushWatchedSeason skipped: unparseable id=$showImdbId")
        return false
    }

    // F1 + F2: one season mark is one queued write, carrying its episode list.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i(
            "SIMKL_REPO",
            "pushWatchedSeason skipped: parked for ${parkedMs}ms after a 429 " +
                "show=$showImdbId s=$season eps=${validEpisodes.size}"
        )
        return queueWhenParked && queueSimklCompletion(
            simklCompletionEntry(
                kind = SimklCompletionKind.PUSH_SEASON,
                imdbId = showImdbId,
                tmdbId = tmdbId,
                title = title,
                season = season,
                episodes = validEpisodes
            )
        )
    }

    return try {
        val response = api.addToWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                shows = listOf(
                    SimklHistoryShow(
                        title = title,
                        ids = ids,
                        seasons = listOf(
                            SimklHistorySeason(
                                number = season,
                                episodes = validEpisodes.map { number ->
                                    SimklHistoryEpisode(number = number)
                                }
                            )
                        )
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "pushWatchedSeason " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
            if (rateLimited && queueWhenParked) {
                if (
                    queueSimklCompletion(
                        simklCompletionEntry(
                            kind = SimklCompletionKind.PUSH_SEASON,
                            imdbId = showImdbId,
                            tmdbId = tmdbId,
                            title = title,
                            season = season,
                            episodes = validEpisodes
                        )
                    )
                ) {
                    return true
                }
            }
        } else {
            // Drop the in-memory show snapshot so the next detail load
            // re-fetches fresh per-episode state from Simkl instead of
            // serving the pre-mark snapshot, and drop the Continue
            // Watching feed so any stale playback session for the just-
            // marked episodes gets filtered (and deleted) on the next
            // rail refresh instead of lingering at its old progress.
            invalidateWatchedSnapshots()

            // Close open playback sessions for the marked episodes so
            // they can't resurface in Continue Watching at their old
            // progress.
            runCatchingCancellable {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = showImdbId,
                    tmdbId = tmdbId,
                    season = season,
                    episodes = validEpisodes
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedSeason ok show=$showImdbId s=$season eps=${validEpisodes.size}")
            requestSimklCompletionDrain()
        }

        response.isSuccessful
    } catch (e: kotlinx.coroutines.CancellationException) {
        // See pushWatchedMovie.
        throw e
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "pushWatchedSeason error: ${e.message}", e)
        false
    }
}

/*
 * "Mark a whole season unwatched": POST /sync/history/remove with one season
 * listing every episode number, so Simkl removes exactly that season's
 * episodes from history while other seasons stay. The mirror of
 * [pushWatchedSeasonImpl].
 */
suspend fun SimklRepository.removeWatchedSeasonImpl(
    showImdbId: String,
    season: Int,
    episodes: List<Int>,
    title: String? = null,
    tmdbId: Int? = null
): Boolean {

    if (!isConfigured() || !hasToken()) {
        Log.i("SIMKL_REPO", "removeWatchedSeason skipped: not configured/authenticated")
        return false
    }

    val validEpisodes = episodes.filter { it > 0 }.distinct().sorted()
    if (showImdbId.isBlank() || season <= 0 || validEpisodes.isEmpty()) {
        Log.i("SIMKL_REPO", "removeWatchedSeason skipped: ids incomplete show=$showImdbId s=$season eps=${validEpisodes.size}")
        return false
    }

    val ids = parsePlaybackIds(showImdbId, tmdbId)
    if (ids == null) {
        Log.i("SIMKL_REPO", "removeWatchedSeason skipped: unparseable id=$showImdbId")
        return false
    }

    // A season unmark cancels that season's queued pushes - the season write and
    // any single-episode ones inside it (F2).
    cancelQueuedSimklCompletions(
        imdbId = showImdbId,
        kind = SimklCompletionKind.PUSH_SEASON,
        season = season
    )

    // F1: removals are gated too, and never queued.
    val parkedMs = simklPlaybackParkMs()
    if (parkedMs > 0L) {
        Log.i(
            "SIMKL_REPO",
            "removeWatchedSeason skipped: parked for ${parkedMs}ms after a 429 " +
                "show=$showImdbId s=$season eps=${validEpisodes.size}"
        )
        return false
    }

    return try {
        val response = api.removeFromWatchedHistory(
            authorization = trackedAuthHeader(),
            body = SimklHistoryRequest(
                shows = listOf(
                    SimklHistoryShow(
                        title = title,
                        ids = ids,
                        seasons = listOf(
                            SimklHistorySeason(
                                number = season,
                                episodes = validEpisodes.map { number ->
                                    SimklHistoryEpisode(number = number)
                                }
                            )
                        )
                    )
                )
            )
        )

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            val rateLimited = noteSimklPlaybackRateLimit(
                response.code(),
                response.headers()["Retry-After"]
            )
            Log.e(
                "SIMKL_REPO",
                "removeWatchedSeason " + (if (rateLimited) "rate-limited " else "failed ") +
                    "code=${response.code()} body=$errorText"
            )
        } else {
            // Drop the in-memory show snapshot so the next detail load
            // re-fetches fresh per-episode state from Simkl instead of
            // resurrecting this season from the stale snapshot.
            // Drop BOTH watched snapshots this write invalidates:
            //
            // - the show library (memory and the 12h disk blob), whose
            //   pre-write copy still counts the unmarked season as watched
            //   everywhere the badges resolve from - which is why a series
            //   unmarked season-by-season kept its completed checkmark
            //   instead of the eye;
            // - the Continue Watching feed, whose cached list was built
            //   while the show was still fully watched (so the feed's own
            //   caught-up filter had excluded it), which is why it never
            //   returned to the rail until the app was restarted.
            invalidateWatchedSnapshots()

            Log.i("SIMKL_REPO", "removeWatchedSeason ok show=$showImdbId s=$season eps=${validEpisodes.size}")
        }

        response.isSuccessful
    } catch (e: Exception) {
        Log.e("SIMKL_REPO", "removeWatchedSeason error: ${e.message}", e)
        false
    }
}

/*
 * ---------------------------------------------------------------------------
 * The completion queue's side of the contract (spec 2026-11-20, F1 + F2)
 * ---------------------------------------------------------------------------
 *
 * The gate and the queue live AT THE IMPLS rather than at the call sites, so the
 * two things that write history - the players' completion sync and a manual
 * "marked watched" from a poster long-press - are covered by one rule. Nothing
 * that calls these functions had to change.
 *
 * Audit rule for this file: every `noteSimklPlaybackRateLimit` site (the seven
 * write impls and the scrobble path) has a matching `simklPlaybackParkMs()` gate
 * before it fires. A future write that notes the park gets the gate in the same
 * commit, or the class regrows the defect the field found.
 */

/** The write a parked impl hands to the queue, in the queue's own shape. */
private fun simklCompletionEntry(
    kind: SimklCompletionKind,
    imdbId: String,
    tmdbId: Int?,
    title: String?,
    season: Int = 0,
    episodes: List<Int> = emptyList()
): SimklCompletionEntry = SimklCompletionEntry(
    kind = kind.name,
    imdbId = imdbId,
    tmdbId = tmdbId,
    title = title,
    season = season,
    episodes = episodes,
    profileId = com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.value?.id
        ?: "",
    enqueuedAt = System.currentTimeMillis()
)

/**
 * Queues [entry]. True only when it is now safely on disk.
 *
 * The caller answers that true to the player's latch, which is the point: the
 * write is ACCEPTED, not merely failed, so the completion stops being re-posted
 * every tick and `trackers: completion` reads ok (F2's return contract).
 */
private fun queueSimklCompletion(entry: SimklCompletionEntry): Boolean {
    val app = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext ?: return false
    val queued = SimklCompletionQueue.enqueue(app, entry)
    if (queued) {
        Log.i(
            "SIMKL_REPO",
            "${entry.kind} queued for ${entry.title ?: entry.imdbId}: the playback leg is " +
                "parked, so this drains after the park instead of being lost with the session"
        )
        // Cheap and useful: the drain sees the park and schedules the wake.
        SimklCompletionSubmission.requestDrain(app)
    }
    return queued
}

/** Asks the drain to run; it returns immediately when nothing is queued. */
private fun requestSimklCompletionDrain() {
    val app = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext ?: return
    if (SimklCompletionQueue.depth(app) == 0) return
    SimklCompletionSubmission.requestDrain(app)
}

/** An unmark cancels the queued pushes it takes back (F2). */
private fun cancelQueuedSimklCompletions(
    imdbId: String,
    kind: SimklCompletionKind,
    season: Int = 0,
    episodes: List<Int> = emptyList()
) {
    val app = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext ?: return
    SimklCompletionQueue.cancelQueuedPushes(
        context = app,
        imdbId = imdbId,
        kind = kind,
        season = season,
        episodes = episodes
    )
}
