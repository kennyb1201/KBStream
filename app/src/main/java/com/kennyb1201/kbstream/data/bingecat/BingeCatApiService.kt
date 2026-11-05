package com.kennyb1201.kbstream.data.bingecat

import okhttp3.Interceptor
import okhttp3.Response as OkHttpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * BingeCat's OAuth surface and v1's reads.
 *
 * Every poll-able and every data answer is a [Response], never a parsed body:
 * the device flow communicates its state through `error` codes in 400 bodies
 * (`authorization_pending` is the NORMAL answer while the viewer has not
 * approved), and a data read has to be able to keep its cache on a 429 rather
 * than throw into a screen.
 *
 * Every PROFILE-SCOPED path here is the **explicit profile form**. The root
 * `sync/...`, `calendar` and `search` routes follow BingeCat's *active* profile -
 * on the web - so using one would serve whoever last touched the account in a
 * browser instead of the profile this KBStream profile is mapped to. The
 * mapping is enforced by the shape of the API, not by a convention: no
 * profile-scoped call in this interface has a root form.
 *
 * The paths that are NOT profile-scoped are deliberate, and none of them can
 * serve another profile's data: a list is addressed by its own `ref`, public
 * lists are account-agnostic discovery (`search:read`), and a job is addressed
 * by the absolute `status_url` its acceptance carried.
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

    /**
     * `POST /api/v1/profiles/{profileId}/lists` - a new MANUAL list on the
     * mapped profile (`lists:write`).
     *
     * Profile-pinned like every other write: a root-form create would land the
     * list on whatever profile BingeCat last had open in a browser. 201 returns
     * the list; 202 (the job form the AI/filter kinds use) carries it too, so
     * one response type reads both.
     */
    @POST("api/v1/profiles/{profileId}/lists")
    suspend fun createList(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Body body: BingeCatListCreateRequest
    ): Response<BingeCatListResponse>

    /**
     * `PATCH /api/v1/lists/{ref}` - a list's metadata (`lists:write`).
     *
     * The body carries the name alone (`enabled` is untouched by a rename; it
     * belongs to BingeCat's publishing switch, which this app does not offer).
     */
    @PATCH("api/v1/lists/{ref}")
    suspend fun patchList(
        @Header("Authorization") authorization: String,
        @Path("ref") ref: String,
        @Body body: BingeCatListPatchRequest
    ): Response<BingeCatListResponse>

    /**
     * `DELETE /api/v1/lists/{ref}` - a SOFT delete on BingeCat's side
     * (`lists:write`). `restore` can bring it back, but this app offers no
     * undelete, so the confirm dialog says the list leaves BingeCat.
     */
    @DELETE("api/v1/lists/{ref}")
    suspend fun deleteList(
        @Header("Authorization") authorization: String,
        @Path("ref") ref: String
    ): Response<BingeCatListResponse>

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

    // ── Public lists (step 6) ─────────────────────────────────────────
    //
    // Discovery (categories, a category's lists, search) is guarded by
    // `search:read`; the import is the ordinary `lists:write` write, pinned to
    // the mapped profile like every other write here. The three reads take no
    // profile at all - public lists are account-agnostic - so they use the
    // bearer token and nothing else.

    /** `GET /api/v1/public-lists/categories` - the tree, cached until re-read. */
    @GET("api/v1/public-lists/categories")
    suspend fun publicListCategories(
        @Header("Authorization") authorization: String
    ): Response<BingeCatPublicCategoriesResponse>

    /**
     * `GET /api/v1/public-lists/categories/{slug}` - one level's list cards.
     *
     * `subcategory` is the drill-down the tree's `children` name, and `type` is
     * the contract's `all | movies | shows | anime` filter (its own spelling -
     * search uses `movie | series | anime`).
     */
    @GET("api/v1/public-lists/categories/{slug}")
    suspend fun publicListsInCategory(
        @Header("Authorization") authorization: String,
        @Path("slug") slug: String,
        @Query("subcategory") subcategory: String? = null,
        @Query("type") type: String = "all",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<BingeCatPublicCategoryResponse>

    /**
     * `GET /api/v1/public-lists/search` - an ARRAY of list cards, never titles.
     *
     * `limit` is the contract's SearchLimit (max 50, default 20).
     */
    @GET("api/v1/public-lists/search")
    suspend fun searchPublicLists(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "all",
        @Query("category") category: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<List<BingeCatPublicSearchResult>>

    /**
     * `POST /api/v1/profiles/{profileId}/public-lists/import` - a JOB, not a
     * finished list (`202` + `Location` + `Retry-After`).
     */
    @POST("api/v1/profiles/{profileId}/public-lists/import")
    suspend fun importPublicLists(
        @Header("Authorization") authorization: String,
        @Path("profileId") profileId: Long,
        @Body body: BingeCatPublicListImportRequest
    ): Response<BingeCatPublicListImportAccepted>

    /**
     * One job, by the `status_url` its acceptance carried.
     *
     * `@Url` rather than a path: the contract names an absolute status URL, and
     * building it from an id would encode an assumption about the route (the
     * contract lets a job's `kind` move its status endpoint).
     */
    @GET
    suspend fun job(
        @Header("Authorization") authorization: String,
        @Url url: String
    ): Response<BingeCatJob>

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
