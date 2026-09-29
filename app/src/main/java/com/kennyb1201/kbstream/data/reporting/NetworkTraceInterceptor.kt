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
 * adding it cannot change request behavior.
 *
 * A host that is a bare address gets one shared label instead of one built
 * from its first octet: `132.226.4.9` used to file as `http.132`, a name that
 * named nothing, so the slowest endpoint in a capture could not be identified
 * from the report at all. It is now `http.ip`, and [PerfTrace.recordHost]
 * carries the address alongside it so the dump says which one it was.
 */
internal class NetworkTraceInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val label = labelFor(host)
        // Only for the labels whose name does not already say where they went:
        // "http.tmdb" describes itself, "http.ip" does not.
        if (label == IP_LABEL) PerfTrace.recordHost(label, host)
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
        host.contains("youtube") || host.contains("googlevideo") || host.contains("ytimg") ->
            "http.youtube"
        host.contains("opensubtitles") -> "http.subtitles"
        isIpLiteralHost(host) -> IP_LABEL
        else -> "http." + host.removePrefix("www.").substringBefore('.')
    }

    private companion object {
        const val IP_LABEL = "http.ip"
    }
}

/**
 * Whether [host] is a bare address rather than a name — an IPv4 dotted quad, or
 * an IPv6 literal (okhttp's `host` drops the brackets).
 *
 * Worth telling apart because an address is nearly always a misconfiguration.
 * There is no name to validate a certificate against, so the connection cannot
 * use TLS, and a name is what lets a device reuse and resume a connection. It
 * is also what made such a host unidentifiable in the perf summary.
 *
 * Hand-rolled rather than `InetAddresses.isNumericAddress` (API 29, and this app
 * ships API 23) or `Patterns.IP_ADDRESS`, so the rule lives in one place that a
 * unit test can pin and does not change with the API level.
 */
internal fun isIpLiteralHost(host: String): Boolean {
    if (host.isEmpty()) return false
    // An IPv6 literal arrives without its brackets, so any colon is one.
    if (host.contains(':')) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 &&
            part.all { it.isDigit() } &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
