package com.kennyb1201.kbstream.data.simkl

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface SimklApiService {

    @GET("oauth/pin")
    suspend fun createPinCode(
        @Query("client_id") clientId: String,
        @Query("redirect") redirect: String = "urn:ietf:wg:oauth:2.0:oob"
    ): SimklPinCodeResponse

    @POST("oauth/token")
    suspend fun exchangePinForToken(
        @Body request: SimklPinTokenRequest
    ): SimklTokenResponse

    @GET("sync/activities")
    suspend fun getActivities(
        @Header("Authorization") authorization: String,
        @Query("client_id") clientId: String,
        @Query("app-name") appName: String,
        @Query("app-version") appVersion: String
    ): SimklActivitiesResponse

    @GET("sync/all-items/shows")
    suspend fun getAllShowItems(
        @Header("Authorization") authorization: String,
        @Query("client_id") clientId: String,
        @Query("app-name") appName: String,
        @Query("app-version") appVersion: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = null,
        @Query("next_watch_info") nextWatchInfo: String = "yes"
    ): List<SimklSyncItem>
}
