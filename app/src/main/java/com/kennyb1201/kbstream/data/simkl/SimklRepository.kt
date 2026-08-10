package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SimklRepository(
    context: Context? = null
) {

    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val clientSecret = BuildConfig.SIMKL_CLIENT_SECRET

    private val prefs = context?.applicationContext?.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            SimklQueryInterceptor(
                clientId = clientId,
                appName = SimklConfig.APP_NAME,
                appVersion = SimklConfig.APP_VERSION
            )
        )
        .build()

    private val api: SimklApiService = Retrofit.Builder()
        .baseUrl(SimklConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SimklApiService::class.java)

    fun isConfigured(): Boolean {
        return clientId.isNotBlank() && clientSecret.isNotBlank()
    }

    fun hasToken(): Boolean {
        val token = getSavedAccessToken()
        Log.e(
            "SIMKL_REPO",
            "hasToken check: prefsIsNull=${prefs == null}, tokenIsNullOrBlank=${token.isNullOrBlank()}, instance=${System.identityHashCode(this)}"
        )
        return !token.isNullOrBlank()
    }

    fun getSavedAccessToken(): String? {
        return prefs?.getString(KEY_ACCESS_TOKEN, null)
    }

    fun clearAuth() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_LAST_WATCHED_ACTIVITY_ALL)
            ?.apply()
    }

    suspend fun createPinCode(): SimklPinCodeResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.createPinCode()
    }

    suspend fun checkPin(userCode: String): SimklTokenResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        val response = api.checkPin(userCode)
        response.accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { saveAccessToken(it) }

        return response
    }

    suspend fun getActivities(
        accessToken: String = requireAccessToken()
    ): SimklActivitiesResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.getActivities(authorization = bearer(accessToken))
    }

    suspend fun hasWatchedActivityChanged(
        accessToken: String = requireAccessToken()
    ): Boolean {
        if (!isConfigured() || !hasToken()) return false

        val activities = runCatching { getActivities(accessToken) }
            .onFailure {
                Log.e("SIMKL_REPO", "getActivities failed: ${it.message}", it)
            }
            .getOrNull()
            ?: return false

        val latest = activities.all?.trim().orEmpty()
        if (latest.isBlank()) {
            Log.e("SIMKL_REPO", "activities.all was blank; treating as unchanged")
            return false
        }

        val saved = getSavedWatchedActivityAll().orEmpty()
        val changed = saved != latest

        Log.e(
            "SIMKL_REPO",
            "hasWatchedActivityChanged changed=$changed saved=$saved latest=$latest"
        )

        return changed
    }

    suspend fun markWatchedActivitySynced(
        accessToken: String = requireAccessToken()
    ) {
        if (!isConfigured() || !hasToken()) return

        val activities = runCatching { getActivities(accessToken) }
            .onFailure {
                Log.e("SIMKL_REPO", "markWatchedActivitySynced getActivities failed: ${it.message}", it)
            }
            .getOrNull()
            ?: return

        val latest = activities.all?.trim().orEmpty()
        if (latest.isBlank()) return

        saveWatchedActivityAll(latest)

        Log.e("SIMKL_REPO", "markWatchedActivitySynced saved activity all=$latest")
    }

    fun forceClearWatchedActivitySync() {
        prefs?.edit()?.remove(KEY_LAST_WATCHED_ACTIVITY_ALL)?.apply()
    }

    fun getSavedWatchedActivityAll(): String? {
        return prefs?.getString(KEY_LAST_WATCHED_ACTIVITY_ALL, null)
    }

    suspend fun getPlaybackItems(
        accessToken: String = requireAccessToken()
    ): List<SimklPlaybackItem> {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.getPlayback(
            authorization = bearer(accessToken),
            extended = "full"
        )
    }

    suspend fun getWatchingShows(
        accessToken: String = requireAccessToken()
    ): SimklWatchingShowsResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.getWatchingShows(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )
    }

    suspend fun getWatchedBulkImport(
        movieImdbIds: List<String> = emptyList(),
        movieSimklIds: List<Int> = emptyList(),
        showImdbIds: List<String> = emptyList(),
        showSimklIds: List<Int> = emptyList(),
        accessToken: String = requireAccessToken()
    ): SimklWatchedImport {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        val movieRefs = buildIdRefs(
            imdbIds = movieImdbIds,
            simklIds = movieSimklIds
        )
        val showRefs = buildIdRefs(
            imdbIds = showImdbIds,
            simklIds = showSimklIds
        )

        if (movieRefs.isEmpty() && showRefs.isEmpty()) {
            return SimklWatchedImport(
                watchedMovieImdbIds = emptySet(),
                watchedMovieSimklIds = emptySet(),
                watchedShowImdbIds = emptySet(),
                watchedShowSimklIds = emptySet(),
                watchedEpisodesByShowKey = emptyMap()
            )
        }

        val requestBody = SimklWatchedBulkRequest(
            movies = movieRefs.map { SimklWatchedLookupMovie(ids = it) },
            shows = showRefs.map { SimklWatchedLookupShow(ids = it) }
        )

        val rawResponse = api.getWatchedBulkRaw(
            authorization = bearer(accessToken),
            body = requestBody
        )

        val rawText = try {
            rawResponse.body()?.string()
        } catch (e: Exception) {
            "unreadable raw body: ${e.message}"
        }

        Log.e(
            "SIMKL_REPO",
            "getWatchedBulk RAW code=${rawResponse.code()} message=${rawResponse.message()} contentType=${rawResponse.headers()["Content-Type"]} body=$rawText"
        )

        return SimklWatchedImport(
            watchedMovieImdbIds = emptySet(),
            watchedMovieSimklIds = emptySet(),
            watchedShowImdbIds = emptySet(),
            watchedShowSimklIds = emptySet(),
            watchedEpisodesByShowKey = emptyMap()
        )
    }

    private fun isShowFullyWatched(item: SimklWatchingShowItem): Boolean {
        val status = item.status?.trim()?.lowercase()
        val watched = item.watchedEpisodesCount
        val total = item.totalEpisodesCount
        val notAired = item.notAiredEpisodesCount ?: 0
        val airedTotal = total?.let { it - notAired }
        val caughtUpOnAired =
            watched != null && airedTotal != null && airedTotal > 0 && watched >= airedTotal

        if (caughtUpOnAired) return true

        val hasNext = !item.nextToWatch.isNullOrBlank()
        return status == "completed" && !hasNext
    }

    private fun isShowFullyWatched(item: SimklWatchingShowDetailedItem): Boolean {
        val status = item.status?.trim()?.lowercase()
        val watched = item.watchedEpisodesCount
        val total = item.totalEpisodesCount
        val notAired = item.notAiredEpisodesCount ?: 0
        val airedTotal = total?.let { it - notAired }
        val caughtUpOnAired =
            watched != null && airedTotal != null && airedTotal > 0 && watched >= airedTotal

        if (caughtUpOnAired) return true

        val hasNext = !item.nextToWatch.isNullOrBlank()
        return status == "completed" && !hasNext
    }

    suspend fun getCompletedMovieImdbIds(
        accessToken: String = requireAccessToken()
    ): Set<String> {
        val httpResponse = api.getCompletedMovies(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )

        Log.e(
            "SIMKL_REPO",
            "getCompletedMovies raw: code=${httpResponse.code()}, message=${httpResponse.message()}"
        )

        if (!httpResponse.isSuccessful) {
            val errorText = try {
                httpResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getCompletedMovies failed body: $errorText")
            return emptySet()
        }

        val body = httpResponse.body()

        if (body == null) {
            Log.e("SIMKL_REPO", "getCompletedMovies succeeded but body was null")
            return emptySet()
        }

        Log.e("SIMKL_REPO", "getCompletedMovies parsed ok: movies=${body.movies.size}")

        return body.movies
            .mapNotNull { it.movie?.ids?.imdb?.takeIf { id -> id.isNotBlank() } }
            .toSet()
    }

    suspend fun isShowWatchedByImdb(
        imdbId: String,
        tmdbId: Int? = null,
        accessToken: String = requireAccessToken()
    ): Boolean {
        if (imdbId.isBlank()) return false

        val response = try {
            api.getAllShowItems(
                authorization = bearer(accessToken),
                extended = "full",
                includeAllEpisodes = "original",
                episodeWatchedAt = "yes"
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getAllShowItems failed for imdb=$imdbId: ${e.message}", e)
            return false
        }

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getAllShowItems failed body: $errorText")
            return false
        }

        val body = response.body()
        if (body == null) {
            Log.e("SIMKL_REPO", "isShowWatchedByImdb body was null for imdb=$imdbId")
            return false
        }

        val match = body.shows.firstOrNull { item ->
            item.show?.ids?.imdb == imdbId ||
                (tmdbId != null && item.show?.ids?.tmdb == tmdbId)
        }

        if (match == null) {
            Log.e("SIMKL_REPO", "isShowWatchedByImdb no match imdb=$imdbId tmdbId=$tmdbId")
            return false
        }

        val isWatched = isShowFullyWatched(match)

        Log.e(
            "SIMKL_REPO",
            "isShowWatchedByImdb imdb=$imdbId tmdbId=$tmdbId watched=$isWatched status=${match.status} watchedEpisodes=${match.watchedEpisodesCount} totalEpisodes=${match.totalEpisodesCount} notAired=${match.notAiredEpisodesCount} nextToWatch=${match.nextToWatch}"
        )

        return isWatched
    }

    suspend fun getWatchedEpisodesForShowByImdb(
        imdbId: String,
        tmdbId: Int? = null,
        accessToken: String = requireAccessToken()
    ): Set<Pair<Int, Int>> {

        Log.e(
            "SIMKL_REPO",
            "show lookup imdb=$imdbId accessTokenLength=${accessToken.length} savedTokenLength=${getSavedAccessToken()?.length ?: 0}"
        )

        if (imdbId.isBlank()) return emptySet()

        val bulkDebug = getWatchedBulkImport(
            showImdbIds = listOf(imdbId),
            accessToken = accessToken
        )

        Log.e(
            "SIMKL_REPO",
            "bulk debug imdb=$imdbId watchedShowImdbIds=${bulkDebug.watchedShowImdbIds} watchedShowSimklIds=${bulkDebug.watchedShowSimklIds} watchedEpisodesKeys=${bulkDebug.watchedEpisodesByShowKey.keys} watchedEpisodes=${bulkDebug.watchedEpisodesByShowKey["imdb:$imdbId"]}"
        )

        val response = try {
            api.getAllShowItems(
                authorization = bearer(accessToken),
                extended = "full",
                includeAllEpisodes = "original",
                episodeWatchedAt = "yes"
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getAllShowItems failed for imdb=$imdbId: ${e.message}", e)
            return emptySet()
        }

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getAllShowItems failed body: $errorText")
            return emptySet()
        }

        val body = response.body()
        if (body == null) {
            Log.e("SIMKL_REPO", "getAllShowItems body was null for imdb=$imdbId")
            return emptySet()
        }

        val tedLassoLike = body.shows
            .filter { item ->
                val title = item.show?.title.orEmpty()
                title.contains("Ted Lasso", ignoreCase = true) ||
                    title.contains("The Bear", ignoreCase = true)
            }
            .take(10)
            .map { item ->
                "${item.show?.title}|imdb=${item.show?.ids?.imdb}|tmdb=${item.show?.ids?.tmdb}|simkl=${item.show?.ids?.simkl}|seasons=${item.seasons.size}"
            }

        Log.e(
            "SIMKL_REPO",
            "getAllShowItems totalShows=${body.shows.size} tedLassoLike=$tedLassoLike"
        )

        val match = body.shows.firstOrNull { item ->
            item.show?.ids?.imdb == imdbId ||
                (tmdbId != null && item.show?.ids?.tmdb == tmdbId)
        }

        Log.e(
            "SIMKL_REPO",
            "getAllShowItems lookup imdb=$imdbId tmdbId=$tmdbId found=${match != null} matchTitle=${match?.show?.title} matchImdb=${match?.show?.ids?.imdb} matchTmdb=${match?.show?.ids?.tmdb} matchSimkl=${match?.show?.ids?.simkl} seasons=${match?.seasons?.size ?: 0}"
        )

        val pairs = match?.seasons
            ?.flatMap { season ->
                val seasonNumber = season.number
                season.episodes.mapNotNull { ep ->
                    val episodeNumber = ep.number ?: ep.episode
                    if (seasonNumber != null && episodeNumber != null && !ep.watchedAt.isNullOrBlank()) {
                        seasonNumber to episodeNumber
                    } else {
                        null
                    }
                }
            }
            ?.toSet()
            .orEmpty()

        Log.e(
            "SIMKL_REPO",
            "getAllShowItems episodes imdb=$imdbId count=${pairs.size} sample=${pairs.take(30)}"
        )

        return pairs
    }

    suspend fun getCompletedMovieKeys(
        accessToken: String = requireAccessToken()
    ): Set<String> {
        val completedResponse = api.getCompletedMovies(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )

        val allItemsResponse = api.getAllMovieItems(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )

        fun extractKeys(body: SimklCompletedMoviesResponse?): Set<String> {
            if (body == null) return emptySet()

            body.movies.take(50).forEach { item ->
                Log.e(
                    "SIMKL_REPO",
                    "movie item title=${item.movie?.title} imdb=${item.movie?.ids?.imdb} tmdb=${item.movie?.ids?.tmdb} simkl=${item.movie?.ids?.simkl}"
                )
            }

            return body.movies.flatMap { item ->
                val ids = item.movie?.ids
                buildList {
                    ids?.imdb?.takeIf { it.isNotBlank() }?.let { add("imdb:$it") }
                    ids?.tmdb?.let { add("tmdb:$it") }
                    ids?.simkl?.let { add("simkl:$it") }
                }
            }.toSet()
        }

        val completedKeys = if (completedResponse.isSuccessful) {
            extractKeys(completedResponse.body())
        } else {
            val errorText = try {
                completedResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getCompletedMovies failed body: $errorText")
            emptySet()
        }

        val allMovieKeys = if (allItemsResponse.isSuccessful) {
            extractKeys(allItemsResponse.body())
        } else {
            val errorText = try {
                allItemsResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getAllMovieItems failed body: $errorText")
            emptySet()
        }

        val merged = completedKeys + allMovieKeys

        Log.e(
            "SIMKL_REPO",
            "movie key sync completed=${completedKeys.size} allItems=${allMovieKeys.size} merged=${merged.size}"
        )
        Log.e(
            "SIMKL_REPO",
            "movie key sample=${merged.take(80)}"
        )

        return merged
    }

    suspend fun getContinueWatching(
        accessToken: String = requireAccessToken()
    ): List<SimklContinueWatchingItem> {
        val playbackItems = getPlaybackItems(accessToken)
        val watchingShows = getWatchingShows(accessToken).shows

        val watchingBySimklId: Map<String, SimklWatchingShowItem> = watchingShows
            .mapNotNull { w -> w.show?.ids?.simkl?.toString()?.let { it to w } }
            .toMap()

        fun isTrulyCompleted(simklId: String): Boolean {
            val watchingEntry = watchingBySimklId[simklId] ?: return false
            return isShowFullyWatched(watchingEntry)
        }

        val playbackMapped = playbackItems.mapNotNull { item ->
            when {
                item.movie != null -> {
                    val movie = item.movie
                    val simklId = movie.ids?.simkl?.toString()
                        ?: item.id?.toString()
                        ?: return@mapNotNull null
                    val imdbId = movie.ids?.imdb?.takeIf { it.isNotBlank() }

                    if (imdbId == null) {
                        Log.e(
                            "SIMKL_REPO",
                            "Movie missing imdb id, simklId=$simklId, title=${movie.title}"
                        )
                    }

                    Log.e(
                        "SIMKL_REPO",
                        "Movie poster raw, title=${movie.title}, poster=${movie.poster}"
                    )

                    SimklContinueWatchingItem(
    id = "movie-$simklId",
    imdbId = imdbId,
    tmdbId = movie.ids?.tmdb,
        simklId = movie.ids?.simkl ?: simklId.toIntOrNull(),
    title = movie.title ?: "Untitled movie",
    year = movie.year,
    posterUrl = normalizePosterUrl(movie.poster),
    lastWatchedAt = item.pausedAt,
    progress = item.progress,
    upNextText = "Resume movie",
    mediaType = "movie",
    source = "playback",
    season = null,
    episode = null
)
                }

                item.show != null -> {
                    val show = item.show
                    val simklId = show.ids?.simkl?.toString()
                        ?: item.id?.toString()
                        ?: return@mapNotNull null

if (isTrulyCompleted(simklId) && (item.progress ?: 0f) >= 95f) return@mapNotNull null
                    }

                    val imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() }

                    if (imdbId == null) {
                        Log.e(
                            "SIMKL_REPO",
                            "Show missing imdb id, simklId=$simklId, title=${show.title}"
                        )
                    }

                    Log.e(
                        "SIMKL_REPO",
                        "Show poster raw, title=${show.title}, poster=${show.poster}"
                    )

                    SimklContinueWatchingItem(
    id = "show-$simklId",
    imdbId = imdbId,
    tmdbId = show.ids?.tmdb,
        simklId = show.ids?.simkl ?: simklId.toIntOrNull(),
    title = show.title ?: "Untitled show",
    year = show.year,
    posterUrl = normalizePosterUrl(show.poster),
    lastWatchedAt = item.pausedAt,
    progress = item.progress,
    upNextText = buildPlaybackUpNextText(item.episode),
    mediaType = "series",
    source = "playback",
    season = item.episode?.season,
    episode = item.episode?.episode
)
                }

                else -> null
            }
        }

        val playbackIds = playbackMapped.map { it.id }.toSet()

        val watchingMapped = watchingShows
            .asSequence()
            .filter { item ->
        val status = item.status?.trim()?.lowercase()

        if (status == "dropped") {
            false
        } else {
            val hasNext = !item.nextToWatch.isNullOrBlank()
            val hasLastWatchedAt = !item.lastWatchedAt.isNullOrBlank()
            val watchedCount = item.watchedEpisodesCount ?: 0
            val hasProgress = watchedCount > 0

            hasNext || hasLastWatchedAt || hasProgress
        }
    }
    .mapNotNull { item ->
        val show = item.show ?: return@mapNotNull null
        val simklId = show.ids?.simkl?.toString() ?: return@mapNotNull null
        val mergedId = "show-$simklId"

        if (mergedId in playbackIds) return@mapNotNull null

        val imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() }

        if (imdbId == null) {
            Log.e(
                "SIMKL_REPO",
                "Watching show missing imdb id, simklId=$simklId, title=${show.title}"
            )
        }
            .mapNotNull { item ->
                val show = item.show ?: return@mapNotNull null
                val simklId = show.ids?.simkl?.toString() ?: return@mapNotNull null
                val mergedId = "show-$simklId"

                if (mergedId in playbackIds) return@mapNotNull null

                val imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() }

package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SimklRepository(
    context: Context? = null
) {

    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val clientSecret = BuildConfig.SIMKL_CLIENT_SECRET

    private val prefs = context?.applicationContext?.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            SimklQueryInterceptor(
                clientId = clientId,
                appName = SimklConfig.APP_NAME,
                appVersion = SimklConfig.APP_VERSION
            )
        )
        .build()

    private val api: SimklApiService = Retrofit.Builder()
        .baseUrl(SimklConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SimklApiService::class.java)

    fun isConfigured(): Boolean {
        return clientId.isNotBlank() && clientSecret.isNotBlank()
    }

    fun hasToken(): Boolean {
        val token = getSavedAccessToken()
        Log.e(
            "SIMKL_REPO",
            "hasToken check: prefsIsNull=${prefs == null}, tokenIsNullOrBlank=${token.isNullOrBlank()}, instance=${System.identityHashCode(this)}"
        )
        return !token.isNullOrBlank()
    }

    fun getSavedAccessToken(): String? {
        return prefs?.getString(KEY_ACCESS_TOKEN, null)
    }

    fun clearAuth() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_LAST_WATCHED_ACTIVITY_ALL)
            ?.apply()
    }

    suspend fun createPinCode(): SimklPinCodeResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.createPinCode()
    }

    suspend fun checkPin(userCode: String): SimklTokenResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        val response = api.checkPin(userCode)
        response.accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { saveAccessToken(it) }

        return response
    }

    suspend fun getActivities(
        accessToken: String = requireAccessToken()
    ): SimklActivitiesResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.getActivities(authorization = bearer(accessToken))
    }

    suspend fun hasWatchedActivityChanged(
        accessToken: String = requireAccessToken()
    ): Boolean {
        if (!isConfigured() || !hasToken()) return false

        val activities = runCatching { getActivities(accessToken) }
            .onFailure {
                Log.e("SIMKL_REPO", "getActivities failed: ${it.message}", it)
            }
            .getOrNull()
            ?: return false

        val latest = activities.all?.trim().orEmpty()
        if (latest.isBlank()) {
            Log.e("SIMKL_REPO", "activities.all was blank; treating as unchanged")
            return false
        }

        val saved = getSavedWatchedActivityAll().orEmpty()
        val changed = saved != latest

        Log.e(
            "SIMKL_REPO",
            "hasWatchedActivityChanged changed=$changed saved=$saved latest=$latest"
        )

        return changed
    }

    suspend fun markWatchedActivitySynced(
        accessToken: String = requireAccessToken()
    ) {
        if (!isConfigured() || !hasToken()) return

        val activities = runCatching { getActivities(accessToken) }
            .onFailure {
                Log.e("SIMKL_REPO", "markWatchedActivitySynced getActivities failed: ${it.message}", it)
            }
            .getOrNull()
            ?: return

        val latest = activities.all?.trim().orEmpty()
        if (latest.isBlank()) return

        saveWatchedActivityAll(latest)

        Log.e("SIMKL_REPO", "markWatchedActivitySynced saved activity all=$latest")
    }

    fun forceClearWatchedActivitySync() {
        prefs?.edit()?.remove(KEY_LAST_WATCHED_ACTIVITY_ALL)?.apply()
    }

    fun getSavedWatchedActivityAll(): String? {
        return prefs?.getString(KEY_LAST_WATCHED_ACTIVITY_ALL, null)
    }

    suspend fun getPlaybackItems(
        accessToken: String = requireAccessToken()
    ): List<SimklPlaybackItem> {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.getPlayback(
            authorization = bearer(accessToken),
            extended = "full"
        )
    }

    suspend fun getWatchingShows(
        accessToken: String = requireAccessToken()
    ): SimklWatchingShowsResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.getWatchingShows(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )
    }

    suspend fun getWatchedBulkImport(
        movieImdbIds: List<String> = emptyList(),
        movieSimklIds: List<Int> = emptyList(),
        showImdbIds: List<String> = emptyList(),
        showSimklIds: List<Int> = emptyList(),
        accessToken: String = requireAccessToken()
    ): SimklWatchedImport {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        val movieRefs = buildIdRefs(
            imdbIds = movieImdbIds,
            simklIds = movieSimklIds
        )
        val showRefs = buildIdRefs(
            imdbIds = showImdbIds,
            simklIds = showSimklIds
        )

        if (movieRefs.isEmpty() && showRefs.isEmpty()) {
            return SimklWatchedImport(
                watchedMovieImdbIds = emptySet(),
                watchedMovieSimklIds = emptySet(),
                watchedShowImdbIds = emptySet(),
                watchedShowSimklIds = emptySet(),
                watchedEpisodesByShowKey = emptyMap()
            )
        }

        val requestBody = SimklWatchedBulkRequest(
            movies = movieRefs.map { SimklWatchedLookupMovie(ids = it) },
            shows = showRefs.map { SimklWatchedLookupShow(ids = it) }
        )

        val rawResponse = api.getWatchedBulkRaw(
            authorization = bearer(accessToken),
            body = requestBody
        )

        val rawText = try {
            rawResponse.body()?.string()
        } catch (e: Exception) {
            "unreadable raw body: ${e.message}"
        }

        Log.e(
            "SIMKL_REPO",
            "getWatchedBulk RAW code=${rawResponse.code()} message=${rawResponse.message()} contentType=${rawResponse.headers()["Content-Type"]} body=$rawText"
        )

        return SimklWatchedImport(
            watchedMovieImdbIds = emptySet(),
            watchedMovieSimklIds = emptySet(),
            watchedShowImdbIds = emptySet(),
            watchedShowSimklIds = emptySet(),
            watchedEpisodesByShowKey = emptyMap()
        )
    }

    private fun isShowFullyWatched(item: SimklWatchingShowItem): Boolean {
        val status = item.status?.trim()?.lowercase()
        val watched = item.watchedEpisodesCount
        val total = item.totalEpisodesCount
        val notAired = item.notAiredEpisodesCount ?: 0
        val airedTotal = total?.let { it - notAired }
        val caughtUpOnAired =
            watched != null && airedTotal != null && airedTotal > 0 && watched >= airedTotal

        if (caughtUpOnAired) return true

        val hasNext = !item.nextToWatch.isNullOrBlank()
        return status == "completed" && !hasNext
    }

    private fun isShowFullyWatched(item: SimklWatchingShowDetailedItem): Boolean {
        val status = item.status?.trim()?.lowercase()
        val watched = item.watchedEpisodesCount
        val total = item.totalEpisodesCount
        val notAired = item.notAiredEpisodesCount ?: 0
        val airedTotal = total?.let { it - notAired }
        val caughtUpOnAired =
            watched != null && airedTotal != null && airedTotal > 0 && watched >= airedTotal

        if (caughtUpOnAired) return true

        val hasNext = !item.nextToWatch.isNullOrBlank()
        return status == "completed" && !hasNext
    }

    suspend fun getCompletedMovieImdbIds(
        accessToken: String = requireAccessToken()
    ): Set<String> {
        val httpResponse = api.getCompletedMovies(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )

        Log.e(
            "SIMKL_REPO",
            "getCompletedMovies raw: code=${httpResponse.code()}, message=${httpResponse.message()}"
        )

        if (!httpResponse.isSuccessful) {
            val errorText = try {
                httpResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getCompletedMovies failed body: $errorText")
            return emptySet()
        }

        val body = httpResponse.body()

        if (body == null) {
            Log.e("SIMKL_REPO", "getCompletedMovies succeeded but body was null")
            return emptySet()
        }

        Log.e("SIMKL_REPO", "getCompletedMovies parsed ok: movies=${body.movies.size}")

        return body.movies
            .mapNotNull { it.movie?.ids?.imdb?.takeIf { id -> id.isNotBlank() } }
            .toSet()
    }

    suspend fun isShowWatchedByImdb(
        imdbId: String,
        tmdbId: Int? = null,
        accessToken: String = requireAccessToken()
    ): Boolean {
        if (imdbId.isBlank()) return false

        val response = try {
            api.getAllShowItems(
                authorization = bearer(accessToken),
                extended = "full",
                includeAllEpisodes = "original",
                episodeWatchedAt = "yes"
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getAllShowItems failed for imdb=$imdbId: ${e.message}", e)
            return false
        }

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getAllShowItems failed body: $errorText")
            return false
        }

        val body = response.body()
        if (body == null) {
            Log.e("SIMKL_REPO", "isShowWatchedByImdb body was null for imdb=$imdbId")
            return false
        }

        val match = body.shows.firstOrNull { item ->
            item.show?.ids?.imdb == imdbId ||
                (tmdbId != null && item.show?.ids?.tmdb == tmdbId)
        }

        if (match == null) {
            Log.e("SIMKL_REPO", "isShowWatchedByImdb no match imdb=$imdbId tmdbId=$tmdbId")
            return false
        }

        val isWatched = isShowFullyWatched(match)

        Log.e(
            "SIMKL_REPO",
            "isShowWatchedByImdb imdb=$imdbId tmdbId=$tmdbId watched=$isWatched status=${match.status} watchedEpisodes=${match.watchedEpisodesCount} totalEpisodes=${match.totalEpisodesCount} notAired=${match.notAiredEpisodesCount} nextToWatch=${match.nextToWatch}"
        )

        return isWatched
    }

    suspend fun getWatchedEpisodesForShowByImdb(
        imdbId: String,
        tmdbId: Int? = null,
        accessToken: String = requireAccessToken()
    ): Set<Pair<Int, Int>> {

        Log.e(
            "SIMKL_REPO",
            "show lookup imdb=$imdbId accessTokenLength=${accessToken.length} savedTokenLength=${getSavedAccessToken()?.length ?: 0}"
        )

        if (imdbId.isBlank()) return emptySet()

        val bulkDebug = getWatchedBulkImport(
            showImdbIds = listOf(imdbId),
            accessToken = accessToken
        )

        Log.e(
            "SIMKL_REPO",
            "bulk debug imdb=$imdbId watchedShowImdbIds=${bulkDebug.watchedShowImdbIds} watchedShowSimklIds=${bulkDebug.watchedShowSimklIds} watchedEpisodesKeys=${bulkDebug.watchedEpisodesByShowKey.keys} watchedEpisodes=${bulkDebug.watchedEpisodesByShowKey["imdb:$imdbId"]}"
        )

        val response = try {
            api.getAllShowItems(
                authorization = bearer(accessToken),
                extended = "full",
                includeAllEpisodes = "original",
                episodeWatchedAt = "yes"
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getAllShowItems failed for imdb=$imdbId: ${e.message}", e)
            return emptySet()
        }

        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getAllShowItems failed body: $errorText")
            return emptySet()
        }

        val body = response.body()
        if (body == null) {
            Log.e("SIMKL_REPO", "getAllShowItems body was null for imdb=$imdbId")
            return emptySet()
        }

        val tedLassoLike = body.shows
            .filter { item ->
                val title = item.show?.title.orEmpty()
                title.contains("Ted Lasso", ignoreCase = true) ||
                    title.contains("The Bear", ignoreCase = true)
            }
            .take(10)
            .map { item ->
                "${item.show?.title}|imdb=${item.show?.ids?.imdb}|tmdb=${item.show?.ids?.tmdb}|simkl=${item.show?.ids?.simkl}|seasons=${item.seasons.size}"
            }

        Log.e(
            "SIMKL_REPO",
            "getAllShowItems totalShows=${body.shows.size} tedLassoLike=$tedLassoLike"
        )

        val match = body.shows.firstOrNull { item ->
            item.show?.ids?.imdb == imdbId ||
                (tmdbId != null && item.show?.ids?.tmdb == tmdbId)
        }

        Log.e(
            "SIMKL_REPO",
            "getAllShowItems lookup imdb=$imdbId tmdbId=$tmdbId found=${match != null} matchTitle=${match?.show?.title} matchImdb=${match?.show?.ids?.imdb} matchTmdb=${match?.show?.ids?.tmdb} matchSimkl=${match?.show?.ids?.simkl} seasons=${match?.seasons?.size ?: 0}"
        )

        val pairs = match?.seasons
            ?.flatMap { season ->
                val seasonNumber = season.number
                season.episodes.mapNotNull { ep ->
                    val episodeNumber = ep.number ?: ep.episode
                    if (seasonNumber != null && episodeNumber != null && !ep.watchedAt.isNullOrBlank()) {
                        seasonNumber to episodeNumber
                    } else {
                        null
                    }
                }
            }
            ?.toSet()
            .orEmpty()

        Log.e(
            "SIMKL_REPO",
            "getAllShowItems episodes imdb=$imdbId count=${pairs.size} sample=${pairs.take(30)}"
        )

        return pairs
    }

    suspend fun getCompletedMovieKeys(
        accessToken: String = requireAccessToken()
    ): Set<String> {
        val completedResponse = api.getCompletedMovies(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )

        val allItemsResponse = api.getAllMovieItems(
            authorization = bearer(accessToken),
            dateFrom = null,
            extended = "full"
        )

        fun extractKeys(body: SimklCompletedMoviesResponse?): Set<String> {
            if (body == null) return emptySet()

            body.movies.take(50).forEach { item ->
                Log.e(
                    "SIMKL_REPO",
                    "movie item title=${item.movie?.title} imdb=${item.movie?.ids?.imdb} tmdb=${item.movie?.ids?.tmdb} simkl=${item.movie?.ids?.simkl}"
                )
            }

            return body.movies.flatMap { item ->
                val ids = item.movie?.ids
                buildList {
                    ids?.imdb?.takeIf { it.isNotBlank() }?.let { add("imdb:$it") }
                    ids?.tmdb?.let { add("tmdb:$it") }
                    ids?.simkl?.let { add("simkl:$it") }
                }
            }.toSet()
        }

        val completedKeys = if (completedResponse.isSuccessful) {
            extractKeys(completedResponse.body())
        } else {
            val errorText = try {
                completedResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getCompletedMovies failed body: $errorText")
            emptySet()
        }

        val allMovieKeys = if (allItemsResponse.isSuccessful) {
            extractKeys(allItemsResponse.body())
        } else {
            val errorText = try {
                allItemsResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getAllMovieItems failed body: $errorText")
            emptySet()
        }

        val merged = completedKeys + allMovieKeys

        Log.e(
            "SIMKL_REPO",
            "movie key sync completed=${completedKeys.size} allItems=${allMovieKeys.size} merged=${merged.size}"
        )
        Log.e(
            "SIMKL_REPO",
            "movie key sample=${merged.take(80)}"
        )

        return merged
    }

    suspend fun getContinueWatching(
        accessToken: String = requireAccessToken()
    ): List<SimklContinueWatchingItem> {
        val playbackItems = getPlaybackItems(accessToken)
        val watchingShows = getWatchingShows(accessToken).shows

        val watchingBySimklId: Map<String, SimklWatchingShowItem> = watchingShows
            .mapNotNull { w -> w.show?.ids?.simkl?.toString()?.let { it to w } }
            .toMap()

        fun isTrulyCompleted(simklId: String): Boolean {
            val watchingEntry = watchingBySimklId[simklId] ?: return false
            return isShowFullyWatched(watchingEntry)
        }

        val playbackMapped = playbackItems.mapNotNull { item ->
            when {
                item.movie != null -> {
                    val movie = item.movie
                    val simklId = movie.ids?.simkl?.toString()
                        ?: item.id?.toString()
                        ?: return@mapNotNull null
                    val imdbId = movie.ids?.imdb?.takeIf { it.isNotBlank() }

                    if (imdbId == null) {
                        Log.e(
                            "SIMKL_REPO",
                            "Movie missing imdb id, simklId=$simklId, title=${movie.title}"
                        )
                    }

                    Log.e(
                        "SIMKL_REPO",
                        "Movie poster raw, title=${movie.title}, poster=${movie.poster}"
                    )

                    SimklContinueWatchingItem(
    id = "movie-$simklId",
    imdbId = imdbId,
    tmdbId = movie.ids?.tmdb,
        simklId = movie.ids?.simkl ?: simklId.toIntOrNull(),
    title = movie.title ?: "Untitled movie",
    year = movie.year,
    posterUrl = normalizePosterUrl(movie.poster),
    lastWatchedAt = item.pausedAt,
    progress = item.progress,
    upNextText = "Resume movie",
    mediaType = "movie",
    source = "playback",
    season = null,
    episode = null
)
                }

                item.show != null -> {
                    val show = item.show
                    val simklId = show.ids?.simkl?.toString()
                        ?: item.id?.toString()
                        ?: return@mapNotNull null

if (isTrulyCompleted(simklId) && (item.progress ?: 0f) >= 95f) return@mapNotNull null
                    }

                    val imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() }

                    if (imdbId == null) {
                        Log.e(
                            "SIMKL_REPO",
                            "Show missing imdb id, simklId=$simklId, title=${show.title}"
                        )
                    }

                    Log.e(
                        "SIMKL_REPO",
                        "Show poster raw, title=${show.title}, poster=${show.poster}"
                    )

                    SimklContinueWatchingItem(
    id = "show-$simklId",
    imdbId = imdbId,
    tmdbId = show.ids?.tmdb,
        simklId = show.ids?.simkl ?: simklId.toIntOrNull(),
    title = show.title ?: "Untitled show",
    year = show.year,
    posterUrl = normalizePosterUrl(show.poster),
    lastWatchedAt = item.pausedAt,
    progress = item.progress,
    upNextText = buildPlaybackUpNextText(item.episode),
    mediaType = "series",
    source = "playback",
    season = item.episode?.season,
    episode = item.episode?.episode
)
                }

                else -> null
            }
        }

        val playbackIds = playbackMapped.map { it.id }.toSet()

        val watchingMapped = watchingShows
            .asSequence()
            .filter { item ->
        val status = item.status?.trim()?.lowercase()

        if (status == "dropped") {
            false
        } else {
            val hasNext = !item.nextToWatch.isNullOrBlank()
            val hasLastWatchedAt = !item.lastWatchedAt.isNullOrBlank()
            val watchedCount = item.watchedEpisodesCount ?: 0
            val hasProgress = watchedCount > 0

            hasNext || hasLastWatchedAt || hasProgress
        }
    }
    .mapNotNull { item ->
        val show = item.show ?: return@mapNotNull null
        val simklId = show.ids?.simkl?.toString() ?: return@mapNotNull null
        val mergedId = "show-$simklId"

        if (mergedId in playbackIds) return@mapNotNull null

        val imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() }

        if (imdbId == null) {
            Log.e(
                "SIMKL_REPO",
                "Watching show missing imdb id, simklId=$simklId, title=${show.title}"
            )
        }
            .mapNotNull { item ->
                val show = item.show ?: return@mapNotNull null
                val simklId = show.ids?.simkl?.toString() ?: return@mapNotNull null
                val mergedId = "show-$simklId"

                if (mergedId in playbackIds) return@mapNotNull null

                val imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() }

                if (imdbId == null) {
                    Log.e(
                        "SIMKL_REPO",
                        "Watching show missing imdb id, simklId=$simklId, title=${show.title}"
                    )
                }

val parsedNext = parseNextTarget(item.nextToWatch)
val fallbackNext = fallbackWatchingTarget(item)
val nextSeason = parsedNext?.first ?: fallbackNext?.first
val nextEpisode = parsedNext?.second ?: fallbackNext?.second

                SimklContinueWatchingItem(
    id = mergedId,
    imdbId = imdbId,
    tmdbId = show.ids?.tmdb,
    simklId = show.ids?.simkl,
    title = show.title ?: "Untitled show",
    year = show.year,
    posterUrl = normalizePosterUrl(show.poster),
    lastWatchedAt = item.lastWatchedAt ?: item.addedToWatchlistAt,
    progress = buildWatchingProgress(
        watchedEpisodesCount = item.watchedEpisodesCount,
        totalEpisodesCount = item.totalEpisodesCount
    ),
    upNextText = buildWatchingUpNextText(
        nextToWatch = item.nextToWatch,
        lastWatched = item.lastWatched,
        status = item.status
    ),
    mediaType = "series",
    source = "watching",
    season = nextSeason,
    episode = nextEpisode
)
            }
            .toList()

        return (playbackMapped + watchingMapped)
            .sortedWith(
                compareByDescending<SimklContinueWatchingItem> { scoreContinueWatchingItem(it) }
                    .thenByDescending { sortableTimestamp(it.lastWatchedAt) }
                    .thenBy { it.title.lowercase() }
            )
    }

        data class WatchedActivityRefreshResult(
        val attempted: Boolean,
        val changed: Boolean,
        val latestActivity: String? = null,
        val errorMessage: String? = null
    )

    suspend fun refreshWatchedActivity(): WatchedActivityRefreshResult {
        if (!isConfigured()) {
            Log.e("SIMKL_REPO", "refreshWatchedActivity skipped: Simkl is not configured")

            return WatchedActivityRefreshResult(
                attempted = false,
                changed = false,
                errorMessage = "Simkl is not configured"
            )
        }

        if (!hasToken()) {
            Log.e("SIMKL_REPO", "refreshWatchedActivity skipped: no saved access token")

            return WatchedActivityRefreshResult(
                attempted = false,
                changed = false,
                errorMessage = "Simkl is not authenticated"
            )
        }

        return try {
            val activities = getActivities()
            val latestActivity = activities.all?.trim().orEmpty()
            val savedActivity = getSavedWatchedActivityAll()?.trim().orEmpty()

            if (latestActivity.isBlank()) {
                Log.e(
                    "SIMKL_REPO",
                    "refreshWatchedActivity completed: activities.all was blank"
                )

                return WatchedActivityRefreshResult(
                    attempted = true,
                    changed = false,
                    latestActivity = null
                )
            }

            val changed = latestActivity != savedActivity

            Log.e(
                "SIMKL_REPO",
                "refreshWatchedActivity completed: changed=$changed " +
                    "saved=$savedActivity latest=$latestActivity"
            )

            WatchedActivityRefreshResult(
                attempted = true,
                changed = changed,
                latestActivity = latestActivity
            )
        } catch (e: Exception) {
            Log.e(
                "SIMKL_REPO",
                "refreshWatchedActivity failed: ${e.message}",
                e
            )

            WatchedActivityRefreshResult(
                attempted = true,
                changed = false,
                errorMessage = e.message
            )
        }
    }

    private fun buildIdRefs(
        imdbIds: List<String>,
        simklIds: List<Int>
    ): List<SimklPlaybackIdsRef> {
        val refs = mutableListOf<SimklPlaybackIdsRef>()

        imdbIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { imdb ->
                refs += SimklPlaybackIdsRef(imdb = imdb)
            }

        simklIds.asSequence()
            .distinct()
            .forEach { simkl ->
                refs += SimklPlaybackIdsRef(simkl = simkl)
            }

        return refs
    }

    private fun scoreContinueWatchingItem(item: SimklContinueWatchingItem): Int {
        var score = 0

        if (item.source == "playback") {
            score += 300
        }

        if (item.mediaType == "movie" && item.source == "playback") {
            score += 25
        }

        if (item.source == "watching" && item.mediaType == "series") {
            when {
                item.season != null && item.episode != null -> score += 220
                item.season != null -> score += 210
                else -> score += 180
            }
        }

        val text = item.upNextText.orEmpty().lowercase()
        if (text.contains("resume")) {
            score += 150
        }

        return score
    }

    private fun buildPlaybackUpNextText(
        episode: SimklPlaybackEpisode?
    ): String {
        if (episode == null) return "Resume show"

        val code = buildEpisodeCode(
            season = episode.season,
            episode = episode.episode
        )

        return buildString {
            append("Up next")
            if (code != null) {
                append(": ")
                append(code)
            }
            episode.title
                ?.takeIf { it.isNotBlank() }
                ?.let { title ->
                    append(" • ")
                    append(title)
                }
        }
    }

    private fun buildWatchingProgress(
        watchedEpisodesCount: Int?,
        totalEpisodesCount: Int?
    ): Float? {
        if (watchedEpisodesCount == null || totalEpisodesCount == null || totalEpisodesCount <= 0) {
            return null
        }

        return (watchedEpisodesCount.toFloat() / totalEpisodesCount.toFloat()) * 100f
    }

    private fun buildWatchingUpNextText(
        nextToWatch: String?,
        lastWatched: String?,
        status: String?
    ): String {
        val next = nextToWatch?.trim().orEmpty()

        if (next.isNotBlank()) {
            val normalized = next.lowercase()

            return when {
                Regex("""s\d+e\d+""", RegexOption.IGNORE_CASE).matches(normalized) -> "$next • Next episode"
                Regex("""s\d+""", RegexOption.IGNORE_CASE).matches(normalized) -> "$next • New season"
                normalized.startsWith("s") -> next
                else -> next
            }
        }

        val parts = mutableListOf<String>()

        status?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts += it.replaceFirstChar { ch -> ch.uppercase() }
            }

        lastWatched?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts += "Last watched $it"
            }

        return if (parts.isNotEmpty()) {
            parts.joinToString(" • ")
        } else {
            "Watching"
        }
    }

    private fun parseNextTarget(value: String?): Pair<Int, Int?>? {
        val match = Regex("""s(\d+)(?:e(\d+))?""", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim().orEmpty())
            ?: return null

        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues
            .getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()

        return season to episode
    }

    private fun fallbackWatchingTarget(
    item: SimklWatchingShowItem
): Pair<Int, Int?>? {
    val watchedCount = item.watchedEpisodesCount ?: return null
    if (watchedCount > 0) return null

    val status = item.status?.trim()?.lowercase().orEmpty()
    if (status == "dropped" || status == "completed") return null

    return 1 to 1
    }


    private fun buildEpisodeCode(
        season: Int?,
        episode: Int?
    ): String? {
        if (season == null && episode == null) return null

        return buildString {
            season?.let { append("S$it") }
            episode?.let { append("E$it") }
        }.ifBlank { null }
    }

    private fun sortableTimestamp(value: String?): String {
        return value?.trim().orEmpty()
    }

    private fun normalizePosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return "https://simkl.in/posters/${raw}_m.jpg"
    }

    private fun requireAccessToken(): String {
        return getSavedAccessToken()
            ?.takeIf { it.isNotBlank() }
            ?: error("Simkl access token is missing")
    }

    private fun saveAccessToken(token: String) {
        prefs?.edit()?.putString(KEY_ACCESS_TOKEN, token)?.apply()
    }

    private fun saveWatchedActivityAll(value: String) {
        prefs?.edit()?.putString(KEY_LAST_WATCHED_ACTIVITY_ALL, value)?.apply()
    }

    private fun bearer(token: String): String = "Bearer $token"

    companion object {
        private const val PREFS_NAME = "simkl_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_LAST_WATCHED_ACTIVITY_ALL = "last_watched_activity_all"
    }
}

    suspend fun refreshWatchedActivity(): WatchedActivityRefreshResult {
        if (!isConfigured()) {
            Log.e("SIMKL_REPO", "refreshWatchedActivity skipped: Simkl is not configured")

            return WatchedActivityRefreshResult(
                attempted = false,
                changed = false,
                errorMessage = "Simkl is not configured"
            )
        }

        if (!hasToken()) {
            Log.e("SIMKL_REPO", "refreshWatchedActivity skipped: no saved access token")

            return WatchedActivityRefreshResult(
                attempted = false,
                changed = false,
                errorMessage = "Simkl is not authenticated"
            )
        }

        return try {
            val activities = getActivities()
            val latestActivity = activities.all?.trim().orEmpty()
            val savedActivity = getSavedWatchedActivityAll()?.trim().orEmpty()

            if (latestActivity.isBlank()) {
                Log.e(
                    "SIMKL_REPO",
                    "refreshWatchedActivity completed: activities.all was blank"
                )

                return WatchedActivityRefreshResult(
                    attempted = true,
                    changed = false,
                    latestActivity = null
                )
            }

            val changed = latestActivity != savedActivity

            Log.e(
                "SIMKL_REPO",
                "refreshWatchedActivity completed: changed=$changed " +
                    "saved=$savedActivity latest=$latestActivity"
            )

            WatchedActivityRefreshResult(
                attempted = true,
                changed = changed,
                latestActivity = latestActivity
            )
        } catch (e: Exception) {
            Log.e(
                "SIMKL_REPO",
                "refreshWatchedActivity failed: ${e.message}",
                e
            )

            WatchedActivityRefreshResult(
                attempted = true,
                changed = false,
                errorMessage = e.message
            )
        }
    }

    private fun buildIdRefs(
        imdbIds: List<String>,
        simklIds: List<Int>
    ): List<SimklPlaybackIdsRef> {
        val refs = mutableListOf<SimklPlaybackIdsRef>()

        imdbIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { imdb ->
                refs += SimklPlaybackIdsRef(imdb = imdb)
            }

        simklIds.asSequence()
            .distinct()
            .forEach { simkl ->
                refs += SimklPlaybackIdsRef(simkl = simkl)
            }

        return refs
    }

    private fun scoreContinueWatchingItem(item: SimklContinueWatchingItem): Int {
        var score = 0

        if (item.source == "playback") {
            score += 300
        }

        if (item.mediaType == "movie" && item.source == "playback") {
            score += 25
        }

        if (item.source == "watching" && item.mediaType == "series") {
            when {
                item.season != null && item.episode != null -> score += 220
                item.season != null -> score += 210
                else -> score += 180
            }
        }

        val text = item.upNextText.orEmpty().lowercase()
        if (text.contains("resume")) {
            score += 150
        }

        return score
    }

    private fun buildPlaybackUpNextText(
        episode: SimklPlaybackEpisode?
    ): String {
        if (episode == null) return "Resume show"

        val code = buildEpisodeCode(
            season = episode.season,
            episode = episode.episode
        )

        return buildString {
            append("Up next")
            if (code != null) {
                append(": ")
                append(code)
            }
            episode.title
                ?.takeIf { it.isNotBlank() }
                ?.let { title ->
                    append(" • ")
                    append(title)
                }
        }
    }

    private fun buildWatchingProgress(
        watchedEpisodesCount: Int?,
        totalEpisodesCount: Int?
    ): Float? {
        if (watchedEpisodesCount == null || totalEpisodesCount == null || totalEpisodesCount <= 0) {
            return null
        }

        return (watchedEpisodesCount.toFloat() / totalEpisodesCount.toFloat()) * 100f
    }

    private fun buildWatchingUpNextText(
        nextToWatch: String?,
        lastWatched: String?,
        status: String?
    ): String {
        val next = nextToWatch?.trim().orEmpty()

        if (next.isNotBlank()) {
            val normalized = next.lowercase()

            return when {
                Regex("""s\d+e\d+""", RegexOption.IGNORE_CASE).matches(normalized) -> "$next • Next episode"
                Regex("""s\d+""", RegexOption.IGNORE_CASE).matches(normalized) -> "$next • New season"
                normalized.startsWith("s") -> next
                else -> next
            }
        }

        val parts = mutableListOf<String>()

        status?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts += it.replaceFirstChar { ch -> ch.uppercase() }
            }

        lastWatched?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts += "Last watched $it"
            }

        return if (parts.isNotEmpty()) {
            parts.joinToString(" • ")
        } else {
            "Watching"
        }
    }

    private fun parseNextTarget(value: String?): Pair<Int, Int?>? {
        val match = Regex("""s(\d+)(?:e(\d+))?""", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim().orEmpty())
            ?: return null

        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues
            .getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()

        return season to episode
    }

    private fun fallbackWatchingTarget(
    item: SimklWatchingShowItem
): Pair<Int, Int?>? {
    val watchedCount = item.watchedEpisodesCount ?: return null
    if (watchedCount > 0) return null

    val status = item.status?.trim()?.lowercase().orEmpty()
    if (status == "dropped" || status == "completed") return null

    return 1 to 1
    }


    private fun buildEpisodeCode(
        season: Int?,
        episode: Int?
    ): String? {
        if (season == null && episode == null) return null

        return buildString {
            season?.let { append("S$it") }
            episode?.let { append("E$it") }
        }.ifBlank { null }
    }

    private fun sortableTimestamp(value: String?): String {
        return value?.trim().orEmpty()
    }

    private fun normalizePosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return "https://simkl.in/posters/${raw}_m.jpg"
    }

    private fun requireAccessToken(): String {
        return getSavedAccessToken()
            ?.takeIf { it.isNotBlank() }
            ?: error("Simkl access token is missing")
    }

    private fun saveAccessToken(token: String) {
        prefs?.edit()?.putString(KEY_ACCESS_TOKEN, token)?.apply()
    }

    private fun saveWatchedActivityAll(value: String) {
        prefs?.edit()?.putString(KEY_LAST_WATCHED_ACTIVITY_ALL, value)?.apply()
    }

    private fun bearer(token: String): String = "Bearer $token"

    companion object {
        private const val PREFS_NAME = "simkl_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_LAST_WATCHED_ACTIVITY_ALL = "last_watched_activity_all"
    }
}
