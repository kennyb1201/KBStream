package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.data.network.NoParkUntilStore
import com.kennyb1201.kbstream.data.network.ParkUntilStore
import com.kennyb1201.kbstream.data.network.retryAfterParkMs

/**
 * The READ leg's 429 park (spec 2026-11-20, BingeCat reads).
 *
 * The write leg has had one since step 2 ([BingeCatWriteGuard]); the reads had
 * none. `authorizedBody` answered a 429 by recording the word "429" and
 * returning null - and that is all it did. `snapshot()` revalidates once its
 * 30-minute TTL lapses, and a `refresh()` that fails does not advance
 * `refreshedAtMs`, so every caller after it (Home rails, Library, the picker,
 * taste adds) re-fired the full four-leg fan-out straight into the same limit:
 * the 0.9.407 paste shows `last_error=429` with `http.addon.bingecat n=24` in
 * about three minutes.
 *
 * So this is a separate guard, not a reuse of the write one: a 429 on the read
 * bucket says nothing about the write bucket, and the codebase's own rule is
 * that legs park independently. Same shape as the write guard, plus the park it
 * persists so a restart does not spend a fan-out re-learning it (R3).
 *
 * Process-wide: a repository is constructed per screen, and a park that reset
 * with each construction would not exist.
 */
internal class BingeCatReadGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private var parkStore: ParkUntilStore = NoParkUntilStore
) {

    @Volatile
    private var blockedUntilMs = parkStore.readParkUntilMs()

    /**
     * Points the running guard at the persisted park.
     *
     * Separate from the constructor because the guard is a process-wide value
     * created before an Android `Context` exists. A store with a park in the
     * future is honoured; one with an expired park is simply no park, because
     * the arithmetic already treats the past as free.
     */
    @Synchronized
    fun attachParkStore(store: ParkUntilStore) {
        parkStore = store
        blockedUntilMs = maxOf(blockedUntilMs, store.readParkUntilMs())
    }

    /**
     * Honor a 429's `Retry-After` (seconds), or [DEFAULT_RETRY_AFTER_SECONDS],
     * and persist it.
     *
     * R1: the header is the server's instruction and is never clamped below it -
     * BingeCat answers with an hours-long value when the account is out of
     * budget, and clamping that is exactly the polling loop this exists to stop.
     */
    @Synchronized
    fun noteRetryAfter(seconds: Long?) {
        val waitMs = (seconds?.takeIf { it > 0L } ?: DEFAULT_RETRY_AFTER_SECONDS) * 1_000L
        blockedUntilMs = maxOf(blockedUntilMs, clock() + waitMs)
        parkStore.writeParkUntilMs(blockedUntilMs)
    }

    /** Milliseconds the read leg is parked for; 0 when a read may go now. */
    @Synchronized
    fun parkedForMs(): Long = (blockedUntilMs - clock()).coerceAtLeast(0L)

    /** Test hook: forgets the park and stops writing to any store. */
    @Synchronized
    fun resetForTest() {
        blockedUntilMs = 0L
        parkStore = NoParkUntilStore
    }

    companion object {
        /** What a 429 without a usable `Retry-After` means (the Trakt/Simkl default). */
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}

/** The read leg's single guard instance (see [BingeCatReadGuard]). */
internal val bingeCatReadGuard = BingeCatReadGuard()

/** Milliseconds the BingeCat read leg is parked by a 429; 0 = may read now. */
internal fun bingeCatReadParkMs(): Long = bingeCatReadGuard.parkedForMs()

/**
 * Parks the read leg from one 429's `Retry-After` header.
 *
 * The header through [retryAfterParkMs] rather than a bare parse, so a header of
 * `0` or a negative value cannot shorten the wait to nothing.
 */
internal fun noteBingeCatReadRateLimit(
    retryAfterHeader: String?,
    nowMs: Long = System.currentTimeMillis()
): Long {
    val parkMs = retryAfterParkMs(retryAfterHeader)
    bingeCatReadGuard.noteRetryAfter(parkMs / 1_000L)
    return nowMs + parkMs
}
