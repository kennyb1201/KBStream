package com.kennyb1201.kbstream.data.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor

/**
 * One process-wide base OkHttp client. Feature clients derive from it via
 * [derived]: `newBuilder()` shares the base client's connection pool and
 * dispatcher, so every API host in the app reuses pooled TCP+TLS connections
 * instead of each feature client idling its own private pool of sockets and
 * threads. Per-feature timeouts/interceptors stay exactly as they were —
 * they are per-call settings and do not affect pool sharing.
 *
 * Derived callers today: MDBList, stream badges, KB collections, TMDB (its
 * shared client IS [get]), the IntroDB skip lookups, addon subtitle
 * downloads, the IPTV/EPG stack, and the addon repository.
 *
 * Two things to know before adding another:
 *
 *  - A derived client inherits the base DISPATCHER unless it installs its own,
 *    and a dispatcher caps concurrent requests per host. The two callers that
 *    fan out against a single host - AddonRepository and TmdbHttpClient - set
 *    one with a higher cap for that reason; sharing this dispatcher is right
 *    for features that talk to different hosts, which is what the rest do.
 *  - Nothing may shut down or evict this client: it is the base every one of
 *    those features derives from (nothing does, and nothing should).
 *
 * NOT for playback: the player keeps its own dedicated clients (its stack,
 * UA handling and watchdog behavior are deliberately self-contained).
 */
object BaseHttpClient {

    @Volatile
    private var base: OkHttpClient? = null

    fun get(): OkHttpClient =
        base ?: synchronized(this) {
            base ?: OkHttpClient.Builder()
                // Brotli first: it only rewrites the response body (and the
                // Accept-Encoding it asks with), so everything derived from
                // this client - every feature interceptor included - sees an
                // already-decompressed body exactly as it would with gzip.
                // A server that ignores br falls back to identity/gzip with no
                // change in behavior; there is no request-side risk.
                .addInterceptor(BrotliInterceptor)
                // A wider idle pool than OkHttp's default of five. Every feature
                // client in the app derives from this one and shares its pool
                // (see the class docs), while the app itself talks to a whole
                // set of hosts - TMDB, MDBList, IntroDB, the IPTV/EPG stack,
                // the subtitle mirrors, the addons. Five idle sockets is fewer
                // than that set, so a burst on one host could evict the warm
                // connection another host was about to reuse and pay a fresh
                // TCP+TLS handshake for it on the next call. Twelve covers the
                // working set; the five-minute keep-alive is OkHttp's default
                // and is right for a box that idles minutes between syncs.
                .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
                .build()
                .also { base = it }
        }

    /** Derive a client with per-feature configuration, sharing the base pool. */
    fun derived(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
        get().newBuilder().apply(configure).build()
}
