package com.kennyb1201.kbstream.data.trakt

import okhttp3.Interceptor
import okhttp3.Response as OkHttpResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Trakt's OAuth surface, and only that - Phase A is authentication.
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
