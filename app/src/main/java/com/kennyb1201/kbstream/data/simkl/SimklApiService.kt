package com.kennyb1201.kbstream.data.simkl

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface SimklApiService {

    @GET("oauth/pin")
    suspend fun createPinCode(
        @Query("redirect") redirect: String = "urn:ietf:wg:oauth:2.0:oob"
    ): SimklPinCodeResponse

    @GET("oauth/pin/{userCode}")
    suspend fun checkPin(
        @Path("userCode") userCode: String
    ): SimklTokenResponse

    @GET("sync/activities")
    suspend fun getActivities(
        @Header("Authorization") authorization: String
    ): SimklActivitiesResponse

    @GET("sync/all-items/shows/watching")
    suspend fun getWatchingShows(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full"
    ): List<SimklSyncItem>

    @GET("sync/all-items/movies/watching")
    suspend fun getWatchingMovies(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full"
    ): List<SimklMovieSyncItem>

    @GET("sync/all-items/anime/watching")
    suspend fun getWatchingAnime(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full"
    ): List<SimklAnimeSyncItem>
}
