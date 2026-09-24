package com.kennyb1201.kbstream.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate decides whether a supplementary review source is asked at all.
 * Both failure directions are silent and expensive: a gate that never
 * unblocks quietly kills the feature for the rest of the session, and a gate
 * that never blocks keeps sending the same rejected request for every title
 * the user opens.
 */
class RejectionGateTest {

    private var now = 1_000L
    private val gate = RejectionGate(blockMs = 60_000L, now = { now })

    @Test
    fun `starts unblocked`() {
        assertFalse(gate.isBlocked())
    }

    @Test
    fun `the first rejection blocks and is reported`() {
        assertTrue(gate.recordRejection())
        assertTrue(gate.isBlocked())
    }

    @Test
    fun `later rejections inside the window are silent`() {
        assertTrue(gate.recordRejection())
        // The whole point of the return value: one log line per block, not one
        // per title.
        assertFalse(gate.recordRejection())
        assertFalse(gate.recordRejection())
        assertTrue(gate.isBlocked())
    }

    @Test
    fun `a later rejection does not extend the window`() {
        gate.recordRejection()
        now += 30_000L
        gate.recordRejection()
        // 89s after the first rejection: past the original 60s window even
        // though a second rejection arrived 30s in.
        now += 59_000L
        assertFalse(gate.isBlocked())
    }

    @Test
    fun `the block expires on its own`() {
        gate.recordRejection()
        now += 59_999L
        assertTrue(gate.isBlocked())
        now += 1L
        assertFalse(gate.isBlocked())
    }

    @Test
    fun `a rejection after expiry blocks afresh and reports again`() {
        gate.recordRejection()
        now += 60_000L
        assertTrue(gate.recordRejection())
        assertTrue(gate.isBlocked())
    }

    @Test
    fun `a success clears the block immediately`() {
        gate.recordRejection()
        gate.reset()
        assertFalse(gate.isBlocked())
        // And the next rejection is reported again, so a re-block is visible.
        assertTrue(gate.recordRejection())
    }

    @Test
    fun `a non-positive window is rejected at construction`() {
        val failure = runCatching { RejectionGate(blockMs = 0L) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "blockMs must be positive",
            failure?.message
        )
    }
}
