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

        return api.getActivities(
            authorization = bearer(accessToken)
        )
    }

    suspend fun getWatchingShows(
        accessToken: String = requireAccessToken(),
        dateFrom: String? = null,
        extended: String? = "full"
    ): List<SimklSyncItem> {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        return api.getWatchingShows(
            authorization = bearer(accessToken),
            dateFrom = dateFrom,
            extended = extended
        )
    }

    suspend fun getWatchingMovies(
        accessToken: String = requireAccessToken(),
        dateFrom: String? = null,
        extended: String? = "full"
    ): List<SimklMovieSyncItem> {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        return api.getWatchingMovies(
            authorization = bearer(accessToken),
            dateFrom = dateFrom,
            extended = extended
        )
    }

    suspend fun getWatchingAnime(
        accessToken: String = requireAccessToken(),
        dateFrom: String? = null,
        extended: String? = "full"
    ): List<SimklAnimeSyncItem> {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }

        return api.getWatchingAnime(
            authorization = bearer(accessToken),
            dateFrom = dateFrom,
            extended = extended
        )
    }

    suspend fun getEnrichedLibraryItems(
        accessToken: String = requireAccessToken(),
        dateFrom: String? = null,
        extended: String? = "full"
    ): List<SimklLibraryItem> {
        val shows = getWatchingShows(
            accessToken = accessToken,
            dateFrom = dateFrom,
            extended = extended
        ).mapNotNull { item ->
            val show = item.show ?: return@mapNotNull null
            SimklLibraryItem(
                title = show.title ?: "Untitled show",
                year = show.year,
                posterUrl = show.poster,
                status = item.status,
                lastWatchedAt = item.lastWatchedAt,
                mediaType = "Show",
                subtitle = item.nextToWatchInfo?.let { next ->
                    buildString {
                        append("Next: ")
                        next.season?.let { append("S$it") }
                        next.episode?.let { append("E$it") }
                        next.title?.let {
                            if (length > 6) append(" - ")
                            append(it)
                        }
                    }.ifBlank { null }
                }
            )
        }

        val movies = getWatchingMovies(
            accessToken = accessToken,
            dateFrom = dateFrom,
            extended = extended
        ).mapNotNull { item ->
            val movie = item.movie ?: return@mapNotNull null
            SimklLibraryItem(
                title = movie.title ?: "Untitled movie",
                year = movie.year,
                posterUrl = movie.poster,
                status = item.status,
                lastWatchedAt = item.lastWatchedAt,
                mediaType = "Movie",
                subtitle = null
            )
        }

        val anime = getWatchingAnime(
            accessToken = accessToken,
            dateFrom = dateFrom,
            extended = extended
        ).mapNotNull { item ->
            val show = item.show ?: return@mapNotNull null
            SimklLibraryItem(
                title = show.title ?: "Untitled anime",
                year = show.year,
                posterUrl = show.poster,
                status = item.status,
                lastWatchedAt = item.lastWatchedAt,
                mediaType = "Anime",
                subtitle = item.nextToWatchInfo?.let { next ->
                    buildString {
                        append("Next: ")
                        next.season?.let { append("S$it") }
                        next.episode?.let { append("E$it") }
                        next.title?.let {
                            if (length > 6) append(" - ")
                            append(it)
                        }
                    }.ifBlank { null }
                }
            )
        }

        return (shows + movies + anime)
            .sortedByDescending { it.lastWatchedAt ?: "" }
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
