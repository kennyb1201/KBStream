package com.kennyb1201.kbstream.data.network

import okhttp3.OkHttpClient

/**
 * One process-wide base OkHttp client. Feature clients derive from it via
 * [derived]: `newBuilder()` shares the base client's connection pool and
 * dispatcher, so every API host in the app reuses pooled TCP+TLS connections
 * instead of each feature client idling its own private pool of sockets and
 * threads. Per-feature timeouts/interceptors stay exactly as they were —
 * they are per-call settings and do not affect pool sharing.
 *
 * NOT for playback: the player keeps its own dedicated clients (its stack,
 * UA handling and watchdog behavior are deliberately self-contained).
 */
object BaseHttpClient {

    @Volatile
    private var base: OkHttpClient? = null

    fun get(): OkHttpClient =
        base ?: synchronized(this) {
            base ?: OkHttpClient.Builder().build().also { base = it }
        }

    /** Derive a client with per-feature configuration, sharing the base pool. */
    fun derived(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
        get().newBuilder().apply(configure).build()
}
