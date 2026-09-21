package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home arrangement rules for the manager's top/bottom moves and the read-time
 * repair of arrangements written before pinned was collections-only.
 *
 * The bug being pinned here: the manager's "very top" action prepended ANY key
 * to the pinned list, but only collections have a pin control — so a catalog
 * sent to the top was stuck above every pinned collection with no way back
 * out. Catalogs now reach the top of the ORDER block, and an already-stuck
 * catalog is healed on read.
 */
class KBHomeOrderMoveTest {

    private val colA = "kb:My Collection"
    private val colB = "kb:Another"
    private val catA = "addon:https://a.example:movie:top"
    private val catB = "addon:https://b.example:series:trending"

    // ── top / bottom moves ──────────────────────────────────────────

    @Test
    fun `top pins a collection above everything`() {
        val result = moveRailToEnd(
            KBHomeOrder(order = listOf(colB, catA), pinned = listOf(colA)),
            colB,
            toTop = true
        )
        assertEquals(listOf(colB, colA), result.pinned)
        assertEquals(listOf(catA), result.order)
    }

    @Test
    fun `top puts a catalog at the head of the order block, never in pinned`() {
        val result = moveRailToEnd(
            KBHomeOrder(order = listOf(catA, catB), pinned = listOf(colA)),
            catB,
            toTop = true
        )
        assertEquals(listOf(colA), result.pinned)
        assertEquals(listOf(catB, catA), result.order)
    }

    @Test
    fun `top on a catalog clears every stray catalog out of pinned`() {
        // The exact stuck state: catalogs sitting in pinned with no control to
        // remove them. A top move on one of them repairs the whole list.
        val result = moveRailToEnd(
            KBHomeOrder(order = listOf(catB), pinned = listOf(catA, colA)),
            catB,
            toTop = true
        )
        assertEquals(listOf(colA), result.pinned)
        // The moved catalog takes the head of the order block; the stray it
        // dislodged lands right behind it rather than vanishing.
        assertEquals(listOf(catB, catA), result.order)
    }

    @Test
    fun `bottom unpins a collection and appends it last`() {
        val result = moveRailToEnd(
            KBHomeOrder(order = listOf(catA), pinned = listOf(colA, colB)),
            colA,
            toTop = false
        )
        assertEquals(listOf(colB), result.pinned)
        assertEquals(listOf(catA, colA), result.order)
    }

    @Test
    fun `moves clear the hidden flag`() {
        val hidden = KBHomeOrder(hidden = listOf(catA))
        assertFalse(catA in moveRailToEnd(hidden, catA, toTop = true).hidden)
        assertFalse(catA in moveRailToEnd(hidden, catA, toTop = false).hidden)
    }

    // ── read-time repair ────────────────────────────────────────────

    @Test
    fun `catalogs pinned by an older build move to the head of order`() {
        val healed = normalizeHomeOrder(
            KBHomeOrder(
                order = listOf(colB),
                pinned = listOf(catA, colA, catB)
            )
        )
        assertEquals(listOf(colA), healed.pinned)
        // Strays keep the order they were pinned in, ahead of the old order.
        assertEquals(listOf(catA, catB, colB), healed.order)
    }

    @Test
    fun `an already-clean arrangement is returned unchanged`() {
        val clean = KBHomeOrder(
            order = listOf(catA, colB),
            pinned = listOf(colA),
            hidden = listOf(catB)
        )
        assertTrue(normalizeHomeOrder(clean) === clean)
    }

    @Test
    fun `duplicate keys are dropped, first occurrence winning`() {
        val healed = normalizeHomeOrder(
            KBHomeOrder(
                order = listOf(catA, catA, colA),
                pinned = listOf(colA, colA)
            )
        )
        assertEquals(listOf(colA), healed.pinned)
        assertEquals(listOf(catA), healed.order)
    }

    @Test
    fun `hidden keys are never touched by the repair`() {
        val healed = normalizeHomeOrder(
            KBHomeOrder(
                order = listOf(catA),
                pinned = listOf(catB),
                hidden = listOf(catA)
            )
        )
        assertEquals(listOf(catA), healed.hidden)
    }
}
