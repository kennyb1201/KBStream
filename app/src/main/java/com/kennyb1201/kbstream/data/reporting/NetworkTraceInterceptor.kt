package com.kennyb1201.kbstream.data.reporting

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Times every HTTP call the app makes and files it under a short label
 * (tmdb / simkl / youtube / addon.<name> / …), so the diagnostics dump can
 * answer "where does the time actually go" without a profiler on the TV.
 *
 * One interceptor is shared by each client (see the `addInterceptor` wiring
 * in the TMDB, Simkl, addon and IPTV clients); it only reads the host, so
 * adding it cannot change request behavior.
 *
 * Two labels exist because the host alone does not identify the caller:
 *
 *  - **addon traffic is named after the addon**, not after its host. Addons
 *    are user-supplied and a self-hosted one is commonly reached by bare
 *    address, so the host label for it was `http.132` — a name that named
 *    nothing about which addon spent the time. [addonNameForHost] resolves the
 *    installed addon for the host; the addon client is the only client that
 *    installs one.
 *  - **a bare address gets one shared label**, `http.ip`, with the address
 *    recorded beside it by [PerfTrace.recordHost], so the endpoint can be
 *    identified and given a name.
 */
internal class NetworkTraceInterceptor(
    /**
     * The installed addon answering on [host], or null when none is — see
     * `AddonManager.addonNameForHost`. Supplied by the addon client, which is
     * the only one whose hosts belong to an addon; every other client leaves it
     * null and keeps the host-derived label.
     */
    private val addonNameForHost: (String) -> String? = { null }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val label = labelFor(host)
        // Only the labels that do not name their own destination carry the
        // host: "http.tmdb" describes itself, "http.ip" does not, and for an
        // addon it is the address someone may want to replace with a name.
        if (label == IP_LABEL || label.startsWith(ADDON_PREFIX)) {
            PerfTrace.recordHost(label, host)
        }
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

    private fun labelFor(host: String): String {
        knownServiceLabel(host)?.let { return it }
        addonNameForHost(host)?.let { name -> return ADDON_PREFIX + addonSlug(name) }
        return if (isIpLiteralHost(host)) IP_LABEL
        else "http." + host.removePrefix("www.").substringBefore('.')
    }

    /** The app's own API hosts, which are never addon hosts. */
    private fun knownServiceLabel(host: String): String? = when {
        host.contains("themoviedb") || host.contains("tmdb") -> "http.tmdb"
        host.contains("simkl") -> "http.simkl"
        host.contains("youtube") || host.contains("googlevideo") || host.contains("ytimg") ->
            "http.youtube"
        host.contains("opensubtitles") -> "http.subtitles"
        else -> null
    }

    private companion object {
        const val IP_LABEL = "http.ip"
        const val ADDON_PREFIX = "http.addon."
    }
}

/**
 * Label-safe form of an addon's name: `AIOStreams (self-hosted)` becomes
 * `aiostreams-self-hosted`.
 *
 * Addon names are free text the user typed, so everything but letters and
 * digits collapses to a single dash and runs are trimmed. The label is printed
 * once per ranked line in the summary and nowhere else, so the length is capped
 * to keep an accidental paragraph out of the report; a name with nothing usable
 * in it at all falls back to `addon` rather than producing a bare prefix.
 */
internal fun addonSlug(name: String): String {
    val slug = name.lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
    return slug.take(MAX_SLUG_LENGTH).ifEmpty { "addon" }
}

private const val MAX_SLUG_LENGTH = 24

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
