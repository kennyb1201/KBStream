package com.kennyb1201.kbstream.data.simkl

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
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

    @GET("sync/playback")
    suspend fun getPlayback(
        @Header("Authorization") authorization: String,
        @Query("extended") extended: String? = "full"
    ): List<SimklPlaybackItem>

    @GET("sync/all-items/shows/watching")
    suspend fun getWatchingShows(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full"
    ): SimklWatchingShowsResponse

    @GET("sync/all-items/shows/watching")
    suspend fun getWatchingShowsDetailed(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full",
        @Query("include_all_episodes") includeAllEpisodes: String? = "yes",
        @Query("episode_watched_at") episodeWatchedAt: String? = "yes"
    ): SimklWatchingShowsDetailedResponse

    // Same detailed-episode shape as getWatchingShowsDetailed, but for shows
    // that have moved to the "completed" bucket -- a fully finished show drops
    // out of shows/watching entirely, so its episode-watched data has to come
    // from here instead.
    @GET("sync/all-items/shows/completed")
    suspend fun getCompletedShowsDetailed(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full",
        @Query("include_all_episodes") includeAllEpisodes: String? = "yes",
        @Query("episode_watched_at") episodeWatchedAt: String? = "yes"
    ): SimklWatchingShowsDetailedResponse

    @GET("sync/all-items/movies/completed")
    suspend fun getCompletedMovies(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full"
    ): retrofit2.Response<SimklCompletedMoviesResponse>

    @POST("sync/watched")
    suspend fun getWatchedBulk(
        @Header("Authorization") authorization: String,
        @Body body: SimklWatchedBulkRequest
    ): retrofit2.Response<SimklWatchedBulkResponse>
}
