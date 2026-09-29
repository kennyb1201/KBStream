package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decisions behind Settings → Playback → Match Content Frame Rate.
 *
 * The property worth protecting is the refusal: a panel that cannot do an exact
 * multiple of the content rate must be left exactly as it was. Flashing the
 * screen into a mode that judders the same amount is worse than doing nothing,
 * and on the boxes that only report the mode they are already in — most of them
 * — that is every case.
 */
class FrameRateMatchTest {

    private fun mode(id: Int, rate: Double) = DisplayModeInfo(id, rate)

    @Test
    fun `film on a panel that can do 24 Hz switches to it`() {
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 23.976,
            modes = listOf(mode(1, 60.0), mode(2, 24.0), mode(3, 50.0)),
            currentModeId = 1
        )

        assertEquals(24.0, chosen?.refreshRate ?: 0.0, 0.0)
        assertEquals(2, chosen?.id)
    }

    @Test
    fun `a 60 Hz only panel is left alone`() {
        // 23.976 fps into 60 Hz is 2.5 frames per slot: no integer between them,
        // so there is no mode to ask for and the panel must not be touched.
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 23.976,
            modes = listOf(mode(1, 60.0)),
            currentModeId = 1
        )

        assertNull(chosen)
    }

    @Test
    fun `a panel already in the right mode is not asked again`() {
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 24.0,
            modes = listOf(mode(1, 60.0), mode(2, 24.0)),
            currentModeId = 2
        )

        assertNull(chosen)
    }

    @Test
    fun `PAL content takes the 50 Hz mode rather than its own rate`() {
        // 25 fps has no 25 Hz mode to ask for; 50 Hz is the exact double.
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 25.0,
            modes = listOf(mode(10, 50.0), mode(11, 60.0)),
            currentModeId = 11
        )

        assertEquals(10, chosen?.id)
    }

    @Test
    fun `the smallest exact multiple wins`() {
        // 24 into 48 and 120 both divide exactly, but 24 Hz repeats the fewest
        // frames, so it is the one worth asking for.
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 24.0,
            modes = listOf(mode(1, 24.0), mode(2, 48.0), mode(3, 120.0)),
            currentModeId = 3
        )

        assertEquals(1, chosen?.id)
    }

    @Test
    fun `29_97 content prefers 30 Hz over 60 Hz`() {
        val both = FrameRateMatch.chooseMode(
            contentFps = 29.97,
            modes = listOf(mode(1, 30.0), mode(2, 60.0)),
            currentModeId = 2
        )
        assertEquals(1, both?.id)

        // With no 30 Hz mode, the exact double is still a match.
        val onlySixty = FrameRateMatch.chooseMode(
            contentFps = 29.97,
            modes = listOf(mode(2, 60.0)),
            currentModeId = 9
        )
        assertEquals(2, onlySixty?.id)
    }

    @Test
    fun `a mode slower than the content is never chosen`() {
        // 24 Hz cannot show every frame of 60 fps content; the multiple would be
        // a fraction, and a fraction is not a match.
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 60.0,
            modes = listOf(mode(1, 24.0)),
            currentModeId = 9
        )

        assertNull(chosen)
    }

    @Test
    fun `a tie is settled by the lower mode id`() {
        // Two modes reporting the same rate: the answer must not depend on the
        // order the box happened to list them in.
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 24.0,
            modes = listOf(mode(7, 48.0), mode(3, 48.0)),
            currentModeId = 60
        )

        assertEquals(3, chosen?.id)
    }

    @Test
    fun `modes that report no usable rate are skipped`() {
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 23.976,
            modes = listOf(mode(1, Double.NaN), mode(2, 0.0), mode(3, 24.0)),
            currentModeId = 60
        )

        assertEquals(3, chosen?.id)
    }

    @Test
    fun `a rate that does not describe content is refused`() {
        val modes = listOf(mode(1, 24.0))
        // media3 reports NO_VALUE (-1) for a track it could not measure, and an
        // HLS variant list can deliver a placeholder 0 in the same way.
        listOf(-1.0, 0.0, 5.0, 1000.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { fps ->
            assertNull("$fps fps must not be matched", FrameRateMatch.chooseMode(fps, modes, 60))
        }
    }

    @Test
    fun `noise around a standard rate is snapped onto it`() {
        // Extractors disagree about the same film: 23.98 and 23.976 are the same
        // content, and both must land on the same decision.
        assertEquals(23.976, FrameRateMatch.normalise(23.98) ?: 0.0, 0.0)
        assertEquals(23.976, FrameRateMatch.normalise(23.976023) ?: 0.0, 0.0)
        assertEquals(24.0, FrameRateMatch.normalise(24.001) ?: 0.0, 0.0)
        // A standard rate is left as itself, not nudged onto a neighbour.
        assertEquals(29.97, FrameRateMatch.normalise(29.97) ?: 0.0, 0.0)

        // Not close enough to a standard rate: kept as it is, not rounded into
        // one it is not.
        assertEquals(27.5, FrameRateMatch.normalise(27.5) ?: 0.0, 0.0)
        assertNull(FrameRateMatch.normalise(0.0))
    }

    @Test
    fun `a lower multiple beats a closer one`() {
        // 47.952 Hz is 1e-3 off an exact double for 23.976, and 24 Hz is 0.024
        // off a single. The single still wins: the whole point is fewer repeated
        // frames, not the smaller arithmetic difference.
        val chosen = FrameRateMatch.chooseMode(
            contentFps = 23.976,
            modes = listOf(mode(1, 47.952), mode(2, 24.0)),
            currentModeId = 60
        )

        assertEquals(2, chosen?.id)
    }
}
