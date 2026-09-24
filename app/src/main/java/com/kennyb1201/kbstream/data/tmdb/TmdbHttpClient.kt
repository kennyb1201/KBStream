package com.kennyb1201.kbstream.data.tmdb

import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The process-wide TMDB HTTP client, with OkHttp's default per-host
 * concurrency cap raised.
 *
 * Every TMDB call in the app - Home rails, hero artwork, the rails prefetch,
 * Detail, background refresh wrappers - targets the same host
 * (`api.themoviedb.org`). OkHttp's default Dispatcher allows only **5**
 * concurrent requests per host, so while the Home rails prefetch is running, a
 * just-opened screen's own request sits in the queue behind it: the screen
 * waits instead of loading, which reads as "everything got slower". TMDB's
 * rate limit is far above this cap, so raising it removes the queueing without
 * risking 429s.
 *
 * Built from [TmdbRepository.sharedOkHttpClient] via `newBuilder()`, so this
 * client still shares that client's connection pool (and any disk cache) - it
 * only overrides the dispatcher. The tuned instance is cached, so all callers
 * share one client and therefore one dispatcher.
 */
internal object TmdbHttpClient {

    @Volatile
    private var tuned: OkHttpClient? = null

    fun get(): OkHttpClient =
        tuned ?: synchronized(this) {
            tuned ?: TmdbRepository.sharedOkHttpClient()
                .newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
                    }
                )
                .build()
                .also { tuned = it }
        }

    /** Concurrent TMDB requests allowed per host. */
    private const val MAX_REQUESTS_PER_HOST = 12
}
