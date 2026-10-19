package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget that exists so Trakt never repeats MDBList's 40-attempt storm.
 *
 * A replaceable clock is the whole point of the test: the window and the
 * Retry-After park are time behaviour, and pretending the clock moved is the
 * only way to pin them without sleeping.
 */
class TraktRateGuardTest {

    private var now = 1_000_000L

    private fun guard(max: Int = 3, windowMs: Long = 60_000L) =
        TraktRateGuard(maxRequestsInWindow = max, windowMs = windowMs) { now }

    @Test
    fun `the budget is spent and then refuses`() {
        val guard = guard(max = 3)

        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        assertFalse("a fourth call is over budget", guard.tryAcquire())
        assertEquals(3, guard.spentInWindow())
    }

    @Test
    fun `the window empties as time passes`() {
        val guard = guard(max = 2, windowMs = 60_000L)

        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())

        now += 60_000L
        assertTrue("the window has moved past the first two", guard.tryAcquire())
    }

    @Test
    fun `a 429 parks every caller until Retry-After passes`() {
        val guard = guard(max = 10)

        guard.noteRetryAfter(30L)
        assertTrue(guard.isBlocked())
        assertEquals(30_000L, guard.blockedForMs())
        assertFalse(guard.tryAcquire())

        now += 30_000L
        assertFalse("the pause is over", guard.isBlocked())
        assertTrue(guard.tryAcquire())
    }

    @Test
    fun `a long Retry-After is never shortened by a later short one`() {
        val guard = guard(max = 10)

        guard.noteRetryAfter(120L)
        now += 10_000L
        guard.noteRetryAfter(5L)

        assertEquals(
            "the longer pause still stands",
            110_000L,
            guard.blockedForMs()
        )
    }
}
