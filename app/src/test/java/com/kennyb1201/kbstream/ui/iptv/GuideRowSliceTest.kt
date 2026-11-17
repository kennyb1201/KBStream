package com.kennyb1201.kbstream.ui.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which cells of a guide row a windowed row draws (round four F6b).
 *
 * A row composes its whole six-hour span today - every cell, on every clock
 * tick, for the ones nobody is looking at. The slice is what turns that into the
 * viewport plus one screen of slack either way, and these are the properties the
 * focus graph depends on: never drop the focused cell, never drop its
 * neighbours, and keep the leading offset exactly the width of what was skipped
 * so the row's geometry - hour marks, now-line, scroll range - does not move.
 */
class GuideRowSliceTest {

    /** Ten 100dp cells: offsets 0, 100, ... 900. */
    private val cells = List(10) { 100 }

    @Test
    fun `the window is the viewport and one screen either side`() {
        // Viewport 200dp wide at scroll 400dp: from 200 to 800 -> cells 2..7.
        val slice = guideRowSlice(cells, scrollDp = 400, viewportDp = 200)
        assertEquals("first visible cell at the low edge", 2, slice.from)
        assertEquals("last cell starting before the high edge", 7, slice.to)
        assertEquals("leading spacer is what came before", 200, slice.leadDp)
        assertTrue("and it is a real reduction", slice.to - slice.from + 1 < cells.size)
    }

    @Test
    fun `a cell that starts before the window but is still on screen stays`() {
        // Scroll 250: cell 2 (200..300) straddles the low edge of the buffer
        // (150), and cell 7 (700..800) is inside. Cell 1 ends at 200 < 150? no:
        // buffer low edge is 50, so cell 0 is the one straddling it.
        val slice = guideRowSlice(cells, scrollDp = 250, viewportDp = 200)
        assertEquals("the cell under the low edge is drawn, not sliced in half", 0, slice.from)
        assertEquals(0, slice.leadDp)
    }

    @Test
    fun `the focused cell and its neighbours are always drawn`() {
        // The union, not a replacement: a cell that is not composed has no focus
        // node, so an anchor outside the scroll window EXTENDS the slice rather
        // than moving it - the visible window is still looked at.
        val slice = guideRowSlice(cells, scrollDp = 0, viewportDp = 200, anchors = listOf(9))
        assertTrue("the anchor is drawn", slice.from <= 8 && slice.to >= 9)
        assertEquals("and nothing before it was cut off", 0, slice.from)
        assertEquals("whose spacer is therefore zero", 0, slice.leadDp)

        // An anchor one past the window adds that cell and its neighbour...
        val extended = guideRowSlice(cells, scrollDp = 0, viewportDp = 100, anchors = listOf(4))
        val plain = guideRowSlice(cells, scrollDp = 0, viewportDp = 100)
        assertTrue("the neighbour is drawn", extended.to >= 5)
        assertTrue("which the plain window would not have", plain.to < 5)
        // ...and one INSIDE it changes nothing at all.
        val inside = guideRowSlice(cells, scrollDp = 400, viewportDp = 200, anchors = listOf(4))
        val untouched = guideRowSlice(cells, scrollDp = 400, viewportDp = 200)
        assertEquals(untouched, inside)
    }

    @Test
    fun `nothing is skipped when the whole row fits the window`() {
        val slice = guideRowSlice(List(3) { 50 }, scrollDp = 0, viewportDp = 400)
        assertEquals(0, slice.from)
        assertEquals(2, slice.to)
        assertEquals(0, slice.leadDp)
    }

    @Test
    fun `an empty row is an empty slice, not a crash`() {
        val slice = guideRowSlice(emptyList(), scrollDp = 0, viewportDp = 200)
        assertTrue(slice.isEmpty)
    }

    @Test
    fun `the slice never runs past the row`() {
        val slice = guideRowSlice(cells, scrollDp = 10_000, viewportDp = 200)
        assertEquals("clamped to the last cell", 9, slice.to)
        assertTrue(slice.from <= slice.to)
        // A zero-width viewport cannot ask for everything.
        val degenerate = guideRowSlice(cells, scrollDp = 0, viewportDp = 0)
        assertTrue(degenerate.to - degenerate.from + 1 < cells.size)
    }

    @Test
    fun `a handoff in flight keeps its target composed, not just the source`() {
        // The pressed cell still holds focus while the axis animates to the cell
        // the handoff is aimed at. Both have to be drawn, or the target has no
        // focus node when the retry asks for it and the press dies silently.
        val slice = guideRowSlice(cells, scrollDp = 0, viewportDp = 200, anchors = listOf(1, 6))
        assertTrue("the source is drawn", slice.from <= 1)
        assertTrue("and so is the target", slice.to >= 6)
    }
}
