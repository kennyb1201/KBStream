package com.kennyb1201.kbstream.ui.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPV's regular-subtitle size, as a number.
 *
 * The bug this pins: the same size preference was 11 / 14 / 18sp on the main
 * player and a fixed 44 / 55 / 66 on MPV. The first list grows with the
 * display's density, the second with the video's height, so Large came out 18sp
 * on Exo and dramatically bigger on MPV for the same 1080p title.
 *
 * The rule is now one pure function - Exo's sp step, converted to pixels and
 * re-expressed against the video's height - and these are its numbers. The
 * android-level half (when it is applied, and that ASS is left alone) is
 * MpvSubtitleSizeContractTest.
 */
class MpvSubtitleFontSizeTest {

    @Test
    fun `the size is Exo's sp step re-expressed against the video height`() {
        listOf(720, 1080, 2160).forEach { height ->
            listOf(1.0f, 1.5f, 2.0f).forEach { density ->
                listOf(
                    SubtitleOverlayStyle.SIZE_SMALL,
                    SubtitleOverlayStyle.SIZE_NEUTRAL,
                    SubtitleOverlayStyle.SIZE_LARGE
                ).forEach { size ->
                    val expected = SubtitleOverlayStyle.exoTextSp(size) * density *
                        SubtitleOverlayStyle.MPV_REFERENCE_HEIGHT_PX / height
                    assertSize(
                        "size $size at density $density on a ${height}px-high title",
                        expected.toDouble(),
                        SubtitleOverlayStyle.mpvFontSize(size, density, height)
                    )
                }
            }
        }
    }

    @Test
    fun `the steps are the main player's 11, 14 and 18sp, one list for both engines`() {
        assertEquals(11f, SubtitleOverlayStyle.exoTextSp(SubtitleOverlayStyle.SIZE_SMALL), 0f)
        assertEquals(14f, SubtitleOverlayStyle.exoTextSp(SubtitleOverlayStyle.SIZE_NEUTRAL), 0f)
        assertEquals(18f, SubtitleOverlayStyle.exoTextSp(SubtitleOverlayStyle.SIZE_LARGE), 0f)
    }

    @Test
    fun `an unknown size index reads as neutral, exactly as renderText's getOrElse does`() {
        assertEquals(14f, SubtitleOverlayStyle.exoTextSp(7), 0f)
        assertSize(
            "an index outside the list is the neutral 14sp step",
            SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_NEUTRAL, 2f, 1080),
            SubtitleOverlayStyle.mpvFontSize(7, 2f, 1080)
        )
    }

    @Test
    fun `height unknown keeps mpv's own fixed steps, never a display-height guess`() {
        listOf(0, -1).forEach { unknown ->
            assertSize(
                "small with no height",
                44.0,
                SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_SMALL, 2f, unknown)
            )
            assertSize(
                "normal with no height",
                55.0,
                SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_NEUTRAL, 2f, unknown)
            )
            assertSize(
                "large with no height",
                66.0,
                SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_LARGE, 2f, unknown)
            )
        }
    }

    @Test
    fun `large on a 1080p title at density 2 is 24, not 66`() {
        assertSize(
            "18sp at density 2 re-expressed against 1080p from a 720p reference",
            24.0,
            SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_LARGE, 2f, 1080)
        )
    }

    @Test
    fun `the steps keep Exo's order at every height, and the same ratio`() {
        listOf(720, 1080, 2160).forEach { height ->
            val small = SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_SMALL, 2f, height)
            val neutral = SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_NEUTRAL, 2f, height)
            val large = SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_LARGE, 2f, height)
            assertTrue("small < normal at ${height}px ($small, $neutral)", small < neutral)
            assertTrue("normal < large at ${height}px ($neutral, $large)", neutral < large)
            assertSize(
                "small is Exo's 11/14 of normal at ${height}px",
                11.0 / 14.0,
                small / neutral
            )
            assertSize(
                "large is Exo's 18/14 of normal at ${height}px",
                18.0 / 14.0,
                large / neutral
            )
        }
    }

    @Test
    fun `a taller video gets a smaller number, so the rendered height stays put`() {
        val at720 = SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_LARGE, 2f, 720)
        val at1080 = SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_LARGE, 2f, 1080)
        val at2160 = SubtitleOverlayStyle.mpvFontSize(SubtitleOverlayStyle.SIZE_LARGE, 2f, 2160)
        assertTrue(at720 > at1080 && at1080 > at2160)
        // The scale is mpv's: the number is chosen so the pixels on screen match,
        // so it falls exactly as fast as the height rises.
        assertSize("720 to 1080", 1080.0 / 720.0, at720 / at1080)
        assertSize("1080 to 2160", 2160.0 / 1080.0, at1080 / at2160)
    }

    /**
     * `assertEquals(message, expected, actual, delta)` does not exist in JUnit 4
     * - its delta overload takes no message - and dropping the message on the
     * one test that walks a matrix would make a failure unreadable.
     */
    private fun assertSize(message: String, expected: Double, actual: Double) {
        assertTrue(
            "$message: expected $expected, got $actual",
            abs(expected - actual) <= 1e-6
        )
    }
}
