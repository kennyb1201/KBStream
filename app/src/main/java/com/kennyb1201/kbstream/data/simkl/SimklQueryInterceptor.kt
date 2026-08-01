package com.kennyb1201.kbstream.data.simkl

import okhttp3.Interceptor
import okhttp3.Response

class SimklQueryInterceptor(
    private val clientId: String,
    private val appName: String = SimklConfig.APP_NAME,
    private val appVersion: String = SimklConfig.APP_VERSION
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        val newUrl = originalUrl.newBuilder()
            .addQueryParameter("client_id", clientId)
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
