package com.kennyb1201.kbstream.ui.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports Hub clock's cadence: one tick per minute boundary, never a fixed
 * sleep from composition and never a per-second poll.
 */
class SportsClockRulesTest {

    @Test
    fun `a time inside a minute waits exactly to the boundary`() {
        assertEquals(1L, millisUntilNextMinute(59_999L))
        assertEquals(30_000L, millisUntilNextMinute(30_000L))
        assertEquals(59_999L, millisUntilNextMinute(1L))
    }

    @Test
    fun `a boundary waits a whole minute rather than ticking twice`() {
        assertEquals(60_000L, millisUntilNextMinute(0L))
        assertEquals(60_000L, millisUntilNextMinute(60_000L))
        assertEquals(60_000L, millisUntilNextMinute(120_000L))
    }

    @Test
    fun `the answer is always a positive delay no longer than a minute`() {
        listOf(0L, 1L, 999L, 61_000L, 3_599_999L, 1_700_000_123_456L).forEach { now ->
            val wait = millisUntilNextMinute(now)
            assertTrue(
                "wait=$wait for now=$now",
                wait in 1L..SPORTS_CLOCK_MINUTE_MS
            )
        }
    }
}
