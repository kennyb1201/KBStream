package com.kennyb1201.kbstream.data.simkl

import android.util.Log

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
    tmdbId: Int? = null
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
            Log.e("SIMKL_REPO", "pushWatchedMovie failed code=${response.code()} body=$errorText")
        } else {
            // A fresh watched write invalidates the completed-movie
            // snapshot and the Continue Watching feed so watched markers
            // and the rail reflect the new state immediately.
            invalidateWatchedSnapshots()

            // Close any open playback session for the movie so the just-
            // watched title can't resurface in Continue Watching at its
            // pre-completion progress (e.g. "99% watched").
            runCatching {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = imdbId,
                    tmdbId = tmdbId
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedMovie ok imdb=$imdbId")
        }

        response.isSuccessful
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
    tmdbId: Int? = null
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
            Log.e("SIMKL_REPO", "pushWatchedShow failed code=${response.code()} body=$errorText")
        } else {
            // A fresh watched write invalidates the cached show library
            // and the Continue Watching feed so episode-watched filters
            // and the rail reflect the new state immediately.
            invalidateWatchedSnapshots()

            // Close any open playback sessions for the show so the just-
            // watched title can't resurface in Continue Watching at its
            // pre-completion progress (e.g. "99% watched").
            runCatching {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = showImdbId,
                    tmdbId = tmdbId
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedShow ok show=$showImdbId")
        }

        response.isSuccessful
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
            Log.e("SIMKL_REPO", "removeWatchedMovie failed code=${response.code()} body=$errorText")
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
            Log.e("SIMKL_REPO", "removeWatchedShow failed code=${response.code()} body=$errorText")
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
    tmdbId: Int? = null
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
            Log.e("SIMKL_REPO", "pushWatchedEpisode failed code=${response.code()} body=$errorText")
        } else {
            // A fresh watched write invalidates the cached show library
            // and the Continue Watching feed so episode-watched filters
            // and the rail reflect the new state immediately.
            invalidateWatchedSnapshots()

            // Close the open playback session for this episode so the
            // just-watched episode can't resurface in Continue Watching
            // at its pre-completion progress (e.g. "99% watched").
            runCatching {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = showImdbId,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedEpisode ok show=$showImdbId s=$season e=$episode")
        }

        response.isSuccessful
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
    tmdbId: Int? = null
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
            Log.e("SIMKL_REPO", "pushWatchedSeason failed code=${response.code()} body=$errorText")
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
            runCatching {
                deleteOpenPlaybackSessionsForWatched(
                    parentId = showImdbId,
                    tmdbId = tmdbId,
                    season = season,
                    episodes = validEpisodes
                )
            }

            Log.i("SIMKL_REPO", "pushWatchedSeason ok show=$showImdbId s=$season eps=${validEpisodes.size}")
        }

        response.isSuccessful
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
            Log.e("SIMKL_REPO", "removeWatchedSeason failed code=${response.code()} body=$errorText")
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
