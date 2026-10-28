package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room a browse-submenu cell reserves for its chip's focus cue.
 *
 * This replaced a fixed shared gap, and it replaced it because a gap cannot be
 * right for every width: a focused chip grows by a PERCENTAGE of itself
 * (`KBFocusChip`) and then draws `KBFocusGlowSmall` outside that, so the room
 * it needs grows with the chip while the gap stayed put. The grid's cells are
 * `GridCells.Adaptive`, so a wide TV stretched a cell past the width the gap
 * was picked for and the focused pill's border ran onto its neighbour - the
 * "clipped tile" this grid was reported for.
 *
 * The rule pinned here is the one the fix relies on: a cell of any width
 * reserves at least the overhang of a chip as wide as the cell allows.
 */
class BrowseChipCueRoomTest {

    private val sideGrowth = (KBFocusChip - 1f) / 2f

    @Test
    fun `the room covers a chip that fills its cell, at every width`() {
        // 216dp is the cell floor; the rest are the widths Adaptive produces as
        // the viewport widens, including the ones the old 16dp gap failed on.
        listOf(216f, 266.7f, 300f, 328f, 416f, 640f, 900f).forEach { cell ->
            val room = submenuCellCueRoomDp(cell)
            val widestChip = cell - 2f * room
            val overhang = sideGrowth * widestChip + KBFocusGlowSmall.value
            assertTrue(
                "cell $cell: room $room must cover overhang $overhang",
                room >= overhang - 0.01f
            )
        }
    }

    @Test
    fun `the room grows with the cell instead of staying at the old gap`() {
        assertTrue(submenuCellCueRoomDp(328f) > 16f)
        assertTrue(submenuCellCueRoomDp(328f) > submenuCellCueRoomDp(216f))
    }

    @Test
    fun `the narrowest cell keeps the room the old gap already covered`() {
        // At the cell floor the room comes out ~13.7dp: a 216dp cell's widest
        // chip is 216 - 2 x room, so its 14.5dp-full-cell overhang shrinks with
        // it. The room is never smaller than the glow alone, the floor of the
        // closed form.
        assertEquals(13.66f, submenuCellCueRoomDp(216f), 0.05f)
        assertTrue(submenuCellCueRoomDp(0f) >= KBFocusGlowSmall.value)
    }
}
