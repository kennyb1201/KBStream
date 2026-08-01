package com.kennyb1201.kbstream.data.simkl

import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SimklRepository {

    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val clientSecret = BuildConfig.SIMKL_CLIENT_SECRET

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

    suspend fun createPinCode(): SimklPinCodeResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.createPinCode()
    }

    suspend fun checkPin(userCode: String): SimklTokenResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        return api.checkPin(userCode)
    }

    suspend fun getActivities(accessToken: String): SimklActivitiesResponse {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        require(accessToken.isNotBlank()) { "Simkl access token is missing" }

        return api.getActivities(
            authorization = bearer(accessToken)
        )
    }

    suspend fun getWatchingShows(
        accessToken: String,
        dateFrom: String? = null,
        extended: String? = "full"
    ): List<SimklSyncItem> {
        require(clientId.isNotBlank()) { "SIMKL_CLIENT_ID is missing" }
        require(accessToken.isNotBlank()) { "Simkl access token is missing" }

        return api.getWatchingShows(
            authorization = bearer(accessToken),
            dateFrom = dateFrom,
            extended = extended
        )
    }

    private fun bearer(token: String): String = "Bearer $token"
}
