package com.kennyb1201.kbstream.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-11-17, F2: a pill's press belongs to the page in front WHEN IT IS PRESSED.
 *
 * The phone's billboard cycles under a focused pill now, so the page under the
 * hand can change between the moment the pill took focus and the moment it is
 * pressed. Resolving the page at press time is the whole answer to the fight F5
 * used to avoid by pausing - so the lookup's fallbacks are pinned here, including
 * the two that keep it from ever doing nothing on a live page.
 */
class BillboardPressPageTest {

    private val pages = listOf("A", "B", "C")

    @Test
    fun `the press acts on the page the pager is on`() {
        assertEquals("B", billboardPressPage(pages, currentIndex = 1, settledIndex = 0))
        assertEquals("C", billboardPressPage(pages, currentIndex = 2, settledIndex = 0))
    }

    @Test
    fun `a pager mid-animation acts on the page it is closest to, not the one it left`() {
        // currentPage moves before settledPage finishes travelling; the viewer and
        // the pager agree on the new page, which is the one the press belongs to.
        assertEquals("C", billboardPressPage(pages, currentIndex = 2, settledIndex = 1))
    }

    @Test
    fun `a feed that shrank under the hero falls back to the settled page`() {
        assertEquals("C", billboardPressPage(pages, currentIndex = 9, settledIndex = 2))
    }

    @Test
    fun `and then to the first page, so a live press never does nothing`() {
        assertEquals("A", billboardPressPage(pages, currentIndex = 9, settledIndex = 9))
        assertEquals("A", billboardPressPage(pages, currentIndex = -1, settledIndex = -1))
    }

    @Test
    fun `an empty billboard has nothing to press`() {
        assertNull(billboardPressPage(emptyList<String>(), currentIndex = 0, settledIndex = 0))
    }

    // ── 2026-11-17, F1: the chevrons wrap like the auto-advance ───────────

    @Test
    fun `a step moves one page and wraps at both ends`() {
        assertEquals(1, billboardWrapPage(size = 3, from = 0, step = 1))
        assertEquals(2, billboardWrapPage(size = 3, from = 1, step = 1))
        assertEquals("last -> first, the auto-advance's own wrap", 0, billboardWrapPage(3, 2, 1))
        assertEquals(2, billboardWrapPage(size = 3, from = 0, step = -1))
        assertEquals("first -> last", 1, billboardWrapPage(3, 2, -1))
    }

    @Test
    fun `a single page has nowhere to step`() {
        assertEquals(0, billboardWrapPage(size = 1, from = 0, step = 1))
        assertEquals(0, billboardWrapPage(size = 1, from = 0, step = -1))
    }

    @Test
    fun `an index that outlived its feed steps from the nearest real page`() {
        assertEquals("9 clamps to the last page, then steps to the first", 0, billboardWrapPage(3, 9, 1))
        assertEquals("-4 clamps to the first page, then wraps back to the last", 2, billboardWrapPage(3, -4, -1))
    }

    @Test
    fun `an empty billboard has nowhere to step`() {
        assertNull(billboardWrapPage(size = 0, from = 0, step = 1))
    }
}
