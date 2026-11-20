package com.kennyb1201.kbstream.data.mal

import com.kennyb1201.kbstream.data.network.NoParkUntilStore
import com.kennyb1201.kbstream.data.network.ParkUntilStore
import com.kennyb1201.kbstream.data.network.retryAfterParkMs

/**
 * MAL's 429 park, for both arms (spec 2026-11-20).
 *
 * The reconciliation pass over the client found the audit's "429 park" was a log
 * line: `malWrite` answered a 429 with `Failed(429, "rate limited")` and
 * `executeGet` with `Missing`, and neither recorded a deadline, so the very next
 * title asked again. MAL's limit is one bucket per account across every call
 * (its own `X-RateLimit-*` headers are per client), which is why this is ONE
 * guard for reads and writes rather than the two the per-leg trackers get - the
 * legs are not rate-limited separately here, and inventing a split would park
 * fewer calls only because it would pretend they are.
 *
 * Persisted (R3): a restart must not re-learn the limit by firing again.
 */
internal class MalRateGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private var parkStore: ParkUntilStore = NoParkUntilStore
) {

    @Volatile
    private var parkedUntilMs = parkStore.readParkUntilMs()

    /** Points the running guard at the persisted park (see the Simkl pattern). */
    @Synchronized
    fun attachParkStore(store: ParkUntilStore) {
        parkStore = store
        parkedUntilMs = maxOf(parkedUntilMs, store.readParkUntilMs())
    }

    /** Honor a 429's `Retry-After`, or [DEFAULT_PARK_MS] when it carries none. */
    @Synchronized
    fun noteRetryAfter(retryAfterHeader: String?) {
        val until = clock() + retryAfterParkMs(retryAfterHeader, DEFAULT_PARK_MS)
        if (until > parkedUntilMs) {
            parkedUntilMs = until
            parkStore.writeParkUntilMs(until)
        }
    }

    /** Milliseconds the client is parked for; 0 when a call may go now. */
    @Synchronized
    fun parkedForMs(): Long = (parkedUntilMs - clock()).coerceAtLeast(0L)

    /** Test hook: forgets the park and stops writing to any store. */
    @Synchronized
    fun resetForTest() {
        parkedUntilMs = 0L
        parkStore = NoParkUntilStore
    }

    companion object {
        /** MAL's documented window is a minute; an unreadable header means this. */
        const val DEFAULT_PARK_MS = 60_000L
    }
}

/** The single guard instance for both MAL arms (see [MalRateGuard]). */
internal val malRateGuard = MalRateGuard()

/** Milliseconds MAL is parked for; 0 when a call may go now. */
internal fun malRateParkMs(): Long = malRateGuard.parkedForMs()
