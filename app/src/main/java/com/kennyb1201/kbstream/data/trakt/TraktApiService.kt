package com.kennyb1201.kbstream.data.trakt

import okhttp3.Interceptor
import okhttp3.Response as OkHttpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Trakt's OAuth surface plus the reads and writes the integration uses
 * (scrobble, watch history, watched snapshot, user lists, comments).
 *
 * Every answer that can legitimately be an error is returned as a
 * [Response] rather than as a parsed body, because Trakt's device flow
 * communicates its state through status codes: 400 while the viewer has not
 * approved yet is the NORMAL case, not a failure. Throwing on it would turn
 * polling into a crash loop (see [TraktDeviceFlowRules] for what each code
 * means).
 */
interface TraktApiService {

    @POST("oauth/device/code")
    suspend fun requestDeviceCode(
        @Body body: TraktDeviceCodeRequest
    ): TraktDeviceCodeResponse

    @POST("oauth/device/token")
    suspend fun pollDeviceToken(
        @Body body: TraktDeviceTokenRequest
    ): Response<TraktTokenResponse>

    @POST("oauth/token")
    suspend fun refreshToken(
        @Body body: TraktRefreshRequest
    ): Response<TraktTokenResponse>

    @POST("oauth/revoke")
    suspend fun revokeToken(
        @Header("Authorization") authorization: String,
        @Body body: TraktRevokeRequest
    ): Response<Unit>

    @GET("users/settings")
    suspend fun userSettings(
        @Header("Authorization") authorization: String
    ): TraktUserSettings

    // -------------------------------------------------------------------
    // Playback and watch state
    // -------------------------------------------------------------------

    /**
     * `POST /scrobble/{start,pause,stop}`.
     *
     * 201 carries the recorded action; 409 means Trakt ignored it (the viewer
     * had already watched the item, or the session was too short). Both are
     * answers, not failures, so the body stays unparsed when it is not 2xx.
     */
    @POST("scrobble/{action}")
    suspend fun scrobble(
        @Path("action") action: String,
        @Header("Authorization") authorization: String,
        @Body body: TraktScrobbleRequest
    ): Response<TraktScrobbleResponse>

    /** `POST /sync/history` - mark a movie or episode watched. */
    @POST("sync/history")
    suspend fun addToHistory(
        @Header("Authorization") authorization: String,
        @Body body: TraktSyncHistoryRequest
    ): Response<Unit>

    /** `POST /sync/history/remove` - the exact inverse of the above. */
    @POST("sync/history/remove")
    suspend fun removeFromHistory(
        @Header("Authorization") authorization: String,
        @Body body: TraktSyncHistoryRequest
    ): Response<Unit>

    /**
     * The account's watched shows, with their watched episodes nested.
     *
     * Trakt pages this read at [limit] per page (100 when the caller says
     * nothing), so a single call is page 1 only: the repository walks the
     * pages rather than trusting one answer to be the whole history.
     */
    @GET("sync/watched/shows")
    suspend fun watchedShows(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktWatchedShow>>

    /** The account's watched movies, one page at a time (see [watchedShows]). */
    @GET("sync/watched/movies")
    suspend fun watchedMovies(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktWatchedMovie>>

    /**
     * Paused movie sessions, newest first - Trakt's own resume points.
     *
     * The read that lets Trakt be the authoritative resume source on another
     * device (see [com.kennyb1201.kbstream.data.settings.ResumeAuthority]).
     */
    @GET("sync/playback/movies")
    suspend fun playbackMovies(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktPlaybackItem>>

    /** Paused episode sessions, newest first. */
    @GET("sync/playback/episodes")
    suspend fun playbackEpisodes(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktPlaybackItem>>

    /** `DELETE /sync/playback/{id}` - drop one paused session. */
    @DELETE("sync/playback/{id}")
    suspend fun deletePlaybackSession(
        @Header("Authorization") authorization: String,
        @Path("id") id: Long
    ): Response<Unit>

    /**
     * The account's watchlist, newest first.
     *
     * Trakt returns one row per title with a `movie` or `show` object; the
     * `extended=full,images` read is what carries title/year and artwork, so
     * the Library tab does not need a TMDB lookup per row just to draw it.
     */
    @GET("sync/watchlist")
    suspend fun watchlist(
        @Header("Authorization") authorization: String,
        @Query("extended") extended: String = "full,images",
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 1
    ): Response<List<TraktListItem>>

    /** `POST /sync/watchlist` - the Library's add mirror. */
    @POST("sync/watchlist")
    suspend fun addToWatchlist(
        @Header("Authorization") authorization: String,
        @Body body: TraktSyncHistoryRequest
    ): Response<Unit>

    /** `POST /sync/watchlist/remove` - the watchlist's removal write. */
    @POST("sync/watchlist/remove")
    suspend fun removeFromWatchlist(
        @Header("Authorization") authorization: String,
        @Body body: TraktSyncHistoryRequest
    ): Response<Unit>

    /** The authenticated account's personal lists. */
    @GET("users/me/lists")
    suspend fun userLists(
        @Header("Authorization") authorization: String,
        @Query("extended") extended: String = "full"
    ): Response<List<TraktUserList>>

    /**
     * A user list's items, one media type at a time.
     *
     * Trakt has no all-types GET here: `/items/{type}` is the documented read,
     * so the repository asks for movies and shows and merges them.
     * `extended=full,images` is what carries the title/year and the artwork.
     */
    @GET("users/{user}/lists/{listId}/items/{type}")
    suspend fun listItems(
        @Path("user") user: String,
        @Path("listId") listId: String,
        @Path("type") type: String,
        @Query("extended") extended: String = "full,images",
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 1
    ): Response<List<TraktListItem>>

    /** `POST /users/me/lists/{listId}/items` - an item add. */
    @POST("users/me/lists/{listId}/items")
    suspend fun addToList(
        @Header("Authorization") authorization: String,
        @Path("listId") listId: String,
        @Body body: TraktSyncHistoryRequest
    ): Response<Unit>

    /** `POST /users/me/lists/{listId}/items/remove`. */
    @POST("users/me/lists/{listId}/items/remove")
    suspend fun removeFromList(
        @Header("Authorization") authorization: String,
        @Path("listId") listId: String,
        @Body body: TraktSyncHistoryRequest
    ): Response<Unit>

    /** The same read for an export that named only the list id. */
    @GET("lists/{listId}/items/{type}")
    suspend fun listItemsById(
        @Path("listId") listId: String,
        @Path("type") type: String,
        @Query("extended") extended: String = "full,images",
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 1
    ): Response<List<TraktListItem>>

    /** Resolves a TMDB id to the Trakt entry (used by the comments read). */
    @GET("search/{idType}/{id}")
    suspend fun searchById(
        @Path("idType") idType: String,
        @Path("id") id: String,
        @Query("type") type: String
    ): Response<List<TraktSearchResult>>

    /**
     * `GET /{type}/{id}/comments/{sort}`.
     *
     * `{id}` accepts a Trakt id, Trakt slug or IMDb id - never a TMDB id, which
     * is why [searchById] exists. `{type}` is the plural path segment
     * ("movies" / "shows").
     */
    @GET("{type}/{id}/comments/{sort}")
    suspend fun comments(
        @Path("type") type: String,
        @Path("id") id: String,
        @Path("sort") sort: String = "likes",
        @Query("extended") extended: String = "full",
        @Query("limit") limit: Int = 20,
        @Query("page") page: Int = 1
    ): Response<List<TraktComment>>
}

/**
 * The two headers Trakt wants on every call: the public API key and the API
 * version.
 *
 * This app is a public client with one key compiled in, so the key is added here
 * rather than at each call site - the same shape as [com.kennyb1201.kbstream.data.simkl.SimklQueryInterceptor],
 * which appends Simkl's id as a query parameter because that is where Simkl wants
 * it.
 */
internal class TraktHeadersInterceptor(
    private val clientId: String = TraktConfig.clientId,
    private val apiVersion: String = TraktConfig.API_VERSION
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val request = chain.request().newBuilder()
            .header("trakt-api-key", clientId)
            .header("trakt-api-version", apiVersion)
            .header("Content-Type", "application/json")
            .header("User-Agent", TraktConfig.USER_AGENT)
            .build()
        return chain.proceed(request)
    }
}
