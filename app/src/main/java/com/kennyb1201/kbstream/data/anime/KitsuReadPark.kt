package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.data.network.NoParkUntilStore
import com.kennyb1201.kbstream.data.network.ParkUntilStore
import com.kennyb1201.kbstream.data.network.retryAfterParkMs

/**
 * Kitsu's READ park (spec 2026-11-20).
 *
 * [KitsuClient]'s single `get()` choke point spaced its calls a second apart and
 * handled a 429 not at all: a 429 came back as null like any other failure, and
 * the next caller retried a second later - a 1/s retry loop through a limit that
 * may last a minute or an hour. The writes ([KitsuLibrary]) have parked since
 * day one; the reads now park the same way.
 *
 * Its own guard rather than the write leg's: a 429 on the public read bucket
 * says nothing about the authenticated write bucket, and parking one must not
 * silence the other.
 *
 * Kitsu publishes no rate headers, so in practice [DEFAULT_PARK_MS] applies; the
 * header is still read because a `Retry-After` that does arrive is the server's
 * own instruction and outranks the default.
 *
 * Process-wide, and persisted: these are unauthenticated reads, so per-device is
 * the best keying available, and a restart must not re-fire into the limit.
 */
internal class KitsuReadGuard(
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

    /** Honor a 429's `Retry-After` (Kitsu sends none), and persist the park. */
    @Synchronized
    fun noteRetryAfter(retryAfterHeader: String?) {
        val until = clock() + retryAfterParkMs(retryAfterHeader, DEFAULT_PARK_MS)
        if (until > parkedUntilMs) {
            parkedUntilMs = until
            parkStore.writeParkUntilMs(until)
        }
    }

    /** Milliseconds the read leg is parked for; 0 when a read may go now. */
    @Synchronized
    fun parkedForMs(): Long = (parkedUntilMs - clock()).coerceAtLeast(0L)

    /** Test hook: forgets the park and stops writing to any store. */
    @Synchronized
    fun resetForTest() {
        parkedUntilMs = 0L
        parkStore = NoParkUntilStore
    }

    companion object {
        /** A minute: the window Kitsu's own clients are built around. */
        const val DEFAULT_PARK_MS = 60_000L
    }
}

/** The read leg's single guard instance (see [KitsuReadGuard]). */
internal val kitsuReadPark = KitsuReadGuard()

/** Milliseconds Kitsu's read leg is parked; 0 when a read may go now. */
internal fun kitsuReadParkMs(): Long = kitsuReadPark.parkedForMs()
