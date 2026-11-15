package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a second press does to the move in flight (spec 2026-11-15, third round).
 *
 * The field: DOWN from the keywords row never reached the episode section, and
 * build 329's paste shows the move named, started, then cancelled - five times.
 * The move into that section asks its season chip a frame at a time, so pressing
 * again inside that window cancelled the move and started the same one over. The
 * cancel is right for a reversal and wrong for a repeat, and this is that
 * distinction, as a rule.
 */
class DetailFocusMoveRulesTest {

    @Test
    fun `the first press starts a move`() {
        assertEquals(
            DetailFocusMoveRules.Decision.LAUNCH,
            DetailFocusMoveRules.decide(
                inFlight = false,
                inFlightDirection = null,
                press = DetailFocusDirection.DOWN
            )
        )
    }

    @Test
    fun `a repeat press coalesces instead of cancelling`() {
        // THE FIX: the move already heading down is the move this press wants.
        DetailFocusDirection.entries.forEach { direction ->
            assertEquals(
                "a second $direction while a $direction is running",
                DetailFocusMoveRules.Decision.COALESCE,
                DetailFocusMoveRules.decide(
                    inFlight = true,
                    inFlightDirection = direction,
                    press = direction
                )
            )
        }
    }

    @Test
    fun `a reversal still cancels - the design reason the cancel exists`() {
        assertEquals(
            "UP while a DOWN is retrying must not be snapped back down",
            DetailFocusMoveRules.Decision.CANCEL_AND_LAUNCH,
            DetailFocusMoveRules.decide(
                inFlight = true,
                inFlightDirection = DetailFocusDirection.DOWN,
                press = DetailFocusDirection.UP
            )
        )
        assertEquals(
            DetailFocusMoveRules.Decision.CANCEL_AND_LAUNCH,
            DetailFocusMoveRules.decide(
                inFlight = true,
                inFlightDirection = DetailFocusDirection.UP,
                press = DetailFocusDirection.DOWN
            )
        )
    }

    @Test
    fun `a press after the move has landed is a new move`() {
        // `inFlight` is the job's liveness, so a landed move leaves nothing to
        // coalesce into: the viewer is on the section now and wants the next row.
        assertEquals(
            DetailFocusMoveRules.Decision.LAUNCH,
            DetailFocusMoveRules.decide(
                inFlight = false,
                inFlightDirection = DetailFocusDirection.DOWN,
                press = DetailFocusDirection.DOWN
            )
        )
    }
}
