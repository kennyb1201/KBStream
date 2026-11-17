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
}
