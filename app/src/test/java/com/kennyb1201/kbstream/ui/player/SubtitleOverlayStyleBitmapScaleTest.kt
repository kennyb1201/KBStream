package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bitmap path's size rule (spec F1, test T1): one authored bitmap line
 * renders at the height of one TEXT line for the same size setting.
 *
 * This is the arithmetic that was missing: the bitmap path used
 * `scaleForSize` - Small 0.85 / Large 1.2 around the AUTHORED size - and an
 * authored PGS line is already taller than 18sp text on a 1080p TV, so "large"
 * meant "more of something already too big". The numbers here are the parity
 * target: 36px of text at Large on a 2.0-density screen, whatever the bitmap
 * was authored at.
 */
class SubtitleOverlayStyleBitmapScaleTest {

    private fun scale(
        sizeIndex: Int,
        bitmapHeightPx: Int,
        frameHeightPx: Int = 1080,
        scaledDensity: Float = 2f
    ): Float = SubtitleOverlayStyle.bitmapScaleForSize(
        sizeIndex = sizeIndex,
        bitmapHeightPx = bitmapHeightPx,
        frameHeightPx = frameHeightPx,
        scaledDensity = scaledDensity
    )

    @Test
    fun `a one-line bitmap at large renders at the large text height`() {
        // 18sp * 2.0 = 36px target; a 50px authored line -> 36/50.
        assertEquals(
            0.72f,
            scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 50),
            0.001f
        )
    }

    @Test
    fun `the same bitmap at small renders at the small text height`() {
        // 11sp * 2.0 = 22px target.
        assertEquals(
            0.44f,
            scale(SubtitleOverlayStyle.SIZE_SMALL, bitmapHeightPx = 50),
            0.001f
        )
    }

    @Test
    fun `a two-line bitmap is scaled per line, so the band is two text lines tall`() {
        // 100px is estimated as two 50px lines, not one 100px line: scaling by
        // the band would squash a two-line subtitle to one line's height.
        assertEquals(
            0.72f,
            scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 100),
            0.001f
        )
        // And the rendered band is the sum of its lines, not a single target.
        assertEquals(
            2 * 36f,
            100f * scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 100),
            0.5f
        )
    }

    @Test
    fun `a bitmap already smaller than the text size is never upscaled`() {
        // 36/30 would be 1.2; upscaling an authored image only blurs it.
        assertEquals(
            1f,
            scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 30),
            0.001f
        )
    }

    @Test
    fun `an enormous bitmap hits the floor instead of vanishing`() {
        // 2160p frame, 140px authored (one line by the 4.5% estimate) at Small:
        // 22 / 140 = 0.157, floored.
        assertEquals(
            SubtitleOverlayStyle.BITMAP_SCALE_MIN,
            scale(SubtitleOverlayStyle.SIZE_SMALL, bitmapHeightPx = 140, frameHeightPx = 2160),
            0.001f
        )
    }

    @Test
    fun `a very tall block is capped at three authored lines`() {
        // The line estimate is clamped to 1..3 (a caption is never taller), so a
        // 200px block on a 1080p frame reads as three 66.7px lines -> 0.54, not
        // the 0.2 the spec's example assumed for a one-line reading of it.
        assertEquals(
            0.54f,
            scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 200),
            0.01f
        )
    }

    @Test
    fun `an out-of-range size index is neutral, matching exoTextSp`() {
        // 14sp * 2.0 = 28px; out-of-range and Neutral must agree.
        assertEquals(
            scale(SubtitleOverlayStyle.SIZE_NEUTRAL, bitmapHeightPx = 50),
            scale(9, bitmapHeightPx = 50),
            0.0001f
        )
        assertEquals(0.56f, scale(9, bitmapHeightPx = 50), 0.001f)
    }

    @Test
    fun `unknown inputs answer the authored size instead of a guess`() {
        assertEquals(1f, scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 0), 0.0001f)
        assertEquals(
            1f,
            scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 50, frameHeightPx = 0),
            0.0001f
        )
        assertEquals(
            1f,
            scale(SubtitleOverlayStyle.SIZE_LARGE, bitmapHeightPx = 50, scaledDensity = 0f),
            0.0001f
        )
    }

    @Test
    fun `the ass overlay's own scale is untouched`() {
        // T3: the ASS overlay is a different picture with a different authored
        // base, and it must not move with this change.
        assertEquals(0.85f, SubtitleOverlayStyle.scaleForSize(SubtitleOverlayStyle.SIZE_SMALL), 0f)
        assertEquals(1f, SubtitleOverlayStyle.scaleForSize(SubtitleOverlayStyle.SIZE_NEUTRAL), 0f)
        assertEquals(1.2f, SubtitleOverlayStyle.scaleForSize(SubtitleOverlayStyle.SIZE_LARGE), 0f)
    }
}
