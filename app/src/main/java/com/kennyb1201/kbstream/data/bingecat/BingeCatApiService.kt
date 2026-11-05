package com.kennyb1201.kbstream.data.bingecat

import okhttp3.Interceptor
import okhttp3.Response as OkHttpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * BingeCat's OAuth surface and v1's reads.
 *
 * Every poll-able and every data answer is a [Response], never a parsed body:
 * the device flow communicates its state through `error` codes in 400 bodies
 * (`authorization_pending` is the NORMAL answer while the viewer has not
 * approved), and a data read has to be able to keep its cache on a 429 rather
 * than throw into a screen.
 *
 * Every path here is the **explicit profile form**. The root `sync/...`,
 * `calendar` and `search` routes follow BingeCat's *active* profile - on the
 * web - so using one would serve whoever last touched the account in a browser
 * instead of the profile this KBStream profile is mapped to. The mapping is
 * enforced by the shape of the API, not by a convention: there is no root-form
 * call in the interface at all.
 */
interface BingeCatApiService {

    @POST("oauth/device/code")
    suspend fun requestDeviceCode(
        @Body body: BingeCatDeviceCodeRequest
    ): Response<BingeCatDeviceCodeResponse>

    @POST("oauth/token")
    suspend fun pollDeviceToken(
        @Body body: BingeCatDeviceTokenRequest
    ): Response<BingeCatTokenResponse>

    @POST("oauth/token")
    suspend fun refreshToken(
        @Body body: BingeCatRefreshTokenRequest
    ): Response<BingeCatTokenResponse>

    @POST("oauth/revoke")
    suspend fun revoke(
        @Body body: BingeCatRevokeRequest
    ): Response<Unit>

    /** The account's profiles - the mapping step's only account-level read. */
    @GET("api/v1/profiles")
    suspend fun profiles(
        @Header("Authorization") authorization: String
    ): Response<BingeCatProfilesResponse>

    /**
     * `sync/watchlist/{movies|shows}/{sort}/{direction}` and its `favorites`
     * twin. Paged with explicit `page`/`limit` (the contract's own paging for
     * this endpoint), so the repository walks pages rather than trusting one
     * answer to be the whole list.
     */
    @GET("api/v1/profiles/{profileId}/sync/{kind}/{resourceType}/{sortBy}/{sortHow}")
    suspend fun savedItems(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Path("kind") kind: String,
        @Path("resourceType") resourceType: String,
        @Path("sortBy") sortBy: String = "added",
        @Path("sortHow") sortHow: String = "desc",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<BingeCatSavedItem>>

    @GET("api/v1/profiles/{profileId}/lists")
    suspend fun lists(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<BingeCatListsResponse>

    /** A list's entries, addressed by the list's own `ref`. */
    @GET("api/v1/lists/{ref}/items")
    suspend fun listItems(
        @Header("Authorization") authorization: String,
        @Path("ref") ref: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<BingeCatListItemsResponse>

    /**
     * The add-play write (`history:write`), profile-pinned exactly like the
     * reads: a root-form write would file the viewer's play into whatever
     * profile BingeCat last had open in a browser.
     */
    @POST("api/v1/profiles/{profileId}/sync/history")
    suspend fun addHistory(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Body body: BingeCatHistoryBatch
    ): Response<BingeCatHistoryAddResponse>

    /** The remove-play write (`history:write`), the add's mirror and also profile-pinned. */
    @POST("api/v1/profiles/{profileId}/sync/history/remove")
    suspend fun removeHistory(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Body body: BingeCatHistoryBatch
    ): Response<BingeCatHistoryRemoveResponse>

    /**
     * The watchlist add (`lists:write`): "Add or restore profile Favorites or
     * Watchlist items" (step 3's Q2b answer - the per-title add is a batch, the
     * single-item PUT only updates rank/notes). Profile-pinned like every other
     * call in this interface.
     */
    @POST("api/v1/profiles/{profileId}/sync/{kind}")
    suspend fun addSavedItems(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        /** `watchlist` or `favorites`. */
        @Path("kind") kind: String,
        @Body body: BingeCatSavedBatch
    ): Response<BingeCatSavedAddResponse>

    /** The watchlist removal (`lists:write`), the add's mirror and also pinned. */
    @POST("api/v1/profiles/{profileId}/sync/{kind}/remove")
    suspend fun removeSavedItems(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Path("kind") kind: String,
        @Body body: BingeCatSavedBatch
    ): Response<BingeCatSavedRemoveResponse>

    /**
     * One user list's add (`lists:write`): the list is addressed by the typed
     * `ref` its own read returned (`internal:12`, ...), never by a name, and the
     * body is a list of media-reference strings (step 3's Q2a answer).
     */
    @POST("api/v1/lists/{ref}/items")
    suspend fun addListItems(
        @Header("Authorization") authorization: String,
        @Path("ref") ref: String,
        @Body body: BingeCatListItemsWrite
    ): Response<BingeCatListItemsAdded>

    /**
     * One user list's removal (`lists:write`).
     *
     * Declared with `@HTTP` rather than `@DELETE` because the request carries a
     * body: the contract's DELETE takes the same `ListItemsWriteRequest`, and
     * Retrofit's `@DELETE` has no body parameter at all.
     */
    @HTTP(method = "DELETE", path = "api/v1/lists/{ref}/items", hasBody = true)
    suspend fun removeListItems(
        @Header("Authorization") authorization: String,
        @Path("ref") ref: String,
        @Body body: BingeCatListItemsWrite
    ): Response<BingeCatListItemsRemoved>

    /** The watched timeline, cursor-paged (`next_cursor`). */
    @GET("api/v1/profiles/{profileId}/history")
    suspend fun history(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("type") type: String = "all"
    ): Response<BingeCatHistoryFeed>
}

/**
 * The two headers BingeCat wants on every call, plus the UA.
 *
 * `bingecat-api-key` is the application client id, which the contract defines
 * as exactly that - not a secret and not a second credential (see
 * [BingeCatConfig]). `bingecat-api-version` must be exactly `1`; the server
 * answers 426 for anything else, which is why it is a constant rather than a
 * per-call argument.
 */
internal class BingeCatHeadersInterceptor(
    private val clientId: String = BingeCatConfig.clientId,
    private val apiVersion: String = BingeCatConfig.API_VERSION
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val request = chain.request().newBuilder()
            .header("bingecat-api-key", clientId)
            .header("bingecat-api-version", apiVersion)
            .header("Content-Type", "application/json")
            .header("User-Agent", BingeCatConfig.USER_AGENT)
            .build()
        return chain.proceed(request)
    }
}
