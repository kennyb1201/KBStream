package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Simkl ratings leg's own guard, in the TraktRateGuard test's shape.
 *
 * Trakt has a budget object because its authenticated limit is documented; no
 * such limit was verified for Simkl, so this is deliberately not a budget claim -
 * it is a politeness floor (one ratings call per two seconds at most) plus the
 * same `Retry-After` rule Trakt gets. It is local to the ratings leg on purpose:
 * no existing Simkl path (history, watchlist, scrobbles) changes its timing.
 *
 * The rule that matters most is what a refusal does NOT do: a refused call spends
 * nothing, so being held back can never itself push the next call further out -
 * the failure mode of a guard that timestamps its refusals is a leg that starves.
 */
class SimklRatingGuardTest {

    private var now = 1_000_000L

    private fun guard() = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now })

    /** A store with one Long in it, the shape the prefs-backed one has. */
    private class FakeParkStore(var untilMs: Long = 0L) : SimklParkStore {
        var writes = 0

        override fun readParkUntilMs(): Long = untilMs

        override fun writeParkUntilMs(untilMs: Long) {
            this.untilMs = untilMs
            writes++
        }
    }

    @Test
    fun `the first call goes and the next waits out the spacing floor`() {
        val guard = guard()

        assertTrue(guard.tryAcquire())

        now += 1_000
        assertFalse("inside the floor", guard.tryAcquire())
        assertEquals(1_000L, guard.blockedForMs())
        assertTrue(guard.isBlocked())

        now += 1_000
        assertTrue("two seconds on, a call may go", guard.tryAcquire())
        // The window restarts on the call that just went, so the leg is held for
        // the full spacing again - what the guard reports is time until the NEXT
        // call may go, which is what the caller waits out.
        assertEquals(2_000L, guard.blockedForMs())
    }

    @Test
    fun `a refused call spends nothing, so it cannot push the next one out`() {
        val guard = guard()
        assertTrue(guard.tryAcquire())

        now += 500
        assertFalse(guard.tryAcquire())
        now += 500
        assertFalse("a refusal is not a call: the window still runs from the real one", guard.tryAcquire())

        now += 1_000
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `a 429 parks the leg for its Retry-After and the wait is honoured`() {
        val guard = guard()
        assertTrue(guard.tryAcquire())

        guard.noteRetryAfter(30)
        assertTrue(guard.isBlocked())
        assertEquals(30_000L, guard.blockedForMs())

        now += 29_999
        assertFalse(guard.tryAcquire())
        now += 1
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `a 429 without a usable header waits the default minute`() {
        val guard = guard()
        guard.noteRetryAfter(null)
        assertEquals(60_000L, guard.blockedForMs())
        assertEquals(60L, SimklRatingGuard.DEFAULT_RETRY_AFTER_SECONDS)

        val nan = guard()
        nan.noteRetryAfter(0)
        assertEquals("a zero is not a pause", 1_000L, nan.blockedForMs())
    }

    @Test
    fun `a longer park is never shortened by a later, shorter one`() {
        val guard = guard()
        guard.noteRetryAfter(120)
        guard.noteRetryAfter(5)
        assertEquals(120_000L, guard.blockedForMs())
    }

    // ── F1 (2026-11-19, Simkl quota): the park outlives the process ─────

    @Test
    fun `a park recorded on one guard holds the next one over the same store`() {
        val store = FakeParkStore()
        val before = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now }, parkStore = store)
        before.noteRetryAfter(965 * 60)

        val restored = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now }, parkStore = store)
        assertTrue(
            "a restart during a 16-hour park must not spend a quota call re-learning it",
            restored.isBlocked()
        )
        assertEquals(965 * 60_000L, restored.parkedForMs())
        assertFalse(restored.tryAcquire())
    }

    @Test
    fun `a persisted park in the past is no park`() {
        val store = FakeParkStore(untilMs = now + 10_000L)
        val guard = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now }, parkStore = store)
        assertEquals(10_000L, guard.parkedForMs())

        now += 10_000L
        assertFalse("the store is not rewritten just because time passed", guard.isBlocked())
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `the spacing floor does not persist`() {
        val store = FakeParkStore()
        val first = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now }, parkStore = store)
        assertTrue(first.tryAcquire())
        assertFalse("inside the floor", first.tryAcquire())

        val next = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now }, parkStore = store)
        assertTrue(
            "a fresh process is not inside a burst window it never opened - a floor " +
                "that survives a restart holds back a leg that is not bursting",
            next.tryAcquire()
        )
        assertEquals("and no park was ever stored by a plain call", 0, store.writes)
    }

    @Test
    fun `the write happens once per 429, not once per call`() {
        val store = FakeParkStore()
        val guard = SimklRatingGuard(minSpacingMs = 2_000L, clock = { now }, parkStore = store)
        guard.tryAcquire()
        guard.tryAcquire()
        assertEquals("a call is not a park", 0, store.writes)

        guard.noteRetryAfter(60)
        assertEquals(1, store.writes)
        assertEquals(now + 60_000L, store.untilMs)
    }

    @Test
    fun `a store attached later still honours a park already in it`() {
        val store = FakeParkStore(untilMs = now + 30_000L)
        val guard = guard()
        assertFalse("nothing installed yet", guard.isBlocked())

        guard.attachParkStore(store)
        assertEquals(30_000L, guard.parkedForMs())
    }

    @Test
    fun `the park names its quota class, and the longer park keeps its own`() {
        val guard = guard()
        assertEquals("no park, no class", null, guard.parkLimitLabel())

        guard.noteRetryAfter(965 * 60, limitLabel = "user_limit_exceeded")
        assertEquals("user_limit_exceeded", guard.parkLimitLabel())

        guard.noteRetryAfter(5, limitLabel = "app_limit_exceeded")
        assertEquals(
            "a shorter park cannot relabel the one that is actually holding the leg",
            "user_limit_exceeded",
            guard.parkLimitLabel()
        )

        now += 965 * 60_000L
        assertEquals("an expired park names nothing", null, guard.parkLimitLabel())
    }

    @Test
    fun `the longer of the park and the spacing window is what holds the leg`() {
        val guard = guard()
        guard.noteRetryAfter(10)

        now += 10_000
        assertTrue("the park has run out", guard.tryAcquire())
        assertEquals(
            "now the spacing window governs, and it is the only wait left",
            2_000L,
            guard.blockedForMs()
        )
    }
}
