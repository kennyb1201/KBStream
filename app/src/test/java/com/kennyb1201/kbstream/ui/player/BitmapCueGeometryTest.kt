package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retention half of F1 (test T4): a bitmap cue group supplies the authored
 * height, and the handler re-applies the SAME scale from it when the viewer
 * changes the size mid-cue - `refreshStyle` runs without a cue group in hand.
 *
 * The handler itself needs a player and a surface, so what is pinned here is
 * the arithmetic it delegates to: the retained values, the fallback to the
 * view's own height, and that clearing drops the scale instead of leaving a
 * text line (or a track switch) with the previous image's geometry.
 */
class BitmapCueGeometryTest {

    private val large = SubtitleOverlayStyle.SIZE_LARGE
    private val small = SubtitleOverlayStyle.SIZE_SMALL

    @Test
    fun `a size change mid-cue re-applies the same scale`() {
        val geometry = BitmapCueGeometry()
        geometry.remember(bitmapHeightPx = 50, frameHeightPx = 1080)

        // What showBitmapCues applies...
        val atDraw = geometry.scaleFor(large, scaledDensity = 2f, fallbackFrameHeightPx = 0)
        // ...and what refreshStyle applies with no cue group in hand.
        val afterChange = geometry.scaleFor(large, scaledDensity = 2f, fallbackFrameHeightPx = 0)

        assertEquals(0.72f, atDraw, 0.001f)
        assertEquals(atDraw, afterChange, 0f)
        assertEquals(0.44f, geometry.scaleFor(small, 2f, 0), 0.001f)
    }

    @Test
    fun `clearing drops the retained scale`() {
        val geometry = BitmapCueGeometry()
        geometry.remember(bitmapHeightPx = 50, frameHeightPx = 1080)
        assertTrue(geometry.hasBitmap)

        geometry.clear()

        assertFalse(geometry.hasBitmap)
        assertEquals(
            "with no bitmap in hand the view keeps the authored size",
            1f,
            geometry.scaleFor(large, scaledDensity = 2f, fallbackFrameHeightPx = 1080),
            0f
        )
    }

    @Test
    fun `the frame falls back to the surface when the player reported none`() {
        val geometry = BitmapCueGeometry()
        geometry.remember(bitmapHeightPx = 50, frameHeightPx = 0)
        assertEquals(0.72f, geometry.scaleFor(large, 2f, fallbackFrameHeightPx = 1080), 0.001f)
    }

    @Test
    fun `a group with no usable bitmap leaves the authored size`() {
        val geometry = BitmapCueGeometry()
        geometry.remember(bitmapHeightPx = 0, frameHeightPx = 1080)
        geometry.remember(bitmapHeightPx = -4, frameHeightPx = -1)

        assertFalse(geometry.hasBitmap)
        assertEquals(1f, geometry.scaleFor(large, 2f, 1080), 0f)
    }
}
