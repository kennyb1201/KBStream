package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SimklRepository(
    context: Context? = null
) {

    private val clientId =
        BuildConfig.SIMKL_CLIENT_ID

    private val clientSecret =
        BuildConfig.SIMKL_CLIENT_SECRET

    private val prefs =
        context
            ?.applicationContext
            ?.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

    private val moshi =
        Moshi.Builder()
            .add(
                KotlinJsonAdapterFactory()
            )
            .build()

    private val okHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                SimklQueryInterceptor(
                    clientId = clientId,
                    appName = SimklConfig.APP_NAME,
                    appVersion = SimklConfig.APP_VERSION
                )
            )
            .build()

    private val api: SimklApiService =
        Retrofit.Builder()
            .baseUrl(
                SimklConfig.BASE_URL
            )
            .client(
                okHttpClient
            )
            .addConverterFactory(
                MoshiConverterFactory.create(
                    moshi
                )
            )
            .build()
            .create(
                SimklApiService::class.java
            )

    private val allShowItemsMutex =
        Mutex()

    private var cachedAllShowItems:
        SimklAllShowsResponse? = null

    private var cachedAllShowItemsFetchedAt =
        0L

    private val tmdbJsonCacheDao:
        TmdbJsonCacheDao? =
        context
            ?.applicationContext
            ?.let {
                WatchHistoryDatabase
                    .getInstance(it)
                    .tmdbJsonCacheDao()
            }

    private val allShowsJsonAdapter:
        JsonAdapter<SimklAllShowsResponse> =
        moshi.adapter(
            SimklAllShowsResponse::class.java
        )

    private val continueWatchingJsonAdapter:
        JsonAdapter<List<SimklContinueWatchingItem>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                SimklContinueWatchingItem::class.java
            )
        )

    private val completedMovieKeysJsonAdapter:
        JsonAdapter<List<String>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                String::class.java
            )
        )

    private val completedMovieKeysMutex =
        Mutex()

    private var cachedCompletedMovieKeys:
        Set<String>? =
        null

    private var cachedCompletedMovieKeysFetchedAt =
        0L

    fun isConfigured(): Boolean {
        return clientId.isNotBlank() &&
            clientSecret.isNotBlank()
    }

    fun hasToken(): Boolean {
        // NOTE: deliberately no logging here - this getter is polled on the
        // UI/main thread several times a second, and logging on every call
        // flooded logcat so heavily it crashed logd and drowned out real
        // diagnostics (just like the trailer-cobalt lines here).
        return !getSavedAccessToken().isNullOrBlank()
    }

    fun getSavedAccessToken(): String? {
        return prefs?.getString(
            KEY_ACCESS_TOKEN,
            null
        )
    }

    fun clearAuth() {
        cachedContinueWatching =
            null

        cachedAllShowItems =
            null

        cachedAllShowItemsFetchedAt =
            0L

        cachedCompletedMovieKeys =
            null

        cachedCompletedMovieKeysFetchedAt =
            0L

        CoroutineScope(
            Dispatchers.IO
        ).launch {
            runCatching {
                tmdbJsonCacheDao?.deleteByKeys(
                    listOf(
                        ALL_SHOW_ITEMS_DISK_KEY,
                        CONTINUE_WATCHING_DISK_KEY,
                        COMPLETED_MOVIES_DISK_KEY
                    )
                )
            }
        }

        prefs
            ?.edit()
            ?.remove(
                KEY_ACCESS_TOKEN
            )
            ?.remove(
                KEY_LAST_WATCHED_ACTIVITY_ALL
            )
            ?.apply()
    }

    suspend fun createPinCode():
        SimklPinCodeResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        return api.createPinCode()
    }

    suspend fun checkPin(
        userCode: String
    ): SimklTokenResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        val response =
            api.checkPin(
                userCode
            )

        response.accessToken
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                saveAccessToken(
                    it
                )
            }

        return response
    }

    suspend fun getActivities(
        accessToken: String =
            requireAccessToken()
    ): SimklActivitiesResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        return api.getActivities(
            authorization =
                bearer(
                    accessToken
                )
        )
    }

    suspend fun hasWatchedActivityChanged(
        accessToken: String =
            requireAccessToken()
    ): Boolean {

        if (
            !isConfigured() ||
            !hasToken()
        ) {
            return false
        }

        val activities =
            runCatching {
                getActivities(
                    accessToken
                )
            }
                .onFailure {
                    Log.e(
                        "SIMKL_REPO",
                        "getActivities failed: " +
                            it.message,
                        it
                    )
                }
                .getOrNull()
                ?: return false

        val latest =
            activities.all
                ?.trim()
                .orEmpty()

        if (
            latest.isBlank()
        ) {
            Log.e(
                "SIMKL_REPO",
                "activities.all was blank; " +
                    "treating as unchanged"
            )

            return false
        }

        val saved =
            getSavedWatchedActivityAll()
                .orEmpty()

        val changed =
            saved != latest

        Log.e(
            "SIMKL_REPO",
            "hasWatchedActivityChanged " +
                "changed=$changed " +
                "saved=$saved " +
                "latest=$latest"
        )

        return changed
    }

    suspend fun markWatchedActivitySynced(
        accessToken: String =
            requireAccessToken()
    ) {

        if (
            !isConfigured() ||
            !hasToken()
        ) {
            return
        }

        val activities =
            runCatching {
                getActivities(
                    accessToken
                )
            }
                .onFailure {
                    Log.e(
                        "SIMKL_REPO",
                        "markWatchedActivitySynced " +
                            "getActivities failed: " +
                            it.message,
                        it
                    )
                }
                .getOrNull()
                ?: return

        val latest =
            activities.all
                ?.trim()
                .orEmpty()

        if (
            latest.isBlank()
        ) {
            return
        }

        saveWatchedActivityAll(
            latest
        )

        Log.e(
            "SIMKL_REPO",
            "markWatchedActivitySynced " +
                "saved activity all=$latest"
        )
    }

    /*
     * Outbound scrobble: record a completed movie or episode to the
     * user's Simkl history (POST /sync/history). Called from the player
     * when playback completes; failures are logged, never thrown, so
     * playback is never blocked by tracking.
     */
    suspend fun pushWatchedMovie(
        imdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "pushWatchedMovie skipped: not configured/authenticated")
            return false
        }

        val ids = parsePlaybackIds(imdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "pushWatchedMovie skipped: unparseable id=$imdbId")
            return false
        }

        return try {
            val response = api.addToWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                Log.d("SIMKL_REPO", "pushWatchedMovie ok imdb=$imdbId")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "pushWatchedMovie error: ${e.message}", e)
            false
        }
    }

    /*
     * Outbound scrobble: record a WHOLE show as watched (POST
     * /sync/history). Sending a show with no seasons array makes Simkl
     * implicitly auto-fill every episode as watched — the documented
     * "mark whole show watched" behavior. Used by the poster long-press
     * "Mark as Watched". Failures are logged, never thrown.
     */
    suspend fun pushWatchedShow(
        showImdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "pushWatchedShow skipped: not configured/authenticated")
            return false
        }

        val ids = parsePlaybackIds(showImdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "pushWatchedShow skipped: unparseable id=$showImdbId")
            return false
        }

        return try {
            val response = api.addToWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                Log.d("SIMKL_REPO", "pushWatchedShow ok show=$showImdbId")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "pushWatchedShow error: ${e.message}", e)
            false
        }
    }

    /*
     * Outbound "mark unwatched": POST /sync/history/remove for a whole
     * movie. Called from the poster long-press "Mark as Unwatched" menu
     * action so the title leaves the user's Simkl history when it is
     * unmarked locally. Failures are logged, never thrown.
     */
    suspend fun removeWatchedMovie(
        imdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "removeWatchedMovie skipped: not configured/authenticated")
            return false
        }

        val ids = parsePlaybackIds(imdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "removeWatchedMovie skipped: unparseable id=$imdbId")
            return false
        }

        return try {
            val response = api.removeFromWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                // Drop the in-memory watched sets so a later preload re-fetches
                // fresh remote state instead of resurrecting this title from a
                // stale completed-list snapshot.
                cachedCompletedMovieKeys = null
                cachedCompletedMovieKeysFetchedAt = 0L
                cachedAllShowItems = null
                cachedAllShowItemsFetchedAt = 0L

                // The Continue Watching feed is snapshotted in-memory and on
                // disk; drop it so the removed title doesn't resurface from
                // the stale snapshot on the next rail refresh.
                clearContinueWatchingCache()

                Log.d("SIMKL_REPO", "removeWatchedMovie ok imdb=$imdbId")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "removeWatchedMovie error: ${e.message}", e)
            false
        }
    }

    /*
     * Outbound "mark unwatched": POST /sync/history/remove for a WHOLE
     * show. Sending the show with no seasons array removes every episode of
     * it from the user's Simkl history at once, the mirror of
     * [pushWatchedShow]. Failures are logged, never thrown.
     */
    suspend fun removeWatchedShow(
        showImdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "removeWatchedShow skipped: not configured/authenticated")
            return false
        }

        val ids = parsePlaybackIds(showImdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "removeWatchedShow skipped: unparseable id=$showImdbId")
            return false
        }

        return try {
            val response = api.removeFromWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                cachedCompletedMovieKeys = null
                cachedCompletedMovieKeysFetchedAt = 0L
                cachedAllShowItems = null
                cachedAllShowItemsFetchedAt = 0L

                // The Continue Watching feed is snapshotted in-memory and on
                // disk; drop it so the removed title doesn't resurface from
                // the stale snapshot on the next rail refresh.
                clearContinueWatchingCache()

                Log.d("SIMKL_REPO", "removeWatchedShow ok show=$showImdbId")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "removeWatchedShow error: ${e.message}", e)
            false
        }
    }

    /**
     * Drops the in-memory and on-disk Continue Watching snapshot so the next
     * rail refresh re-fetches from Simkl instead of serving the stale list
     * (e.g. right after a title was removed from history via
     * [removeWatchedMovie]/[removeWatchedShow]).
     */
    private suspend fun clearContinueWatchingCache() {
        cachedContinueWatching = null
        runCatching {
            tmdbJsonCacheDao?.deleteByKeys(
                listOf(CONTINUE_WATCHING_DISK_KEY)
            )
        }
    }

    suspend fun pushWatchedEpisode(
        showImdbId: String,
        season: Int,
        episode: Int,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "pushWatchedEpisode skipped: not configured/authenticated")
            return false
        }

        if (showImdbId.isBlank() || season <= 0 || episode <= 0) {
            Log.d("SIMKL_REPO", "pushWatchedEpisode skipped: ids incomplete show=$showImdbId s=$season e=$episode")
            return false
        }

        val ids = parsePlaybackIds(showImdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "pushWatchedEpisode skipped: unparseable id=$showImdbId")
            return false
        }

        return try {
            val response = api.addToWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                Log.d("SIMKL_REPO", "pushWatchedEpisode ok show=$showImdbId s=$season e=$episode")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "pushWatchedEpisode error: ${e.message}", e)
            false
        }
    }

    /*
     * Outbound "mark a whole season watched": POST /sync/history with one
     * season listing every episode number, so Simkl marks exactly that
     * season instead of auto-filling every season of the show (which is
     * what sending a show with no seasons array does). Used by the
     * season-chip long-press "Mark as Watched" action. Failures are
     * logged, never thrown.
     */
    suspend fun pushWatchedSeason(
        showImdbId: String,
        season: Int,
        episodes: List<Int>,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "pushWatchedSeason skipped: not configured/authenticated")
            return false
        }

        val validEpisodes = episodes.filter { it > 0 }.distinct().sorted()
        if (showImdbId.isBlank() || season <= 0 || validEpisodes.isEmpty()) {
            Log.d("SIMKL_REPO", "pushWatchedSeason skipped: ids incomplete show=$showImdbId s=$season eps=${validEpisodes.size}")
            return false
        }

        val ids = parsePlaybackIds(showImdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "pushWatchedSeason skipped: unparseable id=$showImdbId")
            return false
        }

        return try {
            val response = api.addToWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                // serving the pre-mark snapshot.
                cachedAllShowItems = null
                cachedAllShowItemsFetchedAt = 0L

                Log.d("SIMKL_REPO", "pushWatchedSeason ok show=$showImdbId s=$season eps=${validEpisodes.size}")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "pushWatchedSeason error: ${e.message}", e)
            false
        }
    }

    /*
     * Outbound "mark a whole season unwatched": POST /sync/history/remove
     * with one season listing every episode number, so Simkl removes
     * exactly that season's episodes from history while other seasons stay.
     * The mirror of [pushWatchedSeason]. Failures are logged, never thrown.
     */
    suspend fun removeWatchedSeason(
        showImdbId: String,
        season: Int,
        episodes: List<Int>,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean {

        if (!isConfigured() || !hasToken()) {
            Log.d("SIMKL_REPO", "removeWatchedSeason skipped: not configured/authenticated")
            return false
        }

        val validEpisodes = episodes.filter { it > 0 }.distinct().sorted()
        if (showImdbId.isBlank() || season <= 0 || validEpisodes.isEmpty()) {
            Log.d("SIMKL_REPO", "removeWatchedSeason skipped: ids incomplete show=$showImdbId s=$season eps=${validEpisodes.size}")
            return false
        }

        val ids = parsePlaybackIds(showImdbId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "removeWatchedSeason skipped: unparseable id=$showImdbId")
            return false
        }

        return try {
            val response = api.removeFromWatchedHistory(
                authorization = bearer(requireAccessToken()),
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
                cachedAllShowItems = null
                cachedAllShowItemsFetchedAt = 0L

                Log.d("SIMKL_REPO", "removeWatchedSeason ok show=$showImdbId s=$season eps=${validEpisodes.size}")
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "removeWatchedSeason error: ${e.message}", e)
            false
        }
    }

    /*
     * Outbound "Remove from Continue Watching" for Simkl-backed cards:
     * deletes the paused playback session (the progress record behind the
     * Continue Watching feed) so the title stops coming back from the
     * remote feed even though watch history is stored separately. Used
     * together with the history removals above. Failures are logged, never
     * thrown.
     */
    suspend fun deletePlaybackSession(
        playbackId: Int?
    ): Boolean {

        if (
            !isConfigured() ||
            !hasToken() ||
            playbackId == null ||
            playbackId <= 0
        ) {
            Log.d(
                "SIMKL_REPO",
                "deletePlaybackSession skipped: " +
                    "no valid playback id=$playbackId"
            )
            return false
        }

        return try {
            val response = api.deletePlaybackSession(
                id = playbackId,
                authorization = bearer(requireAccessToken())
            )

            if (!response.isSuccessful) {
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
                clearContinueWatchingCache()

                Log.d(
                    "SIMKL_REPO",
                    "deletePlaybackSession ok id=$playbackId"
                )
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e(
                "SIMKL_REPO",
                "deletePlaybackSession error: ${e.message}",
                e
            )
            false
        }
    }

    /*
     * Live scrobble (POST /scrobble/start|pause|stop). Called from the
     * player on play/pause/end so Simkl records in-progress playback and
     * extrapolates the watch between events. Failures are logged, never
     * thrown, so playback is never blocked by tracking.
     */
    suspend fun scrobble(
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
            Log.d("SIMKL_REPO", "scrobble/$action skipped: not configured/authenticated")
            return false
        }

        val ids = parsePlaybackIds(parentId, tmdbId)
        if (ids == null) {
            Log.d("SIMKL_REPO", "scrobble/$action skipped: unparseable id=$parentId")
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
            val response =
                when (action) {
                    "pause" ->
                        api.scrobblePause(
                            authorization =
                                bearer(
                                    requireAccessToken()
                                ),

                            body =
                                body
                        )

                    "stop" ->
                        api.scrobbleStop(
                            authorization =
                                bearer(
                                    requireAccessToken()
                                ),

                            body =
                                body
                        )

                    else ->
                        api.scrobbleStart(
                            authorization =
                                bearer(
                                    requireAccessToken()
                                ),

                            body =
                                body
                        )
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
                Log.d(
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

    /*
     * Normalize a catalog item id into Simkl id refs. Accepts:
     *   imdb:tt1234567 / tt1234567  -> imdb
     *   tmdb:123 / 123              -> tmdb
     *   tvdb:123 (unknown flavors)  -> tmdb (when resolvedTmdbId is known)
     *   anything else               -> best-effort imdb passthrough
     * (movie/show refs may carry both imdb + tmdb; Simkl resolves
     * whichever identifier it can match server-side. A server-resolved
     * TMDB id wins over anything derived from the raw string, which is what
     * makes TVDB-sourced titles scrobble correctly.)
     */
    private fun parsePlaybackIds(
        rawId: String,
        resolvedTmdbId: Int? = null
    ): SimklPlaybackIdsRef? {

        val trimmed =
            rawId.trim()

        if (
            trimmed.isBlank()
        ) {
            return null
        }

        val lower =
            trimmed.lowercase()

        var imdb: String? = null
        var tmdb: Int? = resolvedTmdbId

        when {
            lower.startsWith("imdb:") ->
                imdb =
                    trimmed
                        .substringAfter(":")
                        .trim()
                        .takeIf { it.isNotBlank() }

            lower.startsWith("tt") ->
                imdb = trimmed.substringBefore(':')

            lower.startsWith("tmdb:") ->
                tmdb =
                    tmdb
                        ?: trimmed
                            .substringAfter(":")
                            .trim()
                            .toIntOrNull()

            trimmed.all(Char::isDigit) ->
                tmdb =
                    tmdb
                        ?: trimmed.toIntOrNull()

            else ->
                // Best-effort passthrough for unknown flavors; the resolved
                // tmdb above (when present) is the reliable identifier.
                imdb = trimmed
        }

        if (
            imdb == null &&
            tmdb == null
        ) {
            return null
        }

        return SimklPlaybackIdsRef(
            imdb = imdb,
            tmdb = tmdb
        )
    }

    fun forceClearWatchedActivitySync() {
        prefs
            ?.edit()
            ?.remove(
                KEY_LAST_WATCHED_ACTIVITY_ALL
            )
            ?.apply()
    }

    fun getSavedWatchedActivityAll(): String? {
        return prefs?.getString(
            KEY_LAST_WATCHED_ACTIVITY_ALL,
            null
        )
    }

    suspend fun getPlaybackItems(
        accessToken: String =
            requireAccessToken()
    ): List<SimklPlaybackItem> {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        return api.getPlayback(
            authorization =
                bearer(
                    accessToken
                ),

            extended =
                "full"
        )
    }

    suspend fun getWatchingShows(
        accessToken: String =
            requireAccessToken()
    ): SimklWatchingShowsResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        return api.getWatchingShows(
            authorization =
                bearer(
                    accessToken
                ),

            dateFrom =
                null,

            extended =
                "full"
        )
    }

    suspend fun getWatchedBulkImport(
        movieImdbIds: List<String> =
            emptyList(),

        movieSimklIds: List<Int> =
            emptyList(),

        showImdbIds: List<String> =
            emptyList(),

        showSimklIds: List<Int> =
            emptyList(),

        accessToken: String =
            requireAccessToken()
    ): SimklWatchedImport {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        val movieRefs =
            buildIdRefs(
                imdbIds =
                    movieImdbIds,

                simklIds =
                    movieSimklIds
            )

        val showRefs =
            buildIdRefs(
                imdbIds =
                    showImdbIds,

                simklIds =
                    showSimklIds
            )

        if (
            movieRefs.isEmpty() &&
            showRefs.isEmpty()
        ) {
            return SimklWatchedImport(
                watchedMovieImdbIds =
                    emptySet(),

                watchedMovieSimklIds =
                    emptySet(),

                watchedShowImdbIds =
                    emptySet(),

                watchedShowSimklIds =
                    emptySet(),

                watchedEpisodesByShowKey =
                    emptyMap()
            )
        }

        val requestBody =
            SimklWatchedBulkRequest(
                movies =
                    movieRefs.map {
                        SimklWatchedLookupMovie(
                            ids = it
                        )
                    },

                shows =
                    showRefs.map {
                        SimklWatchedLookupShow(
                            ids = it
                        )
                    }
            )

        val rawResponse =
            api.getWatchedBulkRaw(
                authorization =
                    bearer(
                        accessToken
                    ),

                body =
                    requestBody
            )

        val rawText =
            try {
                rawResponse
                    .body()
                    ?.string()
            } catch (e: Exception) {
                "unreadable raw body: " +
                    e.message
            }

        Log.e(
            "SIMKL_REPO",
            "getWatchedBulk RAW " +
                "code=${rawResponse.code()} " +
                "message=${rawResponse.message()} " +
                "contentType=" +
                "${rawResponse.headers()["Content-Type"]} " +
                "body=$rawText"
        )

        return SimklWatchedImport(
            watchedMovieImdbIds =
                emptySet(),

            watchedMovieSimklIds =
                emptySet(),

            watchedShowImdbIds =
                emptySet(),

            watchedShowSimklIds =
                emptySet(),

            watchedEpisodesByShowKey =
                emptyMap()
        )
    }

    private fun isShowFullyWatched(
        item: SimklWatchingShowItem
    ): Boolean {

        val status =
            item.status
                ?.trim()
                ?.lowercase()

        val watched =
            item.watchedEpisodesCount ?: 0

        val total =
            item.totalEpisodesCount ?: 0

        val notAired =
            item.notAiredEpisodesCount ?: 0

        val airedTotal =
            if (total > 0) {
                total - notAired
            } else {
                0
            }

        if (
            airedTotal > 0 &&
            watched >= airedTotal
        ) {
            return true
        }

        val hasNext =
            !item.nextToWatch
                .isNullOrBlank()

        val isFinishedStatus =
            status == "completed" ||
                status == "ended" ||
                status == "canceled"

        if (
            isFinishedStatus &&
            !hasNext
        ) {
            return true
        }

        if (
            total > 0 &&
            watched >= total
        ) {
            return true
        }

        return false
    }

    private fun isShowFullyWatched(
        item: SimklWatchingShowDetailedItem
    ): Boolean {

        val status =
            item.status
                ?.trim()
                ?.lowercase()

        val watched =
            item.watchedEpisodesCount ?: 0

        val total =
            item.totalEpisodesCount ?: 0

        val notAired =
            item.notAiredEpisodesCount ?: 0

        val airedTotal =
            if (total > 0) {
                total - notAired
            } else {
                0
            }

        if (
            airedTotal > 0 &&
            watched >= airedTotal
        ) {
            return true
        }

        val hasNext =
            !item.nextToWatch
                .isNullOrBlank()

        val isFinishedStatus =
            status == "completed" ||
                status == "ended" ||
                status == "canceled"

        if (
            isFinishedStatus &&
            !hasNext
        ) {
            return true
        }

        if (
            total > 0 &&
            watched >= total
        ) {
            return true
        }

        return false
    }

    private suspend fun getAllShowItemsCached(
        accessToken: String,
        forceRefresh: Boolean =
            false
    ): SimklAllShowsResponse? {

        val now =
            System.currentTimeMillis()

        return allShowItemsMutex.withLock {

            val cached =
                cachedAllShowItems

            if (
                !forceRefresh &&
                cached != null &&
                now - cachedAllShowItemsFetchedAt <
                    ALL_SHOW_ITEMS_TTL_MS
            ) {
                return@withLock cached
            }

            // Disk fallback so a cold start doesn't re-download the entire
            // library (hundreds of shows with full episode data) before the
            // Continue Watching rail can resolve anything.
            if (
                !forceRefresh &&
                cached == null
            ) {
                val diskCached =
                    readSimklJsonFromDisk(
                        ALL_SHOW_ITEMS_DISK_KEY
                    )

                if (
                    diskCached != null &&
                    now - diskCached.updatedAt <
                        ALL_SHOW_ITEMS_DISK_TTL_MS
                ) {
                    val parsed =
                        runCatching {
                            allShowsJsonAdapter
                                .fromJson(
                                    diskCached.json
                                )
                        }
                            .getOrNull()

                    if (parsed != null) {
                        cachedAllShowItems =
                            parsed

                        cachedAllShowItemsFetchedAt =
                            now

                        return@withLock parsed
                    }
                }
            }

            val response =
                try {
                    api.getAllShowItems(
                        authorization =
                            bearer(
                                accessToken
                            ),

                        extended =
                            "full",

                        includeAllEpisodes =
                            "original",

                        episodeWatchedAt =
                            "yes"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "SIMKL_REPO",
                        "getAllShowItems failed: " +
                            e.message,
                        e
                    )

                    return@withLock cached
                }

            if (
                !response.isSuccessful
            ) {
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
                    "getAllShowItems failed " +
                        "code=${response.code()} " +
                        "body=$errorText"
                )

                return@withLock cached
            }

            val body =
                response.body()

            if (
                body != null
            ) {
                cachedAllShowItems =
                    body

                cachedAllShowItemsFetchedAt =
                    now

                runCatching {
                    tmdbJsonCacheDao?.upsert(
                        TmdbJsonCacheEntity(
                            key =
                                ALL_SHOW_ITEMS_DISK_KEY,

                            json =
                                allShowsJsonAdapter
                                    .toJson(
                                        body
                                    ),

                            updatedAt =
                                now
                        )
                    )
                }

                Log.e(
                    "SIMKL_REPO",
                    "all-show cache refreshed: " +
                        "shows=${body.shows.size}"
                )
            }

            body ?: cached
        }
    }

    private suspend fun readSimklJsonFromDisk(
        key: String
    ): TmdbJsonCacheEntity? {
        return runCatching {
            tmdbJsonCacheDao?.getByKey(
                key
            )
        }
            .getOrNull()
    }

    suspend fun getCompletedMovieImdbIds(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val httpResponse =
            api.getCompletedMovies(
                authorization =
                    bearer(
                        accessToken
                    ),

                dateFrom =
                    null,

                extended =
                    "full"
            )

        Log.e(
            "SIMKL_REPO",
            "getCompletedMovies raw: " +
                "code=${httpResponse.code()}, " +
                "message=${httpResponse.message()}"
        )

        if (
            !httpResponse.isSuccessful
        ) {
            val errorText =
                try {
                    httpResponse
                        .errorBody()
                        ?.string()
                } catch (e: Exception) {
                    "unreadable: " +
                        e.message
                }

            Log.e(
                "SIMKL_REPO",
                "getCompletedMovies failed body: " +
                    errorText
            )

            return emptySet()
        }

        val body =
            httpResponse.body()

        if (
            body == null
        ) {
            Log.e(
                "SIMKL_REPO",
                "getCompletedMovies succeeded " +
                    "but body was null"
            )

            return emptySet()
        }

        Log.e(
            "SIMKL_REPO",
            "getCompletedMovies parsed ok: " +
                "movies=${body.movies.size}"
        )

        return body.movies
            .mapNotNull {
                it.movie
                    ?.ids
                    ?.imdb
                    ?.takeIf { id ->
                        id.isNotBlank()
                    }
            }
            .toSet()
    }

    suspend fun isShowWatchedByImdb(
        imdbId: String,
        tmdbId: Int? =
            null,

        accessToken: String =
            requireAccessToken()
    ): Boolean {

        if (
            imdbId.isBlank()
        ) {
            return false
        }

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return false

        val match =
            body.shows.firstOrNull { item ->
                item.show
                    ?.ids
                    ?.imdb == imdbId ||
                    (
                        tmdbId != null &&
                        item.show
                            ?.ids
                            ?.tmdb == tmdbId
                        )
            }
                ?: return false

        return isShowFullyWatched(
            match
        )
    }

    suspend fun getWatchedEpisodesForShowByImdb(
        imdbId: String,
        tmdbId: Int? =
            null,

        accessToken: String =
            requireAccessToken()
    ): Set<Pair<Int, Int>> {

        if (
            imdbId.isBlank()
        ) {
            return emptySet()
        }

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return emptySet()

        val match =
            body.shows.firstOrNull { item ->
                item.show
                    ?.ids
                    ?.imdb == imdbId ||
                    (
                        tmdbId != null &&
                        item.show
                            ?.ids
                            ?.tmdb == tmdbId
                        )
            }
                ?: return emptySet()

        return match.seasons
            ?.flatMap { season ->

                val seasonNumber =
                    season.number

                season.episodes.mapNotNull { episode ->

                    val episodeNumber =
                        episode.number
                            ?: episode.episode

                    if (
                        seasonNumber != null &&
                        episodeNumber != null &&
                        !episode.watchedAt
                            .isNullOrBlank()
                    ) {
                        seasonNumber to
                            episodeNumber
                    } else {
                        null
                    }
                }
            }
            ?.toSet()
            .orEmpty()
    }

    suspend fun getCompletedShowImdbIds(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return emptySet()

        return body.shows
            .asSequence()
            .filter { item ->
                isShowFullyWatched(
                    item
                )
            }
            .mapNotNull { item ->
                item.show
                    ?.ids
                    ?.imdb
                    ?.takeIf {
                        it.isNotBlank()
                    }
            }
            .toSet()
    }

    suspend fun refreshAllShowItems(
        accessToken: String =
            requireAccessToken()
    ) {
        getAllShowItemsCached(
            accessToken =
                accessToken,

            forceRefresh =
                true
        )
    }

    suspend fun getCompletedMovieKeys(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val now =
            System.currentTimeMillis()

        return completedMovieKeysMutex.withLock {

            val cached =
                cachedCompletedMovieKeys

            if (
                cached != null &&
                now - cachedCompletedMovieKeysFetchedAt <
                    COMPLETED_MOVIES_TTL_MS
            ) {
                return@withLock cached
            }

            // Disk fallback so a cold start doesn't re-fetch the completed
            // movie lists before the watched markers can resolve.
            if (
                cached == null
            ) {
                val diskCached =
                    readSimklJsonFromDisk(
                        COMPLETED_MOVIES_DISK_KEY
                    )

                if (
                    diskCached != null &&
                    now - diskCached.updatedAt <
                        COMPLETED_MOVIES_DISK_TTL_MS
                ) {
                    val parsed =
                        runCatching {
                            completedMovieKeysJsonAdapter
                                .fromJson(
                                    diskCached.json
                                )
                                ?.toSet()
                        }
                            .getOrNull()

                    if (parsed != null) {
                        cachedCompletedMovieKeys =
                            parsed

                        cachedCompletedMovieKeysFetchedAt =
                            now

                        return@withLock parsed
                    }
                }
            }

            val completedResponse =
            api.getCompletedMovies(
                authorization =
                    bearer(
                        accessToken
                    ),

                dateFrom =
                    null,

                extended =
                    "full"
            )

        val allItemsResponse =
            api.getAllMovieItems(
                authorization =
                    bearer(
                        accessToken
                    ),

                dateFrom =
                    null,

                extended =
                    "full"
            )

        fun extractKeys(
            body: SimklCompletedMoviesResponse?
        ): Set<String> {

            if (
                body == null
            ) {
                return emptySet()
            }

            return body.movies
                .flatMap { item ->

                    val ids =
                        item.movie
                            ?.ids

                    buildList {

                        ids
                            ?.imdb
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                add(
                                    "imdb:$it"
                                )
                            }

                        ids
                            ?.tmdb
                            ?.let {
                                add(
                                    "tmdb:$it"
                                )
                            }

                        ids
                            ?.simkl
                            ?.let {
                                add(
                                    "simkl:$it"
                                )
                            }
                    }
                }
                .toSet()
        }

        val completedKeys =
            if (
                completedResponse.isSuccessful
            ) {
                extractKeys(
                    completedResponse.body()
                )
            } else {
                emptySet()
            }

        val allMovieKeys =
            if (
                allItemsResponse.isSuccessful
            ) {
                extractKeys(
                    allItemsResponse.body()
                )
            } else {
                emptySet()
            }

            val merged =
                completedKeys +
                    allMovieKeys

            cachedCompletedMovieKeys =
                merged

            cachedCompletedMovieKeysFetchedAt =
                now

            runCatching {
                tmdbJsonCacheDao?.upsert(
                    TmdbJsonCacheEntity(
                        key =
                            COMPLETED_MOVIES_DISK_KEY,

                        json =
                            completedMovieKeysJsonAdapter
                                .toJson(
                                    merged.toList()
                                ),

                        updatedAt =
                            now
                    )
                )
            }

            merged
        }
    }

    suspend fun getContinueWatching(
        accessToken: String =
            requireAccessToken(),

        forceRefresh: Boolean =
            false
    ): List<SimklContinueWatchingItem> {

        if (
            !forceRefresh &&
            cachedContinueWatching != null
        ) {
            return cachedContinueWatching.orEmpty()
        }

        // Disk fallback so the rail renders immediately on a cold start
        // instead of waiting for playback + watching-shows network calls.
        if (
            !forceRefresh &&
            cachedContinueWatching == null
        ) {
            val diskCached =
                readSimklJsonFromDisk(
                    CONTINUE_WATCHING_DISK_KEY
                )

            if (
                diskCached != null &&
                System.currentTimeMillis() -
                    diskCached.updatedAt <
                    CONTINUE_WATCHING_DISK_TTL_MS
            ) {
                val parsed =
                    runCatching {
                        continueWatchingJsonAdapter
                            .fromJson(
                                diskCached.json
                            )
                    }
                        .getOrNull()

                if (parsed != null) {
                    cachedContinueWatching =
                        parsed

                    return parsed
                }
            }
        }

        try {
            val playbackItems =
                getPlaybackItems(
                    accessToken
                )

            val watchingShows =
                getWatchingShows(
                    accessToken
                ).shows

            val watchingBySimklId:
                Map<String, SimklWatchingShowItem> =
                watchingShows
                    .mapNotNull { watchedShow ->
                        watchedShow.show
                            ?.ids
                            ?.simkl
                            ?.toString()
                            ?.let {
                                it to watchedShow
                            }
                    }
                    .toMap()

            fun isTrulyCompleted(
                simklId: String
            ): Boolean {

                val watchingEntry =
                    watchingBySimklId[
                        simklId
                    ]
                        ?: return false

                return isShowFullyWatched(
                    watchingEntry
                )
            }

            val playbackMapped =
                playbackItems.mapNotNull { item ->

                    when {

                        item.movie != null -> {

                            val movie =
                                item.movie

                            val simklId =
                                movie.ids
                                    ?.simkl
                                    ?.toString()
                                    ?: item.id
                                        ?.toString()
                                    ?: return@mapNotNull null

                            val imdbId =
                                movie.ids
                                    ?.imdb
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }

                            SimklContinueWatchingItem(
                                id =
                                    "movie-$simklId",

                                playbackId =
                                    item.id,

                                imdbId =
                                    imdbId,

                                tmdbId =
                                    movie.ids
                                        ?.tmdb,

                                simklId =
                                    movie.ids
                                        ?.simkl
                                        ?: simklId
                                            .toIntOrNull(),

                                title =
                                    movie.title
                                        ?: "Untitled movie",

                                year =
                                    movie.year,

                                posterUrl =
                                    normalizePosterUrl(
                                        movie.poster
                                    ),

                                lastWatchedAt =
                                    item.pausedAt,

                                progress =
                                    item.progress,

                                upNextText =
                                    "Resume movie",

                                mediaType =
                                    "movie",

                                source =
                                    "playback",

                                season =
                                    null,

                                episode =
                                    null
                            )
                        }

                        item.show != null -> {

                            val show =
                                item.show

                            val simklId =
                                show.ids
                                    ?.simkl
                                    ?.toString()
                                    ?: item.id
                                        ?.toString()
                                    ?: return@mapNotNull null

                            if (
                                isTrulyCompleted(
                                    simklId
                                ) &&
                                (
                                    item.progress
                                        ?: 0f
                                    ) >= 95f
                            ) {
                                return@mapNotNull null
                            }

                            val imdbId =
                                show.ids
                                    ?.imdb
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }

                            SimklContinueWatchingItem(
                                id =
                                    "show-$simklId",

                                playbackId =
                                    item.id,

                                imdbId =
                                    imdbId,

                                tmdbId =
                                    show.ids
                                        ?.tmdb,

                                simklId =
                                    show.ids
                                        ?.simkl
                                        ?: simklId
                                            .toIntOrNull(),

                                title =
                                    show.title
                                        ?: "Untitled show",

                                year =
                                    show.year,

                                posterUrl =
                                    normalizePosterUrl(
                                        show.poster
                                    ),

                                lastWatchedAt =
                                    item.pausedAt,

                                progress =
                                    item.progress,

                                upNextText =
                                    buildPlaybackUpNextText(
                                        item.episode
                                    ),

                                mediaType =
                                    "series",

                                source =
                                    "playback",

                                season =
                                    item.episode
                                        ?.season,

                                episode =
                                    item.episode
                                        ?.episode
                            )
                        }

                        else ->
                            null
                    }
                }

            val playbackIds =
                playbackMapped
                    .map {
                        it.id
                    }
                    .toSet()

            val watchingMapped =
                watchingShows
                    .asSequence()
                    .filter { item ->

                        val status =
                            item.status
                                ?.trim()
                                ?.lowercase()

                        if (
                            status == "dropped" ||
                            status == "completed" ||
                            status == "ended" ||
                            status == "canceled"
                        ) {
                            false
                        } else {
                            !isShowFullyWatched(
                                item
                            )
                        }
                    }
                    .mapNotNull { item ->

                        val show =
                            item.show
                                ?: return@mapNotNull null

                        val simklId =
                            show.ids
                                ?.simkl
                                ?.toString()
                                ?: return@mapNotNull null

                        val mergedId =
                            "show-$simklId"

                        if (
                            mergedId in playbackIds
                        ) {
                            return@mapNotNull null
                        }

                        val imdbId =
                            show.ids
                                ?.imdb
                                ?.takeIf {
                                    it.isNotBlank()
                                }

                        val parsedNext =
                            parseNextTarget(
                                item.nextToWatch
                            )
                                ?: parseNextTarget(
                                    item.lastWatched
                                )

                        val nextSeason =
                            parsedNext?.first

                        val nextEpisode =
                            parsedNext?.second

                        SimklContinueWatchingItem(
                            id =
                                mergedId,

                            imdbId =
                                imdbId,

                            tmdbId =
                                show.ids
                                    ?.tmdb,

                            simklId =
                                show.ids
                                    ?.simkl,

                            title =
                                show.title
                                    ?: "Untitled show",

                            year =
                                show.year,

                            posterUrl =
                                normalizePosterUrl(
                                    show.poster
                                ),

                            lastWatchedAt =
                                item.lastWatchedAt
                                    ?: item.addedToWatchlistAt,

                            progress =
                                null,

                            upNextText =
                                buildWatchingUpNextText(
                                    nextToWatch =
                                        item.nextToWatch,

                                    lastWatched =
                                        item.lastWatched,

                                    status =
                                        item.status
                                ),

                            mediaType =
                                "series",

                            source =
                                "watching",

                            season =
                                nextSeason,

                            episode =
                                nextEpisode
                        )
                    }
                    .toList()

            val result =
                (
                    playbackMapped +
                        watchingMapped
                    )
                    .sortedWith(
                        compareByDescending<
                            SimklContinueWatchingItem
                            > {
                                scoreContinueWatchingItem(
                                    it
                                )
                            }
                            .thenByDescending {
                                sortableTimestamp(
                                    it.lastWatchedAt
                                )
                            }
                            .thenBy {
                                it.title
                                    .lowercase()
                            }
                    )

            cachedContinueWatching =
                result

            runCatching {
                tmdbJsonCacheDao?.upsert(
                    TmdbJsonCacheEntity(
                        key =
                            CONTINUE_WATCHING_DISK_KEY,

                        json =
                            continueWatchingJsonAdapter
                                .toJson(
                                    result
                                ),

                        updatedAt =
                            System.currentTimeMillis()
                    )
                )
            }

            return result

        } catch (e: Exception) {

            Log.e(
                "SIMKL_REPO",
                "getContinueWatching failed: " +
                    e.message,
                e
            )

            return cachedContinueWatching
                .orEmpty()
        }
    }

    data class WatchedActivityRefreshResult(
        val attempted: Boolean,
        val changed: Boolean,
        val latestActivity: String? =
            null,

        val errorMessage: String? =
            null
    )

    suspend fun refreshWatchedActivity():
        WatchedActivityRefreshResult {

        if (
            !isConfigured()
        ) {
            return WatchedActivityRefreshResult(
                attempted =
                    false,

                changed =
                    false,

                errorMessage =
                    "Simkl is not configured"
            )
        }

        if (
            !hasToken()
        ) {
            return WatchedActivityRefreshResult(
                attempted =
                    false,

                changed =
                    false,

                errorMessage =
                    "Simkl is not authenticated"
            )
        }

        return try {

            val activities =
                getActivities()

            val latestActivity =
                activities.all
                    ?.trim()
                    .orEmpty()

            val savedActivity =
                getSavedWatchedActivityAll()
                    ?.trim()
                    .orEmpty()

            if (
                latestActivity.isBlank()
            ) {
                return WatchedActivityRefreshResult(
                    attempted =
                        true,

                    changed =
                        false,

                    latestActivity =
                        null
                )
            }

            val changed =
                latestActivity !=
                    savedActivity

            WatchedActivityRefreshResult(
                attempted =
                    true,

                changed =
                    changed,

                latestActivity =
                    latestActivity
            )

        } catch (e: Exception) {

            WatchedActivityRefreshResult(
                attempted =
                    true,

                changed =
                    false,

                errorMessage =
                    e.message
            )
        }
    }

    private fun buildIdRefs(
        imdbIds: List<String>,
        simklIds: List<Int>
    ): List<SimklPlaybackIdsRef> {

        val refs =
            mutableListOf<
                SimklPlaybackIdsRef
                >()

        imdbIds
            .asSequence()
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .forEach { imdb ->
                refs +=
                    SimklPlaybackIdsRef(
                        imdb =
                            imdb
                    )
            }

        simklIds
            .asSequence()
            .distinct()
            .forEach { simkl ->
                refs +=
                    SimklPlaybackIdsRef(
                        simkl =
                            simkl
                    )
            }

        return refs
    }

    private fun scoreContinueWatchingItem(
        item: SimklContinueWatchingItem
    ): Int {

        var score =
            0

        if (
            item.source ==
                "playback"
        ) {
            score += 500
        }

        if (
            item.mediaType ==
                "movie" &&
            item.source ==
                "playback"
        ) {
            score += 50
        }

        if (
            item.source ==
                "watching" &&
            item.mediaType ==
                "series"
        ) {
            if (
                item.season != null &&
                item.episode != null
            ) {
                score += 350
            } else if (
                item.season != null
            ) {
                score += 300
            } else {
                score += 150
            }
        }

        val text =
            item.upNextText
                .orEmpty()
                .lowercase()

        if (
            text.contains(
                "resume"
            )
        ) {
            score += 100
        }

        return score
    }

    private fun buildPlaybackUpNextText(
        episode: SimklPlaybackEpisode?
    ): String {

        if (
            episode == null
        ) {
            return "Resume show"
        }

        val code =
            buildEpisodeCode(
                season =
                    episode.season,

                episode =
                    episode.episode
            )

        return buildString {

            append(
                "Up next"
            )

            if (
                code != null
            ) {
                append(
                    ": "
                )

                append(
                    code
                )
            }

            episode.title
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { title ->
                    append(
                        " • "
                    )

                    append(
                        title
                    )
                }
        }
    }

    private fun buildWatchingUpNextText(
        nextToWatch: String?,
        lastWatched: String?,
        status: String?
    ): String {

        val next =
            nextToWatch
                ?.trim()
                .orEmpty()

        if (
            next.isNotBlank()
        ) {
            return "Up next: $next"
        }

        return "Up next"
    }

    private fun parseNextTarget(
        value: String?
    ): Pair<Int, Int?>? {

        val trimmed =
            value
                ?.trim()
                .orEmpty()

        if (
            trimmed.isBlank()
        ) {
            return null
        }

        val match =
            Regex(
                """s(\d+)\s*(?:e(\d+))?""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    trimmed
                )
                ?: return null

        val season =
            match.groupValues[1]
                .toIntOrNull()
                ?: return null

        val episode =
            match.groupValues
                .getOrNull(2)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.toIntOrNull()

        return season to
            episode
    }

    private fun buildEpisodeCode(
        season: Int?,
        episode: Int?
    ): String? {

        if (
            season == null &&
            episode == null
        ) {
            return null
        }

        return buildString {

            season?.let {
                append(
                    "S$it"
                )
            }

            episode?.let {
                append(
                    "E$it"
                )
            }

        }.ifBlank {
            null
        }
    }

    private fun sortableTimestamp(
        value: String?
    ): String {
        return value
            ?.trim()
            .orEmpty()
    }

    private fun normalizePosterUrl(
        raw: String?
    ): String? {

        if (
            raw.isNullOrBlank()
        ) {
            return null
        }

        if (
            raw.startsWith(
                "http://"
            ) ||
            raw.startsWith(
                "https://"
            )
        ) {
            return raw
        }

        return "https://simkl.in/posters/" +
            "${raw}_m.jpg"
    }

    private fun requireAccessToken(): String {
        return getSavedAccessToken()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: error(
                "Simkl access token is missing"
            )
    }

    private fun saveAccessToken(
        token: String
    ) {
        prefs
            ?.edit()
            ?.putString(
                KEY_ACCESS_TOKEN,
                token
            )
            ?.apply()
    }

    private fun saveWatchedActivityAll(
        value: String
    ) {
        prefs
            ?.edit()
            ?.putString(
                KEY_LAST_WATCHED_ACTIVITY_ALL,
                value
            )
            ?.apply()
    }

    private fun bearer(
        token: String
    ): String =
        "Bearer $token"

    companion object {

        @Volatile
        private var INSTANCE:
            SimklRepository? = null

        fun getInstance(
            context: Context
        ): SimklRepository {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE
                        ?: SimklRepository(
                            context.applicationContext
                        )
                            .also {
                                INSTANCE = it
                            }
                }
        }

        private const val PREFS_NAME =
            "simkl_auth"

        private const val KEY_ACCESS_TOKEN =
            "access_token"

        private const val KEY_LAST_WATCHED_ACTIVITY_ALL =
            "last_watched_activity_all"

        private const val ALL_SHOW_ITEMS_TTL_MS =
            15L * 60L * 1000L

        private const val ALL_SHOW_ITEMS_DISK_TTL_MS =
            12L * 60L * 60L * 1000L

        private const val CONTINUE_WATCHING_DISK_TTL_MS =
            6L * 60L * 60L * 1000L

        private const val ALL_SHOW_ITEMS_DISK_KEY =
            "simkl:all_show_items"

        private const val CONTINUE_WATCHING_DISK_KEY =
            "simkl:continue_watching"

        private const val COMPLETED_MOVIES_TTL_MS =
            15L * 60L * 1000L

        private const val COMPLETED_MOVIES_DISK_TTL_MS =
            12L * 60L * 60L * 1000L

        private const val COMPLETED_MOVIES_DISK_KEY =
            "simkl:completed_movies"

        private var cachedContinueWatching:
            List<SimklContinueWatchingItem>? =
            null
    }
}
