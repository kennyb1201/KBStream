package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press menu's vertical move (spec 2026-11-19, F1).
 *
 * The field bug was an ESCAPE, not a missed press: from the last row, Down moved
 * focus to the background card below the menu, because the move was a focus
 * SEARCH and a focusGroup has no edge to stop one at. So the property these pin
 * is deliberately NOT "the reported index is fresh" - the focus callback lags a
 * key repeat by design - but "the answer is always a row of this menu", which is
 * what the clamp gives.
 */
class MenuMoveTargetTest {

    @Test
    fun `a step from the middle moves one row`() {
        assertEquals(2, menuMoveTarget(currentIndex = 1, delta = 1, lastIndex = 4))
        assertEquals(0, menuMoveTarget(currentIndex = 1, delta = -1, lastIndex = 4))
    }

    @Test
    fun `the edges hold instead of leaving the menu`() {
        assertEquals(
            "Down from the last row stays on it - the reported escape",
            4,
            menuMoveTarget(currentIndex = 4, delta = 1, lastIndex = 4)
        )
        assertEquals(
            "Up from the first row stays on it - the symmetric case",
            0,
            menuMoveTarget(currentIndex = 0, delta = -1, lastIndex = 4)
        )
    }

    @Test
    fun `no row reported yet means row zero either way`() {
        assertEquals(
            "focus is still on the overlay anchor during the take",
            0,
            menuMoveTarget(currentIndex = -1, delta = 1, lastIndex = 3)
        )
        assertEquals(0, menuMoveTarget(currentIndex = -1, delta = -1, lastIndex = 3))
    }

    @Test
    fun `an empty menu has no target`() {
        // -1 is what makes the handler's getOrNull a no-op. The press is still
        // consumed, so an empty menu cannot leak it either.
        assertEquals(-1, menuMoveTarget(currentIndex = -1, delta = 1, lastIndex = -1))
        assertEquals(-1, menuMoveTarget(currentIndex = 0, delta = -1, lastIndex = -1))
    }

    @Test
    fun `a stale index still lands inside the menu`() {
        // A fast D-pad's callback is a frame behind: the reported row is one
        // lower than the row actually focused. Down then asks for a row focus is
        // already on (a duplicate press) and Up for the one behind it. Both are
        // rows; neither is the card behind the scrim.
        val lastIndex = 5
        for (reported in -1..lastIndex) {
            listOf(-1, 1).forEach { delta ->
                val target = menuMoveTarget(reported, delta, lastIndex)
                assertTrue(
                    "reported=$reported delta=$delta produced $target, " +
                        "outside 0..$lastIndex",
                    target in 0..lastIndex
                )
            }
        }
        assertEquals(
            "and a row list that SHRANK under a stale index is clamped too",
            5,
            menuMoveTarget(currentIndex = 7, delta = 1, lastIndex = 5)
        )
    }
}
