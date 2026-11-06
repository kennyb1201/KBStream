package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pinned Library rail's order, as the Home manager's arrows write it.
 *
 * Pin order IS rail order on Home (the rails are drawn from the pins list at the
 * end of the column), so a move in the manager is a reorder of the pins blob and
 * nothing else. These rules are pure so the arrows' exactness - "the first rail
 * has no up", "the last has no down", "a press that changes nothing is not an
 * edit" - is pinned without a device.
 */
class LibraryHomeRailMoveRulesTest {

    private val a = LibraryHomeRailPin(source = "BINGECAT_LIST", id = "ref-a", name = "A")
    private val b = LibraryHomeRailPin(source = "BINGECAT_LIST", id = "ref-b", name = "B")
    private val c = LibraryHomeRailPin(source = "TRAKT_LIST", id = "my-list", name = "C")
    private val pins = listOf(a, b, c)

    @Test
    fun `a move rewrites the pin order one place at a time`() {
        assertEquals("A down one", listOf(b, a, c), movedPins(pins, a.key, +1))
        assertEquals("B up one", listOf(b, a, c), movedPins(pins, b.key, -1))
        assertEquals("C up one", listOf(a, c, b), movedPins(pins, c.key, -1))
        assertEquals("B down one", listOf(a, c, b), movedPins(pins, b.key, +1))
        assertSame("a zero step is not a move at all", pins, movedPins(pins, b.key, 0))
    }

    @Test
    fun `the ends are exact`() {
        assertEquals("very top", listOf(c, a, b), movedPins(pins, c.key, Int.MIN_VALUE))
        assertEquals("very bottom", listOf(b, c, a), movedPins(pins, a.key, Int.MAX_VALUE))
        // The manager draws an arrow only when pressing it would move the rail.
        assertFalse("the first pinned rail has no up", pinsMoveChangesOrder(pins, a.key, -1))
        assertFalse(
            "and nothing to gain from the very top",
            pinsMoveChangesOrder(pins, a.key, Int.MIN_VALUE)
        )
        assertTrue("but it can go down", pinsMoveChangesOrder(pins, a.key, +1))
        assertFalse("the last pinned rail has no down", pinsMoveChangesOrder(pins, c.key, +1))
        assertFalse("nor a bottom to reach", pinsMoveChangesOrder(pins, c.key, Int.MAX_VALUE))
        assertTrue("but it can go up", pinsMoveChangesOrder(pins, c.key, -1))
        assertTrue("the middle can go anywhere", pinsMoveChangesOrder(pins, b.key, Int.MIN_VALUE))
        assertTrue(
            "and a step past the end is still a real move, clamped to the end",
            pinsMoveChangesOrder(pins, b.key, +5)
        )
        assertEquals(
            "which lands it exactly on the last slot",
            listOf(a, c, b),
            movedPins(pins, b.key, +5)
        )
    }

    @Test
    fun `a step never drops, duplicates or rewrites a pin`() {
        val moved = movedPins(pins, b.key, Int.MIN_VALUE)
        assertEquals("every pin is still there", pins.size, moved.size)
        assertEquals("in the same set", pins.toSet(), moved.toSet())
        assertEquals("each once", pins.size, moved.distinctBy { it.key }.size)
        assertSame("and the pin objects are the store's own", a, moved.first { it.key == a.key })
    }

    @Test
    fun `a key no pin carries moves nothing`() {
        // The ghost a rename leaves behind is addressed by its STORED key, and a
        // key that is not in the blob any more (removed meanwhile, or adopted
        // away by a sibling) must be a no-op rather than an append.
        val ghost = LibraryHomeRailPin(source = "TRAKT_LIST", id = "old-slug", name = "Old")
        assertEquals(pins, movedPins(pins, ghost.key, +1))
        assertFalse(pinsMoveChangesOrder(pins, ghost.key, Int.MAX_VALUE))
        assertEquals(
            "an empty blob stays empty",
            emptyList<LibraryHomeRailPin>(),
            movedPins(emptyList(), a.key, -1)
        )
    }
}
