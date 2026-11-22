package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's rail publish cadence (perf spec 2026-11-22, item 3).
 *
 * A cold load resolves 60-90 catalog rows, and each one used to republish the
 * whole rail list. These cases pin the two properties that make batching safe:
 * the first row still paints at once, and a rail held back by the cadence is
 * never lost.
 */
class RailStreamGateTest {

    private var clock = 0L
    private val published = mutableListOf<List<String>>()
    private val gate = RailStreamGate<String>(cadenceMs = 90L, now = { clock })

    private fun offer(rail: String): Boolean =
        gate.offer(rail) { batch -> published += batch }

    @Test
    fun `the first rail publishes at once, with no timer to wait out`() {
        assertTrue("the first offer publishes", offer("a"))
        assertEquals(listOf(listOf("a")), published)
    }

    @Test
    fun `rails inside the cadence ride the next publish, in order`() {
        offer("a")
        clock = 10L
        assertFalse("held back by the cadence", offer("b"))
        clock = 20L
        assertFalse("still inside the window", offer("c"))
        assertEquals("only the first rail has been published", 1, published.size)

        clock = 90L
        assertTrue("the window has elapsed", offer("d"))
        assertEquals(listOf("b", "c", "d"), published[1])
    }

    @Test
    fun `a held-back rail is never dropped`() {
        val rails = (1..40).map { "rail$it" }
        rails.forEachIndexed { index, rail ->
            clock = index * 7L
            offer(rail)
        }
        clock = 10_000L
        offer("last")
        assertEquals("every rail reaches the screen exactly once", rails + "last", published.flatten())
    }
}
