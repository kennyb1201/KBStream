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
        assertEquals(0L, guard.blockedForMs())
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
