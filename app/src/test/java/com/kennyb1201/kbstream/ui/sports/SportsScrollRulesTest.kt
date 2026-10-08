package com.kennyb1201.kbstream.ui.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distance one D-pad press scrolls the hub when there is nothing to focus.
 *
 * This is the rule behind "it won't scroll down into the games": a section of
 * cards the playlist cannot carry has no focus target in it, so the press has
 * to become a scroll instead. The distance is the part worth pinning - too
 * small and the press reads as broken, too large and a card is skipped that was
 * never on screen to be focused.
 */
class SportsScrollRulesTest {

    @Test
    fun `a press moves by the row it is reading`() {
        // The normal case: the last visible item is a row of cards.
        assertEquals(200, sportsScrollStep(rowHeight = 200, viewportHeight = 640))
    }

    @Test
    fun `a press never moves less than a quarter of the screen`() {
        // The last visible item can be a section heading, and 40dp of a 640dp
        // list is a press that looks like it did nothing at all.
        assertEquals(160, sportsScrollStep(rowHeight = 40, viewportHeight = 640))
    }

    @Test
    fun `a press never skips more than the screen holds`() {
        // A tournament card can be taller than the list; scrolling by it whole
        // would put its own middle past the viewport.
        assertEquals(640, sportsScrollStep(rowHeight = 1_200, viewportHeight = 640))
    }

    @Test
    fun `an unknown row height scrolls a screenful`() {
        // Before the first layout pass reports an item, the only honest step is
        // the viewport itself.
        assertEquals(640, sportsScrollStep(rowHeight = 0, viewportHeight = 640))
        assertEquals(640, sportsScrollStep(rowHeight = -5, viewportHeight = 640))
    }

    @Test
    fun `a viewport too small to measure has no step`() {
        assertEquals(0, sportsScrollStep(rowHeight = 200, viewportHeight = 0))
        assertEquals(0, sportsScrollStep(rowHeight = 200, viewportHeight = -10))
    }

    @Test
    fun `the bounds hold at every viewport the rule can see`() {
        // coerceIn throws if its range is inverted, so the quarter-floor and the
        // viewport-ceiling have to stay ordered for every input - including the
        // single-pixel viewport a collapsing layout reports.
        for (viewport in 1..2_000) {
            for (row in listOf(-1, 0, 1, 40, viewport / 2, viewport, viewport + 500)) {
                val step = sportsScrollStep(rowHeight = row, viewportHeight = viewport)
                assertTrue(
                    "step $step out of bounds for row=$row viewport=$viewport",
                    step in 1..viewport
                )
            }
        }
    }
}
