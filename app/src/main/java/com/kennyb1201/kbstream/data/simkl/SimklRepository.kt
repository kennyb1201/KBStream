package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SimklRepository(context: Context) {
    private val authManager = SimklAuthManager(context)
    private val prefs = SimklPrefs(context)

    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val clientSecret = BuildConfig.SIMKL_CLIENT_SECRET
    private val appName = "KBStream"
    private val appVersion = "0.1.0"
    private val userAgent = "KBStream/0.1.0"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(request)
            }
        )
        .build()

    private val api = Retrofit.Builder()
        .baseUrl("https://api.simkl.com/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SimklApiService::class.java)

    suspend fun createPinCode(): SimklPinCodeResponse {
        require(clientId.isNotBlank()) { "Missing SIMKL_CLIENT_ID" }
        return api.createPinCode(clientId = clientId)
    }

    suspend fun exchangePin(deviceCode: String): SimklTokenResponse {
        require(clientId.isNotBlank()) { "Missing SIMKL_CLIENT_ID" }
        require(clientSecret.isNotBlank()) { "Missing SIMKL_CLIENT_SECRET" }

        val response = api.exchangePinForToken(
            SimklPinTokenRequest(
                code = deviceCode,
                clientId = clientId,
                clientSecret = clientSecret
            )
        )

        response.accessToken?.let { authManager.accessToken = it }
        response.tokenType?.let { authManager.tokenType = it }
        response.refreshToken?.let { authManager.refreshToken = it }
        response.createdAt?.let { authManager.createdAtSeconds = it }

        return response
    }

    suspend fun getActivities(): SimklActivitiesResponse {
        return api.getActivities(
            authorization = bearerToken(),
            clientId = clientId,
            appName = appName,
            appVersion = appVersion
        )
    }

    suspend fun initialSyncShows(): List<SimklSyncItem> {
        val items = api.getAllShowItems(
            authorization = bearerToken(),
            clientId = clientId,
            appName = appName,
            appVersion = appVersion,
            dateFrom = null,
            extended = null,
            nextWatchInfo = "yes"
        )

        val activities = getActivities()
        prefs.lastActivitiesAll = activities.all
        prefs.initialSyncDone = true
        return items
    }

    suspend fun syncShowsIfNeeded(): List<SimklSyncItem> {
        val activities = getActivities()
        val previous = prefs.lastActivitiesAll
        val current = activities.all

        if (!prefs.initialSyncDone || previous.isNullOrBlank()) {
            return initialSyncShows()
        }

        if (current.isNullOrBlank() || current == previous) {
            return emptyList()
        }

        val items = api.getAllShowItems(
            authorization = bearerToken(),
            clientId = clientId,
            appName = appName,
            appVersion = appVersion,
            dateFrom = previous,
            extended = null,
            nextWatchInfo = "yes"
        )

        prefs.lastActivitiesAll = current
        return items
    }

    fun hasToken(): Boolean = authManager.hasAccessToken()

    fun clearAuth() {
        authManager.clear()
        prefs.clear()
    }

    private fun bearerToken(): String {
        val token = authManager.accessToken
            ?: error("Simkl access token is missing")
        return "Bearer $token"
    }
}
