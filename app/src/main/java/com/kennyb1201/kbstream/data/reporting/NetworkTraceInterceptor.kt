package com.kennyb1201.kbstream.data.reporting

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Times every HTTP call the app makes and files it under a short service
 * label (tmdb / simkl / addons / iptv / youtube / …), so the diagnostics dump
 * can answer "where does the time actually go" without a profiler on the TV.
 *
 * One interceptor is shared by each client (see the `addInterceptor` wiring
 * in the TMDB, Simkl, addon and IPTV clients); it only reads the host, so
 * adding it cannot change request behaviour.
 */
internal class NetworkTraceInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val label = labelFor(request.url.host)
        val started = SystemClock.elapsedRealtime()
        var ok = true
        try {
            val response = chain.proceed(request)
            ok = response.isSuccessful
            return response
        } catch (t: Throwable) {
            ok = false
            throw t
        } finally {
            PerfTrace.record(label, SystemClock.elapsedRealtime() - started, ok)
        }
    }

    private fun labelFor(host: String): String = when {
        host.contains("themoviedb") || host.contains("tmdb") -> "http.tmdb"
        host.contains("simkl") -> "http.simkl"
        host.contains("trakt") -> "http.trakt"
        host.contains("reddit") -> "http.reddit"
        host.contains("youtube") || host.contains("googlevideo") || host.contains("ytimg") ->
            "http.youtube"
        host.contains("opensubtitles") -> "http.subtitles"
        else -> "http." + host.removePrefix("www.").substringBefore('.')
    }
}
