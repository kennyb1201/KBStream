package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decoder grace period a rebuild still owes, which is the whole of the
 * switch-recovery arithmetic that can be decided without a TV in the room.
 *
 * The stakes are a viewer-visible failure with a misleading name: ask for a
 * video decoder before the outgoing one has given its buffers back and the
 * box answers OMX_ErrorInsufficientResources (0x80001000), which the app
 * reports as "this TV has run out of video decoder resources" while the next
 * ranked source — the smaller one the same box then plays — is hunted down
 * instead. Every case below is one of the ways the wait could come out short.
 */
class PlayerRebuildTest {

    private val released = 1_000_000L

    @Test
    fun `the first build of a session has nothing to wait for`() {
        assertEquals(0L, rebuildSettleRemainingMs(3_000L, releasedAtMs = 0L, nowMs = 5_000L))
    }

    @Test
    fun `a rebuild at the moment of the release waits the whole grace period`() {
        assertEquals(3_000L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released))
    }

    @Test
    fun `a second switch inside the window waits only the remainder`() {
        assertEquals(2_000L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released + 1_000L))
        assertEquals(1_000L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released + 2_000L))
        assertEquals(1L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released + 2_999L))
    }

    @Test
    fun `a rebuild once the window has passed does not wait at all`() {
        assertEquals(0L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released + 3_000L))
        assertEquals(0L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released + 90_000L))
    }

    @Test
    fun `an immediate rebuild never turns into a wait`() {
        assertEquals(0L, rebuildSettleRemainingMs(0L, releasedAtMs = released, nowMs = released))
        assertEquals(0L, rebuildSettleRemainingMs(-5L, releasedAtMs = released, nowMs = released))
    }

    @Test
    fun `a clock that has gone backwards waits the whole grace period, not none`() {
        assertEquals(3_000L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released - 1L))
        assertEquals(3_000L, rebuildSettleRemainingMs(3_000L, releasedAtMs = released, nowMs = released - 60_000L))
    }

    @Test
    fun `a shorter grace period is respected as asked`() {
        assertEquals(500L, rebuildSettleRemainingMs(500L, releasedAtMs = released, nowMs = released))
        assertEquals(0L, rebuildSettleRemainingMs(500L, releasedAtMs = released, nowMs = released + 500L))
    }
}
