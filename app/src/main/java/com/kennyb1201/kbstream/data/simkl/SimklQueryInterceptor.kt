package com.kennyb1201.kbstream.data.simkl

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Names this app to Simkl on every request (spec 2026-11-16, F0/F4).
 *
 * `client_id`, `app-name`, `app-version` and a `User-Agent` are required on every
 * call, and the V1-to-V2 migration makes the client id a moving part: F4 keeps
 * BOTH registrations live, so the id is resolved per request rather than frozen
 * into the client - the oauth2 endpoints always speak as the V2 app, and a data call
 * speaks as the app that issued the token it is carrying. That rule is
 * [SimklAuthV2Rules.clientIdFor], which is testable on its own; this class is the
 * wire half.
 *
 * A plain `String` is still accepted for the single-id case the tests use.
 */
class SimklQueryInterceptor(
    /** Per-request resolution of the client id; see [SimklAuthV2Rules.clientIdFor]. */
    private val clientIdFor: (path: String?) -> String,
    private val appName: String = SimklConfig.APP_NAME,
    private val appVersion: String = SimklConfig.APP_VERSION
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        val resolvedId = clientIdFor(originalUrl.encodedPath.trimStart('/'))

        val newUrl = originalUrl.newBuilder()
            .addQueryParameter("client_id", resolvedId)
            .addQueryParameter("app-name", appName)
            .addQueryParameter("app-version", appVersion)
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .header("User-Agent", "$appName/$appVersion")
            .build()

        return chain.proceed(newRequest)
    }
}
