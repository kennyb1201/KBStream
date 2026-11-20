package com.kennyb1201.kbstream.data.network

import com.kennyb1201.kbstream.data.anime.KitsuReadGuard
import com.kennyb1201.kbstream.data.bingecat.BingeCatReadGuard
import com.kennyb1201.kbstream.data.bingecat.BingeCatWriteGuard
import com.kennyb1201.kbstream.data.mal.MalRateGuard
import com.kennyb1201.kbstream.data.trakt.TraktRateGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified 429 rule set (spec 2026-11-20), pinned without a device.
 *
 * Each of these was a real gap: BingeCat's reads had no park at all, Kitsu's
 * reads had none, MAL's "park" was a log line, and every other leg's park died
 * with the process. The arithmetic that decides how long a rate-limited leg
 * stays quiet is the thing that turns one 429 into a polling loop when it is
 * wrong, so it is tested here rather than by reading source text.
 */
class RateLimitParksTest {

    /** A clock the test moves by hand. */
    private class FakeClock(var nowMs: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    private class FakeStore(var untilMs: Long = 0L) : ParkUntilStore {
        var writes: Int = 0

        override fun readParkUntilMs(): Long = untilMs

        override fun writeParkUntilMs(untilMs: Long) {
            this.untilMs = untilMs
            writes += 1
        }
    }

    // ── R1: the header is the whole wait ────────────────────────────────

    @Test
    fun `a long Retry-After is never clamped`() {
        // Simkl's lesson, applied everywhere: a daily quota answers in hours.
        assertEquals(29_460_000L, retryAfterParkMs("29460"))
        assertEquals(16L * 60L * 60L * 1_000L, retryAfterParkMs("57600"))
    }

    @Test
    fun `a missing or unreadable header means a minute`() {
        assertEquals(RATE_LIMIT_DEFAULT_PARK_MS, retryAfterParkMs(null))
        assertEquals(RATE_LIMIT_DEFAULT_PARK_MS, retryAfterParkMs(""))
        assertEquals(RATE_LIMIT_DEFAULT_PARK_MS, retryAfterParkMs("soon"))
        assertEquals(RATE_LIMIT_DEFAULT_PARK_MS, retryAfterParkMs("0"))
        assertEquals(RATE_LIMIT_DEFAULT_PARK_MS, retryAfterParkMs("-30"))
    }

    @Test
    fun `an absurdly short header is lifted to the floor`() {
        assertEquals(RATE_LIMIT_MIN_PARK_MS, retryAfterParkMs("1"))
    }

    // ── BingeCat reads: the gap this pass exists for ────────────────────

    @Test
    fun `a BingeCat read 429 blocks the next read until the park expires`() {
        val clock = FakeClock()
        val guard = BingeCatReadGuard(clock)

        assertEquals(0L, guard.parkedForMs())

        guard.noteRetryAfter(30L)
        assertEquals(30_000L, guard.parkedForMs())

        clock.nowMs += 29_999L
        assertTrue("still parked one millisecond before the deadline", guard.parkedForMs() > 0L)

        clock.nowMs += 1L
        assertEquals("the park frees itself at the deadline", 0L, guard.parkedForMs())
    }

    @Test
    fun `a BingeCat read 429 without a header parks for a minute`() {
        val clock = FakeClock()
        val guard = BingeCatReadGuard(clock)

        guard.noteRetryAfter(null)

        assertEquals(60_000L, guard.parkedForMs())
    }

    @Test
    fun `a shorter second park does not shorten a longer one`() {
        val clock = FakeClock()
        val guard = BingeCatReadGuard(clock)

        guard.noteRetryAfter(3_600L)
        guard.noteRetryAfter(5L)

        assertEquals(3_600_000L, guard.parkedForMs())
    }

    // ── R3: the park survives a restart ─────────────────────────────────

    @Test
    fun `BingeCat's read park is restored by the next process`() {
        val clock = FakeClock()
        val store = FakeStore()
        val before = BingeCatReadGuard(clock)
        before.attachParkStore(store)

        before.noteRetryAfter(900L)
        assertEquals(1, store.writes)
        assertEquals(clock.nowMs + 900_000L, store.untilMs)

        // A new guard is what a relaunch builds; it must start parked.
        val after = BingeCatReadGuard(clock, store)
        assertEquals(900_000L, after.parkedForMs())
    }

    @Test
    fun `an expired persisted park is simply no park`() {
        val clock = FakeClock()
        val store = FakeStore(untilMs = clock.nowMs - 1L)

        val guard = BingeCatReadGuard(clock, store)

        assertEquals(0L, guard.parkedForMs())
    }

    @Test
    fun `BingeCat's write park is restored by the next process`() {
        val clock = FakeClock()
        val store = FakeStore()
        val before = BingeCatWriteGuard(clock)
        before.attachParkStore(store)
        before.noteRetryAfter(120L)

        assertEquals(120_000L, BingeCatWriteGuard(clock, store).parkedForMs())
    }

    @Test
    fun `Trakt's park is restored and its window is not`() {
        val clock = FakeClock()
        val store = FakeStore()
        val before = TraktRateGuard(clock = clock)
        before.attachParkStore(store)
        before.noteRetryAfter(120L)

        val after = TraktRateGuard(clock = clock, parkStore = store)

        assertTrue(after.isBlocked())
        assertEquals(120_000L, after.blockedForMs())
        // The budget's stamps are deliberately NOT restored: a window that
        // outlived the process would hold back a leg that is no longer spending.
        assertEquals(0, after.spentInWindow())
    }

    // ── Kitsu reads: the verified gap ───────────────────────────────────

    @Test
    fun `a Kitsu read 429 parks for a minute and is restored`() {
        val clock = FakeClock()
        val store = FakeStore()
        val guard = KitsuReadGuard(clock)
        guard.attachParkStore(store)

        guard.noteRetryAfter(null)
        assertEquals(60_000L, guard.parkedForMs())
        assertEquals(clock.nowMs + 60_000L, store.untilMs)

        assertEquals(60_000L, KitsuReadGuard(clock, store).parkedForMs())
    }

    @Test
    fun `a Kitsu Retry-After outranks the default`() {
        val clock = FakeClock()
        val guard = KitsuReadGuard(clock)

        guard.noteRetryAfter("300")

        assertEquals(300_000L, guard.parkedForMs())
    }

    // ── MAL: the park the client doc claimed and the code did not have ──

    @Test
    fun `a MAL 429 parks both arms for its header`() {
        val clock = FakeClock()
        val store = FakeStore()
        val guard = MalRateGuard(clock)
        guard.attachParkStore(store)

        guard.noteRetryAfter("90")

        assertEquals(90_000L, guard.parkedForMs())
        assertEquals(90_000L, MalRateGuard(clock, store).parkedForMs())
    }

    @Test
    fun `a MAL 429 without a header parks for a minute`() {
        val guard = MalRateGuard(FakeClock())

        guard.noteRetryAfter(null)

        assertEquals(60_000L, guard.parkedForMs())
    }

    // ── The no-op store keeps a guard working without persistence ───────

    @Test
    fun `a guard with no store installed still parks in memory`() {
        val clock = FakeClock()
        val guard = BingeCatReadGuard(clock)

        guard.noteRetryAfter(10L)

        assertEquals(10_000L, guard.parkedForMs())
        assertFalse(guard.parkedForMs() == 0L)

        guard.resetForTest()
        assertEquals(0L, guard.parkedForMs())
    }
}
