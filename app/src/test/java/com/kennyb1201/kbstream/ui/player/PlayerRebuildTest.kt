package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuild arithmetic that can be decided without a TV in the room: the
 * decoder grace period a switch still owes, and whether a screen coming back
 * from the TV's launcher owes a rebuild at all.
 *
 * The stakes of the first are a viewer-visible failure with a misleading name:
 * ask for a video decoder before the outgoing one has given its buffers back
 * and the box answers OMX_ErrorInsufficientResources (0x80001000), which the
 * app reports as "this TV has run out of video decoder resources" while the
 * next ranked source — the smaller one the same box then plays — is hunted down
 * instead. Every case below is one of the ways the wait could come out short.
 *
 * The stakes of the second are the report that started it: a viewer pressed the
 * remote's Home button mid-film, came back, and found a screen whose play and
 * restart presses did nothing at all until a source switch rebuilt the player
 * behind them.
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

    // --- Coming back from a stop -------------------------------------------

    /** The report: stopped with a player up, returned to a screen still here. */
    private fun owes(
        finishing: Boolean = false,
        destroyed: Boolean = false,
        tornDownAtStop: Boolean = true,
        playerPresent: Boolean = false,
        handingOver: Boolean = false
    ) = shouldRebuildAfterStop(
        finishing = finishing,
        destroyed = destroyed,
        tornDownAtStop = tornDownAtStop,
        playerPresent = playerPresent,
        handingOver = handingOver
    )

    @Test
    fun `a stop that took the player rebuilds it on the way back`() {
        assertTrue(owes())
    }

    @Test
    fun `the launch itself owes nothing - onCreate is already building a player`() {
        assertFalse(owes(tornDownAtStop = false))
        assertFalse(owes(tornDownAtStop = false, playerPresent = true))
    }

    @Test
    fun `a screen on its way out never rebuilds`() {
        assertFalse(owes(finishing = true))
        assertFalse(owes(destroyed = true))
    }

    @Test
    fun `a player that is still there is left alone`() {
        // Picture-in-Picture stops nothing, so onStart can run with the session
        // intact; rebuilding over it would hand the one 4K decoder this box
        // allows to a second player.
        assertFalse(owes(playerPresent = true))
    }

    @Test
    fun `a session continued in another engine is never rebuilt here`() {
        // The MPV switch, an installed external player and the next-episode
        // chain each open the file and play it themselves.
        assertFalse(owes(handingOver = true))
    }

    @Test
    fun `only the stop marker separates a launch from a return`() {
        // Every other input is held constant: this is the flag onStop sets and
        // the rebuild clears, so a return that lost it would silently go back
        // to being a dead screen.
        assertFalse(owes(tornDownAtStop = false))
        assertTrue(owes(tornDownAtStop = true))
    }
}
