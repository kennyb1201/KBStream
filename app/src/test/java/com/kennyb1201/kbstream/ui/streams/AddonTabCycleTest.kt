package com.kennyb1201.kbstream.ui.streams

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The LEFT/RIGHT tab ring (spec 2026-11-12), as arithmetic.
 *
 * Row order is All first (tab -1), then each add-on chip in the order the row
 * draws them (0..n-1). RIGHT walks forward, LEFT backward, and both wrap: from
 * the last add-on RIGHT comes back to All, and from All LEFT goes to the last
 * add-on. With one add-on there is no ring, so the selection cannot move.
 */
class AddonTabCycleTest {

    @Test
    fun `right walks All then each add-on in row order`() {
        // All -> first add-on -> second -> third
        assertEquals(0, cycleAddonTab(-1, +1, 3))
        assertEquals(1, cycleAddonTab(0, +1, 3))
        assertEquals(2, cycleAddonTab(1, +1, 3))
    }

    @Test
    fun `right from the last add-on wraps to All`() {
        assertEquals(-1, cycleAddonTab(2, +1, 3))
        assertEquals("and so does a two-add-on row", -1, cycleAddonTab(1, +1, 2))
    }

    @Test
    fun `left walks back and wraps from All to the last add-on`() {
        assertEquals(2, cycleAddonTab(-1, -1, 3))
        assertEquals(0, cycleAddonTab(1, -1, 3))
        assertEquals(-1, cycleAddonTab(0, -1, 3))
    }

    @Test
    fun `a full lap in either direction returns to where it started`() {
        var tab = -1
        repeat(4) { tab = cycleAddonTab(tab, +1, 3) }
        assertEquals("right, one lap of a 3-add-on row", -1, tab)
        repeat(4) { tab = cycleAddonTab(tab, -1, 3) }
        assertEquals(-1, tab)
    }

    @Test
    fun `no add-on or one add-on has no ring, so the keys cannot move the tab`() {
        assertEquals("no add-on answering", -1, cycleAddonTab(-1, +1, 0))
        assertEquals(-1, cycleAddonTab(-1, -1, 0))
        assertEquals("one add-on draws no tabs at all", -1, cycleAddonTab(-1, +1, 1))
        assertEquals(-1, cycleAddonTab(-1, -1, 1))
        // ...and an add-on already selected stays selected: the row is the
        // refresh control alone, so there is nothing to switch to.
        assertEquals(0, cycleAddonTab(0, +1, 1))
        assertEquals(0, cycleAddonTab(0, -1, 1))
    }

    @Test
    fun `refresh is never a stop, because it is not a tab`() {
        // Every value the ring can produce is All (-1) or a real add-on index;
        // there is no third kind of stop for REFRESH to occupy.
        val produced = mutableSetOf<Int>()
        for (start in -1..3) {
            for (direction in listOf(-1, +1)) {
                produced += cycleAddonTab(start, direction, 4)
            }
        }
        assertEquals(setOf(-1, 0, 1, 2, 3), produced)
    }
}
