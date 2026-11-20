package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.data.network.NoParkUntilStore
import com.kennyb1201.kbstream.data.network.ParkUntilStore

/**
 * The write leg's 429 park.
 *
 * Process-wide and not a field on the repository: the repository is constructed
 * per screen, and a park that reset with each construction would not exist.
 * Deliberately its own guard rather than Simkl's: a 429 on BingeCat's bucket
 * says nothing about Simkl's, and parking one leg must not silence the other.
 *
 * Its own file so the guard is a plain JVM object with no Moshi, no Retrofit and
 * no config in front of it - the arithmetic is pinned by a test, not by reading
 * source text. Since 2026-11-20 the park is also persisted (R3): before that a
 * restart re-learned the 429 by sending a write the server had already refused.
 */
internal class BingeCatWriteGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private var parkStore: ParkUntilStore = NoParkUntilStore
) {

    @Volatile
    private var blockedUntilMs = parkStore.readParkUntilMs()

    /**
     * Points the running guard at the persisted park (R3).
     *
     * A store with a park in the future is honoured; one with an expired park is
     * simply no park, because the arithmetic already treats the past as free.
     */
    @Synchronized
    fun attachParkStore(store: ParkUntilStore) {
        parkStore = store
        blockedUntilMs = maxOf(blockedUntilMs, store.readParkUntilMs())
    }

    /** Honor a 429's `Retry-After` (seconds), or [DEFAULT_RETRY_AFTER_SECONDS]. */
    @Synchronized
    fun noteRetryAfter(seconds: Long?) {
        val waitMs = (seconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1L) * 1_000L
        blockedUntilMs = maxOf(blockedUntilMs, clock() + waitMs)
        parkStore.writeParkUntilMs(blockedUntilMs)
    }

    /** Milliseconds the leg is parked for; 0 when a write may go now. */
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

/** The write leg's single guard instance (see [BingeCatWriteGuard]). */
internal val bingeCatWriteGuard = BingeCatWriteGuard()

/** Milliseconds the BingeCat write leg is parked by a 429; 0 = may send now. */
internal fun bingeCatWriteParkMs(): Long = bingeCatWriteGuard.parkedForMs()

/**
 * Applies the 429 rule to one write response: park for `Retry-After` (a minute
 * when the header is absent) and answer true, so the caller returns its own
 * failure but the NEXT write is held back.
 */
internal fun noteBingeCatWriteRateLimit(code: Int, retryAfterHeader: String?): Boolean {
    if (code != 429) return false
    val seconds = retryAfterHeader?.trim()?.toLongOrNull()
    bingeCatWriteGuard.noteRetryAfter(seconds)
    return true
}
