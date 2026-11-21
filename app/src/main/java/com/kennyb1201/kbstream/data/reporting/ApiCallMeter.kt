package com.kennyb1201.kbstream.data.reporting

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many requests this process sent to each API (F7, spec 2026-11-20).
 *
 * The quota shape this exists for is one heavy account across nine connections
 * and four devices on free tiers, so every per-launch full-section fetch is
 * multiplied by devices × profiles. "Stop spending quota on known answers" is a
 * claim, not a measurement, unless the calls are counted - and logcat is exactly
 * what is not reachable from the couch, so the counts belong in the diagnostics
 * export beside everything else.
 *
 * Counted at ONE choke point: [BaseHttpClient] is the client every data API in
 * the app derives from (Simkl, Trakt, MDBList, BingeCat, TMDB, the add-on
 * repository, the EPG stack), and a derived client inherits its interceptors
 * through `newBuilder()` - so a new feature client is counted without being
 * taught to be. Counters are per process by design: the question is "what did
 * this session spend", and a persisted counter would answer a different one.
 *
 * [labelFor] is a host map rather than a per-client label, because the counting
 * happens below the clients. The add-on hosts are user-installed and therefore
 * unknowable here, which is why the unnamed bucket is the add-ons': in this app
 * the HTTP that is not one of the named APIs is the add-on repository (the EPG
 * and guide-feed fetches land there too, at one or two calls per refresh).
 */
internal object ApiCallMeter {

    /** Printed in this order, so two pastes line up column for column. */
    private val SERVICES = listOf(
        "simkl",
        "trakt",
        "mdblist",
        "bingecat",
        "tmdb",
        "anilist",
        "mal",
        "kitsu",
        "tvmaze",
        "omdb",
        "addons"
    )

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** The bucket for anything on a host this build does not name. */
    private const val ADDONS = "addons"

    fun record(service: String) {
        counts.getOrPut(service) { AtomicInteger() }.incrementAndGet()
    }

    /** Which service a host belongs to; see the class docs for the fallback. */
    fun labelFor(host: String): String {
        val normalized = host.lowercase()
        return when {
            normalized.matchesHost("simkl.com") -> "simkl"
            normalized.matchesHost("trakt.tv") -> "trakt"
            normalized.matchesHost("mdblist.com") -> "mdblist"
            normalized.matchesHost("bingecat.com") -> "bingecat"
            // Both of TMDB's domains: the API answers on api.themoviedb.org and
            // its images on image.tmdb.org, and neither is an add-on.
            normalized.matchesHost("themoviedb.org") ||
                normalized.matchesHost("tmdb.org") -> "tmdb"
            normalized.matchesHost("anilist.co") -> "anilist"
            normalized.matchesHost("myanimelist.net") -> "mal"
            normalized.matchesHost("kitsu.app") || normalized.matchesHost("kitsu.io") -> "kitsu"
            normalized.matchesHost("tvmaze.com") -> "tvmaze"
            normalized.matchesHost("omdbapi.com") -> "omdb"
            else -> ADDONS
        }
    }

    private fun String.matchesHost(domain: String): Boolean =
        this == domain || endsWith(".$domain")

    /** The diagnostics line: every named service, then the total. */
    fun line(): String {
        val parts = SERVICES.map { service -> "$service=${countOf(service)}" }
        return "calls: ${parts.joinToString(" ")} · total=${total()}"
    }

    fun countOf(service: String): Int = counts[service]?.get() ?: 0

    fun total(): Int = counts.values.sumOf { it.get() }

    /** Tests only: a fresh process. */
    fun resetForTest() {
        counts.clear()
    }
}
