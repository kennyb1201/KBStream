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
        android.util.Log.e(
            "SIMKL_REPO",
            "hasToken check: prefsIsNull=${prefs == null}, tokenIsNullOrBlank=${token.isNullOrBlank()}, " +
                "instance=${System.identityHashCode(this)}"
        )
        return !token.isNullOrBlank()
    }

    fun getSavedAccessToken(): String? {
        return prefs?.getString(KEY_ACCESS_TOKEN, null)
    }

    fun clearAuth() {
        prefs?.edit()?.remove(KEY_ACCESS_TOKEN)?.apply()
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

        val httpResponse = api.getWatchedBulk(
            authorization = bearer(accessToken),
            body = SimklWatchedBulkRequest(
                movies = movieRefs.map { SimklWatchedLookupMovie(ids = it) },
                shows = showRefs.map { SimklWatchedLookupShow(ids = it) }
            )
        )

        Log.e(
            "SIMKL_REPO",
            "getWatchedBulk raw: code=${httpResponse.code()}, " +
                "message=${httpResponse.message()}, " +
                "contentType=${httpResponse.headers()["Content-Type"]}"
        )

        if (!httpResponse.isSuccessful) {
            val errorText = try {
                httpResponse.errorBody()?.string()
            } catch (e: Exception) {
                "unreadable: ${e.message}"
            }
            Log.e("SIMKL_REPO", "getWatchedBulk failed body: $errorText")
            return SimklWatchedImport(
                watchedMovieImdbIds = emptySet(),
                watchedMovieSimklIds = emptySet(),
                watchedShowImdbIds = emptySet(),
                watchedShowSimklIds = emptySet(),
                watchedEpisodesByShowKey = emptyMap()
            )
        }

        val response = httpResponse.body()

        if (response == null) {
            Log.e(
                "SIMKL_REPO",
                "getWatchedBulk succeeded (${httpResponse.code()}) but body was null -- " +
                    "endpoint likely returned a shape Moshi couldn't map to SimklWatchedBulkResponse, " +
                    "or a genuinely empty successful body"
            )
            return SimklWatchedImport(
                watchedMovieImdbIds = emptySet(),
                watchedMovieSimklIds = emptySet(),
                watchedShowImdbIds = emptySet(),
                watchedShowSimklIds = emptySet(),
                watchedEpisodesByShowKey = emptyMap()
            )
        }

        Log.e(
            "SIMKL_REPO",
            "getWatchedBulk parsed ok: movies=${response.movies.size}, shows=${response.shows.size}"
        )

        val watchedMovieImdbIds = mutableSetOf<String>()
        val watchedMovieSimklIds = mutableSetOf<Int>()
        val watchedShowImdbIds = mutableSetOf<String>()
        val watchedShowSimklIds = mutableSetOf<Int>()
        val watchedEpisodesByShowKey = linkedMapOf<String, MutableSet<Pair<Int, Int>>>()

        response.movies.forEach { item ->
            if (item.watched == true) {
                item.movie?.ids?.imdb
                    ?.takeIf { it.isNotBlank() }
                    ?.let { watchedMovieImdbIds += it }

                item.movie?.ids?.simkl
                    ?.let { watchedMovieSimklIds += it }
            }
        }

        response.shows.forEach { item ->
            if (item.watched == true) {
                item.show?.ids?.imdb
                    ?.takeIf { it.isNotBlank() }
                    ?.let { watchedShowImdbIds += it }

                item.show?.ids?.simkl
                    ?.let { watchedShowSimklIds += it }
            }

            Log.e(
                "SIMKL_REPO",
                "bulk watched show imdb=${item.show?.ids?.imdb} simkl=${item.show?.ids?.simkl} watched=${item.watched} episodes=${item.episodes}"
            )

            val episodePairs = item.episodes
                .mapNotNull { ep ->
                    val season = ep.season
                    val episode = ep.episode
                    if (season != null && episode != null) {
                        season to episode
                    } else {
                        null
                    }
                }
                .toSet()

            if (episodePairs.isNotEmpty()) {
                item.show?.ids?.imdb
                    ?.takeIf { it.isNotBlank() }
                    ?.let { imdb ->
                        watchedEpisodesByShowKey.getOrPut("imdb:$imdb") { linkedSetOf() }
                            .addAll(episodePairs)
                    }

                item.show?.ids?.simkl
                    ?.let { simkl ->
                        watchedEpisodesByShowKey.getOrPut("simkl:$simkl") { linkedSetOf() }
                            .addAll(episodePairs)
                    }
            }
        }

        return SimklWatchedImport(
            watchedMovieImdbIds = watchedMovieImdbIds,
            watchedMovieSimklIds = watchedMovieSimklIds,
            watchedShowImdbIds = watchedShowImdbIds,
            watchedShowSimklIds = watchedShowSimklIds,
            watchedEpisodesByShowKey = watchedEpisodesByShowKey.mapValues { it.value.toSet() }
        )
    }

    // Shared "is this show fully caught up" check -- used both to filter completed
    // shows out of Continue Watching, and to build the watched-indicator id set.
    // Prefers actual episode counts (aired episodes watched) over Simkl's status
    // string, since status can lag behind (stays "watching" for an ongoing show
    // even once you're caught up on everything currently released).
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

    // All IMDb ids for shows the user has fully watched (per isShowFullyWatched),
    // for use as a general watched-indicator signal anywhere in the app -- not
    // just the Continue Watching row.
    suspend fun getCompletedShowImdbIds(
        accessToken: String = requireAccessToken()
    ): Set<String> {
        val watchingShows = getWatchingShows(accessToken).shows

        return watchingShows
            .filter { isShowFullyWatched(it) }
            .mapNotNull { it.show?.ids?.imdb?.takeIf { id -> id.isNotBlank() } }
            .toSet()
    }

    // All IMDb ids for movies Simkl has marked completed. Mirrors the proven
    // sync/all-items/shows/watching pattern rather than the sync/watched bulk
    // endpoint, which returned a bare null body for this app's request shape.
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

    suspend fun isMovieWatchedByImdb(
        imdbId: String,
        accessToken: String = requireAccessToken()
    ): Boolean {
        if (imdbId.isBlank()) return false

        val result = getWatchedBulkImport(
            movieImdbIds = listOf(imdbId),
            accessToken = accessToken
        )

        return imdbId in result.watchedMovieImdbIds
    }

    suspend fun isShowWatchedByImdb(
        imdbId: String,
        accessToken: String = requireAccessToken()
    ): Boolean {
        if (imdbId.isBlank()) return false

        val result = getWatchedBulkImport(
            showImdbIds = listOf(imdbId),
            accessToken = accessToken
        )

        return imdbId in result.watchedShowImdbIds
    }

    suspend fun getWatchedEpisodesForShowByImdb(
        imdbId: String,
        accessToken: String = requireAccessToken()
    ): Set<Pair<Int, Int>> {
        if (imdbId.isBlank()) return emptySet()

        val watchingResponse = try {
            api.getWatchingShowsDetailed(
                authorization = bearer(accessToken),
                dateFrom = null,
                extended = "full",
                includeAllEpisodes = "yes",
                episodeWatchedAt = "yes"
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getWatchingShowsDetailed failed for imdb=$imdbId: ${e.message}", e)
            null
        }

        val completedResponse = try {
            api.getCompletedShowsDetailed(
                authorization = bearer(accessToken),
                dateFrom = null,
                extended = "full",
                includeAllEpisodes = "yes",
                episodeWatchedAt = "yes"
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getCompletedShowsDetailed failed for imdb=$imdbId: ${e.message}", e)
            null
        }

        val watchingShow = watchingResponse?.shows?.firstOrNull { it.show?.ids?.imdb == imdbId }
        val completedShow = completedResponse?.shows?.firstOrNull { it.show?.ids?.imdb == imdbId }

        Log.e(
            "SIMKL_REPO",
            "detailed episode lookup imdb=$imdbId: foundInWatching=${watchingShow != null}, " +
                "foundInCompleted=${completedShow != null}"
        )

        fun episodesFrom(show: SimklWatchingShowDetailedItem?): Set<Pair<Int, Int>> {
            return show?.seasons
                ?.flatMap { season ->
                    val seasonNumber = season.number
                    season.episodes.mapNotNull { ep ->
                        val episodeNumber = ep.number ?: ep.episode
                        val watchedAt = ep.watchedAt

                        if (seasonNumber != null && episodeNumber != null && !watchedAt.isNullOrBlank()) {
                            seasonNumber to episodeNumber
                        } else {
                            null
                        }
                    }
                }
                ?.toSet()
                .orEmpty()
        }

        val merged = episodesFrom(watchingShow) + episodesFrom(completedShow)
        Log.e("SIMKL_REPO", "merged watched episodes for imdb=$imdbId = $merged")

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
                        title = movie.title ?: "Untitled movie",
                        year = movie.year,
                        posterUrl = normalizePosterUrl(movie.poster),
                        lastWatchedAt = item.pausedAt,
                        progress = item.progress,
                        upNextText = "Resume movie",
                        mediaType = "movie",
                        source = "playback"
                    )
                }

                item.show != null -> {
                    val show = item.show
                    val simklId = show.ids?.simkl?.toString()
                        ?: item.id?.toString()
                        ?: return@mapNotNull null

                    if (isTrulyCompleted(simklId)) {
                        return@mapNotNull null
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
                        title = show.title ?: "Untitled show",
                        year = show.year,
                        posterUrl = normalizePosterUrl(show.poster),
                        lastWatchedAt = item.pausedAt,
                        progress = item.progress,
                        upNextText = buildPlaybackUpNextText(item.episode),
                        mediaType = "series",
                        source = "playback"
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
                val watched = item.watchedEpisodesCount
                val total = item.totalEpisodesCount
                val notAired = item.notAiredEpisodesCount ?: 0
                val airedTotal = total?.let { it - notAired }
                val caughtUpOnAired =
                    watched != null && airedTotal != null && airedTotal > 0 && watched >= airedTotal

                if (caughtUpOnAired) {
                    false
                } else if (airedTotal == null) {
                    val hasNext = !item.nextToWatch.isNullOrBlank()
                    status != "dropped" && (status != "completed" || hasNext)
                } else {
                    status != "dropped"
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

                SimklContinueWatchingItem(
                    id = mergedId,
                    imdbId = imdbId,
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
                    source = "watching"
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

        val text = item.upNextText.orEmpty().lowercase()

        if (text.contains("next episode")) {
            score += 220
        } else if (text.contains("new season")) {
            score += 210
        } else if (text.startsWith("up next")) {
            score += 180
        } else if (text.contains("resume")) {
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
                    append(" \u2022 ")
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
                normalized.matches(Regex("""s\d+e\d+""")) -> "$next \u2022 Next episode"
                normalized.matches(Regex("""s\d+""")) -> "$next \u2022 New season"
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
            parts.joinToString(" \u2022 ")
        } else {
            "Watching"
        }
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

    private fun bearer(token: String): String = "Bearer $token"

    companion object {
        private const val PREFS_NAME = "simkl_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
    }
}
