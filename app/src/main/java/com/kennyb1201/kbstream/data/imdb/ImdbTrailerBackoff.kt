package com.kennyb1201.kbstream.data.imdb

/**
 * How long the IMDb data service is left alone after it says no.
 *
 * A refusal (an HTTP error, or a GraphQL `errors` body) is about this client
 * identity and is held for half an hour; two transport failures in a row - which
 * is one dropped request plus one retry - pause for five minutes. Neither pause
 * takes the title page away, so a paused API never means a missing trailer, only
 * a slower one.
 *
 * Pure and clock-injected, so both pauses are pinned by a unit test rather than
 * by a wait.
 */
internal class ImdbApiBackoff {

    private var failures = 0
    private var pausedUntilMs = Long.MIN_VALUE

    @Synchronized
    fun allowed(nowMs: Long): Boolean = nowMs >= pausedUntilMs

    @Synchronized
    fun succeeded() {
        failures = 0
        pausedUntilMs = Long.MIN_VALUE
    }

    @Synchronized
    fun refused(nowMs: Long) {
        failures = 0
        pausedUntilMs = nowMs + REFUSAL_PAUSE_MS
    }

    @Synchronized
    fun failed(nowMs: Long) {
        if (++failures < FAILURES_BEFORE_PAUSE) return
        failures = 0
        pausedUntilMs = nowMs + FAILURE_PAUSE_MS
    }

    @Synchronized
    fun reset() {
        failures = 0
        pausedUntilMs = Long.MIN_VALUE
    }

    companion object {
        const val REFUSAL_PAUSE_MS = 30 * 60_000L
        const val FAILURE_PAUSE_MS = 5 * 60_000L
        const val FAILURES_BEFORE_PAUSE = 2
    }
}
