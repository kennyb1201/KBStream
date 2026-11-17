package com.kennyb1201.kbstream.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rest invariant the billboard's fix is built on (spec 2026-11-17, F1).
 *
 * A page that is not scrolling must sit at offset 0. The reported bug was a
 * 3%-of-a-frame offset left over at rest - so this rule has to say yes to 0.03
 * and no to floating-point dust, in both directions: the field photo showed the
 * strip at the right edge (positive offset), but the same bug mirrored shows the
 * previous page at the left.
 */
class BillboardRestOffsetTest {

    @Test
    fun `a settled pager is not off centre`() {
        assertFalse(billboardRestOffCenter(0f))
        assertFalse(billboardRestOffCenter(-0f))
    }

    @Test
    fun `floating point dust is not a strip`() {
        assertFalse(billboardRestOffCenter(BILLBOARD_REST_EPSILON))
        assertFalse(billboardRestOffCenter(-BILLBOARD_REST_EPSILON))
        assertFalse("a tenth of a pixel on a 358dp frame", billboardRestOffCenter(0.0003f))
    }

    @Test
    fun `the reported strip is off centre in both directions`() {
        assertTrue("the field photo: next page at the right edge", billboardRestOffCenter(0.03f))
        assertTrue("the mirror: previous page at the left edge", billboardRestOffCenter(-0.03f))
    }

    @Test
    fun `anything past the threshold counts, however small the visible sliver`() {
        assertTrue(billboardRestOffCenter(BILLBOARD_REST_EPSILON * 1.01f))
        assertTrue(billboardRestOffCenter(-0.002f))
        // The neighbours of a settled page are always a whole page away, so the
        // largest offset that can still be called "rest" is half a page.
        assertTrue(billboardRestOffCenter(-0.5f))
        assertTrue(billboardRestOffCenter(0.5f))
    }

    @Test
    fun `the threshold stays smaller than a visible sliver`() {
        assertEquals(
            "0.001 of a 358dp frame is a third of a pixel: invisible, so nothing " +
                "a viewer could report is ever let through",
            0.001f,
            BILLBOARD_REST_EPSILON,
            0f
        )
    }
}
