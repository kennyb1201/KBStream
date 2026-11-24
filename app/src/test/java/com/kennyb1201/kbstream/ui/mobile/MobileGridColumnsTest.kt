package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many poster columns a mobile grid draws (spec 2026-11-22, adaptive
 * layouts, F4).
 *
 * The width-derived rule shipped without a test; the cap it now takes from the
 * window class makes this the moment to add one. The phone numbers are the
 * regression gate - three posters in a portrait hand, and the same three after
 * the cap moved, because a phone window is several columns short of any cap.
 */
class MobileGridColumnsTest {

    private val mediumTile = 124.dp
    private val spacing = 12.dp
    private val gutter = 32.dp // 16dp on each side, as every phone grid pads

    private fun columns(
        windowWidthDp: Int,
        landscape: Boolean = false,
        tile: androidx.compose.ui.unit.Dp = mediumTile,
        cap: Int = MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Compact)
    ) = mobileGridColumns(
        availableWidth = windowWidthDp.dp - gutter,
        preferredTileWidth = tile,
        spacing = spacing,
        landscape = landscape,
        columnCap = cap
    )

    @Test
    fun `a portrait phone is still three columns`() {
        assertEquals(3, columns(360))
        assertEquals(3, columns(390))
        assertEquals(3, columns(430))
    }

    @Test
    fun `a landscape phone gets the width it has`() {
        // 800dp wide, 16:9 cards start at two: 768dp of content fits five
        // 124dp tiles, which is already more than the three a hand gets.
        assertEquals(5, columns(800, landscape = true))
        assertTrue(columns(800, landscape = true) > columns(360))
    }

    @Test
    fun `the cap follows the window class`() {
        // A window wide enough for ten Medium posters: Compact stops at 8, Medium
        // allows 10, Expanded allows 12 but the fit itself stops at 10.
        assertEquals(8, columns(1400, cap = MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Compact)))
        assertEquals(10, columns(1400, cap = MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Medium)))
        assertEquals(10, columns(1400, cap = MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Expanded)))
    }

    @Test
    fun `an expanded tablet turns width into columns, not bigger posters`() {
        // 1280dp landscape with the same Medium tile: nine across, and the cap is
        // not what limits it - the tile width is.
        assertEquals(9, columns(1280, landscape = true, cap = 12))
        assertTrue(columns(1280, landscape = true, cap = 12) > columns(800, landscape = true))
    }

    @Test
    fun `no phone width reaches the old cap`() {
        // The reason Compact's cap value could stay at 8: it never binds in a
        // hand, so the phone's rendering is unchanged by construction.
        listOf(360, 390, 412, 430, 800, 915).forEach { width ->
            assertTrue("$width dp reached 8 columns", columns(width) < 8)
            assertTrue("$width dp reached 8 columns, landscape", columns(width, landscape = true) < 8)
        }
    }

    @Test
    fun `a degenerate width answers the floor, and a cap never goes below it`() {
        assertEquals(3, columns(0))
        // A cap smaller than the floor cannot turn a grid into zero columns.
        assertEquals(3, columns(2000, cap = 1))
        assertEquals(2, columns(2000, landscape = true, cap = 1))
    }
}
