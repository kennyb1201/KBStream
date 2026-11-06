package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One order for the whole Home column: the arranged rails AND the viewer's
 * pinned Library lists.
 *
 * The bug being pinned here: a pinned Library list was a rail of a second list.
 * The arrangement ordered the catalogs, collections and built-ins, Home appended
 * the pinned lists after all of them, and the manager's arrows could not cross -
 * the last arranged rail had no "down" and the first pinned list no "up", so a
 * Library rail could never sit above a catalog however often the viewer pressed.
 *
 * The fallbacks below are as important as the moves: every pin already in the
 * field has no entry in the stored order, and every entry can outlive its pin.
 * Those must cost the column nothing - a pin appends at the end in pin order, and
 * an entry naming a removed pin is skipped rather than drawn as a blank slot.
 */
class HomeColumnOrderTest {

    private val cw = "builtin:continue_watching"
    private val catA = "addon:https://a.example:movie:top"
    private val catB = "addon:https://b.example:series:trending"
    private val collection = "kb:Films"
    private val pinA = "list:TRAKT_LIST|my-list"
    private val pinB = "list:BINGECAT_LIST|ref-b"

    // The arrangement's own rails in their default order, then the pins in PIN
    // order - the tail is the fallback, which is where Home drew them before.
    private val defaults = listOf(cw, catA, catB, collection)
    private val pins = listOf(pinA, pinB)

    private fun column(prefs: KBHomeOrder) = homeColumnRailKeys(prefs, defaults, pins)

    // ── placement ───────────────────────────────────────────────────

    @Test
    fun `pins with no entry land at the end of the column, in pin order`() {
        assertEquals(defaults + pins, column(KBHomeOrder()))
    }

    @Test
    fun `a stored entry puts a pin between the catalogs`() {
        val prefs = KBHomeOrder(order = listOf(catA, pinA))

        // The pin takes the slot the order gave it; everything the order does
        // not name keeps its default slot behind it.
        assertEquals(
            listOf(catA, pinA, cw, catB, collection, pinB),
            column(prefs)
        )
    }

    @Test
    fun `a placement made on another device holds, and the rest fall back`() {
        // What a sibling pushes: the pins blob unchanged, the order carrying
        // both pins where that device put them.
        val prefs = KBHomeOrder(order = listOf(pinB, catA, catB, pinA))

        assertEquals(listOf(pinB, catA, catB, pinA, cw, collection), column(prefs))
    }

    @Test
    fun `an entry naming a removed pin is skipped, never a blank slot`() {
        val prefs = KBHomeOrder(
            order = listOf(pinA, "list:TRAKT_LIST|deleted-on-another-device", catA)
        )

        assertEquals(
            listOf(pinA, catA, cw, catB, collection, pinB),
            column(prefs)
        )
    }

    @Test
    fun `an unknown key in the stored order cannot invent a rail`() {
        // An older or newer build's key family: the column ignores it rather than
        // handing Home something no renderer can draw.
        val prefs = KBHomeOrder(order = listOf("widget:brand-new-family", catA))

        assertEquals(
            listOf(catA, cw, catB, collection, pinA, pinB),
            column(prefs)
        )
    }

    // ── moves across the old fence ───────────────────────────────────

    @Test
    fun `a pin steps up past the catalog above it`() {
        val prefs = KBHomeOrder(order = listOf(catA, pinA))

        val moved = moveRailInHomeColumn(prefs, column(prefs), pinA, -1)

        val after = column(moved)
        assertTrue(
            "the pin is now drawn above the catalog it was under",
            after.indexOf(pinA) < after.indexOf(catA)
        )
        assertEquals(
            "and nothing else changed places",
            listOf(pinA, catA, cw, catB, collection, pinB),
            after
        )
    }

    @Test
    fun `a catalog steps down past the pin below it`() {
        val prefs = KBHomeOrder(order = listOf(catA, pinA))

        val moved = moveRailInHomeColumn(prefs, column(prefs), catA, +1)

        val after = column(moved)
        assertTrue(
            "the catalog is now drawn below the pin it was above",
            after.indexOf(catA) > after.indexOf(pinA)
        )
        assertEquals(
            "and nothing else changed places",
            listOf(pinA, catA, cw, catB, collection, pinB),
            after
        )
    }

    @Test
    fun `two pins still swap among themselves`() {
        val prefs = KBHomeOrder(order = defaults + pins)

        val moved = moveRailInHomeColumn(prefs, column(prefs), pinB, -1)

        assertEquals(
            listOf(cw, catA, catB, collection, pinB, pinA),
            moved.order
        )
    }

    @Test
    fun `very top takes a pin above the first arranged rail`() {
        val prefs = KBHomeOrder(order = listOf(catA, catB))

        val moved = moveRailInHomeColumn(prefs, column(prefs), pinB, Int.MIN_VALUE)

        assertEquals(pinB, column(moved).first())
    }

    @Test
    fun `very bottom puts a catalog below the last pin`() {
        val prefs = KBHomeOrder(order = listOf(catA, pinA, catB))

        val moved = moveRailInHomeColumn(prefs, column(prefs), catA, Int.MAX_VALUE)

        assertEquals(catA, column(moved).last())
    }

    @Test
    fun `a move writes the order and leaves the pins alone`() {
        // Which lists are pinned is one statement; where they sit is another. A
        // move that rewrote the pins' order would publish a pin edit for a rail
        // nobody pinned or unpinned.
        val prefs = KBHomeOrder(order = listOf(catA), pinned = listOf(collection))

        val moved = moveRailInHomeColumn(prefs, column(prefs), pinA, -1)

        assertEquals(prefs.pinned, moved.pinned)
        assertEquals(prefs.hidden, moved.hidden)
        assertEquals(prefs.renames, moved.renames)
    }

    // ── the arrows ─────────────────────────────────────────────────

    @Test
    fun `arrows are dark exactly where a press would change nothing`() {
        val prefs = KBHomeOrder(order = defaults + pins)
        val keys = column(prefs)

        assertFalse(
            "the first entry of the column has no up",
            railMoveChangesOrder(prefs, keys, keys.first(), -1)
        )
        assertFalse(
            "nor anything to gain from the very top",
            railMoveChangesOrder(prefs, keys, keys.first(), Int.MIN_VALUE)
        )
        assertFalse(
            "the last entry has no down",
            railMoveChangesOrder(prefs, keys, keys.last(), +1)
        )
        assertFalse(
            "nor a bottom to reach",
            railMoveChangesOrder(prefs, keys, keys.last(), Int.MAX_VALUE)
        )
    }

    @Test
    fun `a pin's arrows are lit by the column, not by the pins alone`() {
        // The old rule asked the pins list, where the first pin had no up and the
        // last no down whatever Home drew above or below it.
        val prefs = KBHomeOrder(order = defaults + pins)
        val keys = column(prefs)

        assertTrue("up, past the collection", railMoveChangesOrder(prefs, keys, pinA, -1))
        assertTrue("down, past the other pin", railMoveChangesOrder(prefs, keys, pinA, +1))
        assertTrue("to the very top", railMoveChangesOrder(prefs, keys, pinA, Int.MIN_VALUE))
        assertTrue("to the very bottom", railMoveChangesOrder(prefs, keys, pinA, Int.MAX_VALUE))
    }

    @Test
    fun `a step past the end is clamped to the end, not swallowed`() {
        val prefs = KBHomeOrder(order = defaults + pins)
        val keys = column(prefs)

        val moved = moveRailInHomeColumn(prefs, keys, pinA, +5)

        assertEquals(pinB, moved.order[moved.order.lastIndex - 1])
        assertEquals(pinA, moved.order.last())
    }
}
