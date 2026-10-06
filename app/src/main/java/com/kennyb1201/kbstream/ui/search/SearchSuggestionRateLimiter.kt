package com.kennyb1201.kbstream.ui.search

/**
 * Bounds how often the exported global-search provider will spend a TMDB query
 * (HD-P2-6).
 *
 * The provider has to be exported for the launcher - another process - to reach
 * it at all, and `query` answers over binder by blocking a binder thread on
 * `runBlocking`. Every other app on the box can therefore call it, and a caller
 * that does not respect the launcher's own `searchSuggestThreshold` could pin
 * binder threads and burn the app's TMDB quota (the API key is the app's, so a
 * hammering caller is a free proxy for it).
 *
 * The two-character floor in the provider stops the cheapest version of this -
 * one-character prefixes - but nothing bounded the SUSTAINED rate. This is that
 * bound: a rolling window of at most [maxRequests] queries. A real viewer
 * typing a title stays far under it (the launcher asks once per keystroke after
 * the threshold, and each answer is capped at its own deadline); a caller that
 * does not get an empty cursor instead of a query.
 *
 * Pure and clock-injected - the caller passes the timestamp - so the window is
 * unit tested without a provider, a device or a sleeping test. Thread safe:
 * `query` runs on binder threads, so several calls can land at once.
 */
internal class SearchSuggestionRateLimiter(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {

    private val stamps = ArrayDeque<Long>()

    /** True when this call may spend a query; false when it must answer empty. */
    @Synchronized
    fun allow(nowMs: Long): Boolean {
        // Drop the stamps that have aged out of the window first, so a caller
        // that went quiet for a while starts fresh rather than carrying a debt.
        while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) {
            stamps.removeFirst()
        }
        if (stamps.size >= maxRequests) return false
        stamps.addLast(nowMs)
        return true
    }

    companion object {
        /**
         * Two per second sustained. Generous for a viewer typing, far below what
         * a programmatic caller would want.
         */
        const val DEFAULT_MAX_REQUESTS = 20

        const val DEFAULT_WINDOW_MS = 10_000L
    }
}
