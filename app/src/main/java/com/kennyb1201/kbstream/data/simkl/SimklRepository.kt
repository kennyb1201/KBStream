package com.kennyb1201.kbstream.data.simkl

import android.content.Context
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
        return !getSavedAccessToken().isNullOrBlank()
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
        return api.getPlayback(authorization = bearer(accessToken))
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

    suspend fun getContinueWatching(
        accessToken: String = requireAccessToken()
    ): List<SimklContinueWatchingItem> {
        val playbackItems = getPlaybackItems(accessToken)
        val watchingShows = getWatchingShows(accessToken).shows

        val playbackMapped = playbackItems.mapNotNull { item ->
            when {
                item.movie != null -> {
                    val movie = item.movie
                    val simklId = movie.ids?.simkl?.toString()
                        ?: item.id?.toString()
                        ?: return@mapNotNull null

                    SimklContinueWatchingItem(
                        id = "movie-$simklId",
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

                    SimklContinueWatchingItem(
                        id = "show-$simklId",
                        title = show.title ?: "Untitled show",
                        year = show.year,
                        posterUrl = normalizePosterUrl(show.poster),
                        lastWatchedAt = item.pausedAt,
                        progress = item.progress,
                        upNextText = buildPlaybackUpNextText(item.episode),
                        mediaType = "show",
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
                item.status?.trim()?.lowercase() != "dropped"
            }
            .mapNotNull { item ->
                val show = item.show ?: return@mapNotNull null
                val simklId = show.ids?.simkl?.toString() ?: return@mapNotNull null
                val mergedId = "show-$simklId"

                if (mergedId in playbackIds) return@mapNotNull null

                SimklContinueWatchingItem(
                    id = mergedId,
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
                    mediaType = "show",
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

    private fun scoreContinueWatchingItem(item: SimklContinueWatchingItem): Int {
        var score = 0

        if (item.source == "playback") {
            score += 300
        }

        if (item.mediaType == "movie" && item.source == "playback") {
            score += 25
        }

        val text = item.upNextText.orEmpty().lowercase()

        if (text.startsWith("up next:")) {
            score += 220
        }

        if (text.contains("new season")) {
            score += 210
        }

        if (text.contains("next episode")) {
            score += 200
        }

        if (text.contains("up next")) {
            score += 180
        }

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
                normalized.contains("season") -> "New season • $next"
                normalized.contains("episode") -> "Next episode • $next"
                normalized.startsWith("s") -> "Next episode • $next"
                else -> "Up next • $next"
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
        return "https://simkl.in/posters/$raw"
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
