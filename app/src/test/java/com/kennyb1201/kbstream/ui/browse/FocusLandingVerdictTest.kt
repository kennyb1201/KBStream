package com.kennyb1201.kbstream.ui.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict every driven focus landing shares (sweep 7, §4.2).
 *
 * The two standards that had drifted apart: the profile picker judged a landing
 * by OBSERVED focus state (each tile reports its own), while the Continue
 * Watching head landing and the Detail hand-backs read `requestFocus()`'s return
 * value and called a single true a delivery. An unattached node answers false,
 * so the common miss was covered - but a node that ACCEPTED and was disposed a
 * frame later (a rail rebuilding under the delivery) also answers true, and the
 * log said "landed" while the viewer had focus nowhere.
 *
 * Compose exposes no "is this requester focused now" query, so the second
 * observation is a second ask: this table is that rule.
 */
class FocusLandingVerdictTest {

    @Test
    fun `one acceptance is not a landing`() {
        val verdict = FocusLandingVerdict()
        assertFalse(
            "an accepted request that has not been confirmed must not count as " +
                "delivered - that single answer is the shape the audit could not " +
                "tell from a node being disposed",
            verdict.accepted(asked = true)
        )
    }

    @Test
    fun `two consecutive acceptances land`() {
        val verdict = FocusLandingVerdict()
        assertFalse(verdict.accepted(asked = true))
        assertTrue(
            "asked again on the next attempt, the same target still takes the " +
                "focus: that is the landing",
            verdict.accepted(asked = true)
        )
    }

    @Test
    fun `a miss between two acceptances resets the streak`() {
        val verdict = FocusLandingVerdict()
        assertFalse(verdict.accepted(asked = true))
        assertFalse(
            "the target went away on the frame between, so its earlier answer " +
                "covers nothing",
            verdict.accepted(asked = false)
        )
        assertFalse(
            "and the next acceptance is only the first of a new streak",
            verdict.accepted(asked = true)
        )
        assertTrue(verdict.accepted(asked = true))
    }

    @Test
    fun `a target that never answers never lands`() {
        val verdict = FocusLandingVerdict()
        repeat(12) {
            assertFalse(verdict.accepted(asked = false))
        }
    }
}
