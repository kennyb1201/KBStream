package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season strip's D-pad edges (spec 2026-11-14, F2).
 *
 * Field report: UP reached the strip from the episode list, and one more
 * LEFT/RIGHT at its end left the panel - focus landed on the cast row of the
 * overlay behind it, and the next OK opened an actor page mid-episode. The strip
 * had one wired edge (DOWN); every other direction fell to focus search.
 *
 * "Hold at the end" is the chosen stay-behaviour, identical in both engines: a
 * wrap would move the ring to the far end on a press the viewer read as "nothing
 * further", and the trap would be harder to state. These tests are the whole
 * rule - one comparison per direction.
 */
class PickerDpadRulesTest {

    private val seasons = 4 // positions 0..3, lastIndex 3

    @Test
    fun `LEFT on the first chip is consumed, and nowhere else`() {
        assertTrue(PickerDpadRules.holdsWithinStrip(StripDirection.LEFT, 0, seasons - 1))
        assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.LEFT, 1, seasons - 1))
        assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.LEFT, seasons - 1, seasons - 1))
    }

    @Test
    fun `RIGHT on the last chip is consumed, and nowhere else`() {
        assertTrue(PickerDpadRules.holdsWithinStrip(StripDirection.RIGHT, seasons - 1, seasons - 1))
        assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.RIGHT, 0, seasons - 1))
        assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.RIGHT, seasons - 2, seasons - 1))
    }

    @Test
    fun `UP never leaves the strip - there is nothing above it in the picker`() {
        (0 until seasons).forEach { position ->
            assertTrue(
                "chip $position must keep the D-pad on UP",
                PickerDpadRules.holdsWithinStrip(StripDirection.UP, position, seasons - 1)
            )
        }
    }

    @Test
    fun `DOWN is the strip's one real edge, so it is never consumed`() {
        (0 until seasons).forEach { position ->
            assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.DOWN, position, seasons - 1))
        }
        assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.OTHER, 0, seasons - 1))
    }

    @Test
    fun `a one-season strip holds both horizontal directions`() {
        assertTrue(PickerDpadRules.holdsWithinStrip(StripDirection.LEFT, 0, 0))
        assertTrue(PickerDpadRules.holdsWithinStrip(StripDirection.RIGHT, 0, 0))
        assertFalse(PickerDpadRules.holdsWithinStrip(StripDirection.DOWN, 0, 0))
    }
}
