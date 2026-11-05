package com.kennyb1201.kbstream.ui.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPV's regular-subtitle size, as a number.
 *
 * Two bugs live behind this file. The first: the same size preference was
 * 11 / 14 / 18sp on the main player and a fixed 44 / 55 / 66 on MPV, so the two
 * engines disagreed about what "large" meant. The second, and the one the
 * viewer reported as "subtitles are tiny in mpv": the number was re-expressed
 * against the VIDEO's height, while mpv scales its cues with the WINDOW (a
 * 720-reference window, per its manual). A 2160p title on a 1080-tall surface -
 * which is what a DV/HDR remux usually is - therefore came out at half size,
 * and a 720p one came out 1.5x too large, on the same TV and the same setting.
 *
 * The rule is one pure function of Exo's sp step and the SURFACE height, so the
 * rendered height matches on both engines at every resolution. The
 * android-level half (when it is applied, and that ASS is left alone) is
 * MpvSubtitleSizeContractTest.
 */
class MpvSubtitleFontSizeTest {

    @Test
    fun `the size is Exo's sp step re-expressed against the window height`() {
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
    fun `an unmeasured window keeps mpv's own fixed steps`() {
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
    fun `large on a 1080-tall window at density 2 is 24, not 66`() {
        assertSize(
            "18sp at density 2 re-expressed against 1080 from a 720 reference",
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

    /**
     * The regression itself: mpv multiplies the number by window/720, so the
     * pixels on screen are the sp step at every window height - and the file's
     * own resolution does not enter the calculation at all. (It used to: a
     * 2160p title on this same surface asked for half the number, and came out
     * at half the size - the "tiny" report.)
     */
    @Test
    fun `the rendered height is the same at every window height, which is what Exo draws`() {
        val density = 2f
        listOf(720, 1080, 2160).forEach { window ->
            listOf(
                SubtitleOverlayStyle.SIZE_SMALL,
                SubtitleOverlayStyle.SIZE_NEUTRAL,
                SubtitleOverlayStyle.SIZE_LARGE
            ).forEach { size ->
                val font = SubtitleOverlayStyle.mpvFontSize(size, density, window)
                val renderedPx = font * window / SubtitleOverlayStyle.MPV_REFERENCE_HEIGHT_PX
                assertSize(
                    "size $size on a ${window}px-high window must render at Exo's " +
                        "${SubtitleOverlayStyle.exoTextSp(size)}sp",
                    (SubtitleOverlayStyle.exoTextSp(size) * density).toDouble(),
                    renderedPx
                )
            }
        }
    }

    @Test
    fun `a 2160p title on a 1080-tall surface is NOT half size`() {
        val font = SubtitleOverlayStyle.mpvFontSize(
            SubtitleOverlayStyle.SIZE_LARGE,
            2f,
            1080
        )
        // mpv draws at font x window/720, so on a 1080-tall window this is
        // 18sp x density - Exo's pixels, whatever the file's resolution.
        assertSize(
            "large renders at 36px on a 1080-tall window at density 2",
            36.0,
            font * 1080 / SubtitleOverlayStyle.MPV_REFERENCE_HEIGHT_PX
        )
        // The rule this replaced divided by the FILE's height and mpv then
        // scaled it by the window, so a 2160p file - 2x the surface - came out
        // at exactly half of Exo's size. That is the reported bug, pinned here
        // so the video's height cannot creep back into the calculation.
        val oldRule = SubtitleOverlayStyle.exoTextSp(SubtitleOverlayStyle.SIZE_LARGE) * 2f *
            SubtitleOverlayStyle.MPV_REFERENCE_HEIGHT_PX / 2160
        assertSize(
            "the old video-height rule rendered half of Exo's 36px",
            18.0,
            oldRule * 1080 / SubtitleOverlayStyle.MPV_REFERENCE_HEIGHT_PX.toDouble()
        )
    }

    @Test
    fun `a taller window gets a smaller number, so the rendered height stays put`() {
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
