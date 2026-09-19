package com.kennyb1201.kbstream.data.simkl

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
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

    @GET("users/settings")
    suspend fun getUserSettings(
        @Header("Authorization") authorization: String
    ): SimklUserSettingsResponse

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
        @Query("episode_watched_at") episodeWatchedAt: String? = "yes",
        @Query("page") page: Int = 1
    ): Response<SimklWatchingShowsDetailedResponse>

    @GET("sync/all-items/shows/completed")
    suspend fun getCompletedShowsDetailed(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full",
        @Query("include_all_episodes") includeAllEpisodes: String? = "yes",
        @Query("episode_watched_at") episodeWatchedAt: String? = "yes",
        @Query("page") page: Int = 1
    ): Response<SimklWatchingShowsDetailedResponse>

    @GET("sync/all-items/shows")
    suspend fun getAllShowItems(
        @Header("Authorization") authorization: String,
        @Query("extended") extended: String = "full",
        @Query("include_all_episodes") includeAllEpisodes: String = "original",
        @Query("episode_watched_at") episodeWatchedAt: String = "yes"
    ): Response<SimklAllShowsResponse>

    @GET("sync/all-items/movies/completed")
    suspend fun getCompletedMovies(
        @Header("Authorization") authorization: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("extended") extended: String? = "full"
    ): Response<SimklCompletedMoviesResponse>

    @GET("sync/all-items/movies")
    suspend fun getAllMovieItems(
    @Header("Authorization") authorization: String,
    @Query("date_from") dateFrom: String? = null,
    @Query("extended") extended: String? = "full"
    ): Response<SimklCompletedMoviesResponse>

    @POST("sync/watched")
    suspend fun getWatchedBulk(
        @Header("Authorization") authorization: String,
        @Body body: SimklWatchedBulkRequest
    ): Response<SimklWatchedBulkResponse>

    @POST("sync/watched")
    suspend fun getWatchedBulkRaw(
        @Header("Authorization") authorization: String,
        @Body body: SimklWatchedBulkRequest
    ): Response<ResponseBody>

    @POST("sync/history")
    suspend fun addToWatchedHistory(
        @Header("Authorization") authorization: String,
        @Body body: SimklHistoryRequest
    ): Response<ResponseBody>

    /*
     * Outbound "mark unwatched" (POST /sync/history/remove). Simkl's
     * supported way to undo a watched mark; DELETE /sync/history is not
     * handled and silently leaves the item in history. Sending the same
     * movie/show payload with no seasons removes the whole title from the
     * user's Simkl history, which un-watches it there.
     */
    @POST("sync/history/remove")
    suspend fun removeFromWatchedHistory(
        @Header("Authorization") authorization: String,
        @Body body: SimklHistoryRequest
    ): Response<ResponseBody>

    /*
     * Outbound "Remove from Continue Watching" for Simkl-backed cards:
     * DELETE /sync/playback/{id} drops the paused playback session (the
     * progress record behind the Continue Watching feed) so the title stops
     * coming back from the remote feed. 204 on success; 404 when the
     * session is already gone.
     */
    @DELETE("sync/playback/{id}")
    suspend fun deletePlaybackSession(
        @Path("id") id: Int,
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    /*
     * Watchlist (Plan to Watch) read: all-items with the plantowatch
     * status. Covers both movies and shows in one call per type; movies
     * use the movies type, series the shows type.
     */
    @GET("sync/all-items/movies/plantowatch")
    suspend fun getWatchlistMovies(
        @Header("Authorization") authorization: String,
        @Query("extended") extended: String? = "full"
    ): Response<SimklWatchlistMoviesResponse>

    @GET("sync/all-items/shows/plantowatch")
    suspend fun getWatchlistShows(
        @Header("Authorization") authorization: String,
        @Query("extended") extended: String? = "full"
    ): Response<SimklWatchlistShowsResponse>

    /*
     * Watchlist write: POST /sync/add-to-list moves an item into a
     * watchlist status (plantowatch here) WITHOUT recording a watch
     * event — exactly the "Add to Library" semantics. The `to` field
     * sits at the request root, not per item, on this endpoint family.
     */
    @POST("sync/add-to-list")
    suspend fun addToWatchlist(
        @Header("Authorization") authorization: String,
        @Body body: SimklAddToListRequest
    ): Response<ResponseBody>

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequest
    ): Response<ResponseBody>

    @POST("scrobble/pause")
    suspend fun scrobblePause(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequest
    ): Response<ResponseBody>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Header("Authorization") authorization: String,
        @Body body: SimklScrobbleRequest
    ): Response<ResponseBody>

}
