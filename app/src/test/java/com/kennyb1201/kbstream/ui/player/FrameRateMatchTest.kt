package com.kennyb1201.kbstream.ui.player

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ── How the rate is asked for ──────────────────────────────────────
    //
    // Two ways, in this order: the platform's own frame-rate API on the surface
    // the picture goes to, then the window's display-mode hint as the backstop
    // for a firmware that ignores the platform's scheduler. Which of the two is
    // even possible is a function of the Android version, which is why it is
    // decided here rather than inside a `Build.VERSION` branch the tests cannot
    // reach.

    @Test
    fun `below Android 11 there is no frame-rate API to ask`() {
        assertNull(FrameRateMatch.surfaceRateRequest(23.976, 23))
        assertNull(FrameRateMatch.surfaceRateRequest(23.976, 29))
    }

    @Test
    fun `Android 11 can ask for a rate but only for a switch that does not blank`() {
        val request = FrameRateMatch.surfaceRateRequest(23.976, 30)

        assertNotNull(request)
        // CHANGE_FRAME_RATE_ALWAYS does not exist yet, so nothing carries it and
        // the framework is left to take only what it can do without blanking.
        assertNull(request?.changeFrameRateStrategy)
    }

    @Test
    fun `from Android 12 the request opts into a blank-screen switch`() {
        // Without this flag the framework refuses to blank the screen for a rate
        // change - which is precisely the switch film on a 60 Hz panel needs.
        val request = FrameRateMatch.surfaceRateRequest(24.0, 31)

        assertEquals(Surface.CHANGE_FRAME_RATE_ALWAYS, request?.changeFrameRateStrategy ?: 0)
        assertEquals(24.0f, request?.frameRate ?: 0f, 0f)
    }

    @Test
    fun `the request carries the standardised rate, not the raw one`() {
        // 23.976023 is what an extractor reports for 23.976, and handing the
        // panel the raw measurement is how an app asks for a rate no panel has.
        assertEquals(23.976f, FrameRateMatch.surfaceRateRequest(23.976023, 33)?.frameRate ?: 0f, 0f)
        assertEquals(24.0f, FrameRateMatch.surfaceRateRequest(24.001, 33)?.frameRate ?: 0f, 0f)
    }

    @Test
    fun `a rate that does not describe content is never asked for`() {
        listOf(-1.0, 0.0, 5.0, 1000.0, Double.NaN).forEach { fps ->
            assertNull(
                "$fps fps must not be asked for",
                FrameRateMatch.surfaceRateRequest(fps, 33)
            )
        }
    }

    // ── The lines the diagnostics row shows ────────────────────────────
    //
    // These are the only thing that separates "the TV refused" from "there was
    // nothing to ask for", so their wording is pinned: a line that quietly
    // changes meaning is worse than no line at all.

    @Test
    fun `the panel line names the mode it is in and every mode it reports`() {
        val line = FrameRateMatch.describeModes(
            modes = listOf(mode(1, 60.0), mode(2, 24.0), mode(3, 50.0)),
            currentModeId = 1
        )

        assertEquals("mode 1 at 60.000 Hz; 3 modes: 1@60.000, 2@24.000, 3@50.000", line)
    }

    @Test
    fun `a single-mode panel says so, which is the whole answer for a TV that never switches`() {
        assertEquals(
            "mode 1 at 60.000 Hz; 1 mode: 1@60.000",
            FrameRateMatch.describeModes(listOf(mode(1, 60.0)), currentModeId = 1)
        )
    }

    @Test
    fun `a long mode list is summarised rather than printed in full`() {
        val line = FrameRateMatch.describeModes(
            modes = (1..12).map { mode(it, 49.0 + it) },
            currentModeId = 1
        )

        assertTrue(line, line.contains("12 modes"))
        assertTrue(line, line.contains("+4 more"))
        assertFalse(line, line.contains("12@61.000"))
    }

    @Test
    fun `a panel reporting no modes at all is not read as a panel that is fine`() {
        assertEquals(
            "mode 7 (not in the panel's list); the panel reports no modes at all",
            FrameRateMatch.describeModes(emptyList(), currentModeId = 7)
        )
    }

    @Test
    fun `the no-match line says how many modes were searched`() {
        // The single-mode case is the one that arrives from a TV that never
        // switches: film, and one 60 Hz mode that cannot be a multiple of it.
        assertEquals(
            "no whole multiple of 23.976 fps in the panel's only mode",
            FrameRateMatch.describeNoMatch(23.976, listOf(mode(1, 60.0)))
        )
        assertEquals(
            "no whole multiple of 23.976 fps in any of the 3 modes",
            FrameRateMatch.describeNoMatch(
                contentFps = 23.976,
                modes = listOf(mode(1, 60.0), mode(2, 30.0), mode(3, 25.0))
            )
        )
        assertEquals(
            "no usable frame rate was reported",
            FrameRateMatch.describeNoMatch(-1.0, listOf(mode(1, 60.0)))
        )
    }

    @Test
    fun `the surface request line names whether it was allowed to blank the screen`() {
        val request = FrameRateMatch.surfaceRateRequest(23.976, 33)
        assertNotNull(request)

        assertEquals(
            "asked the platform for 23.976 fps (a blank-screen switch is allowed)",
            FrameRateMatch.describeSurfaceRequest(request!!, allowNonSeamless = true)
        )
        assertEquals(
            "asked the platform for 23.976 fps (no blank-screen switch)",
            FrameRateMatch.describeSurfaceRequest(request, allowNonSeamless = false)
        )
    }

    @Test
    fun `the mode-switch line names the mode, the rate and what it replaced`() {
        assertEquals(
            "asked for mode 2 at 24.000 Hz for 23.976 fps (was mode 1)",
            FrameRateMatch.describeModeSwitch(23.976, mode(2, 24.0), wasModeId = 1)
        )
    }

    @Test
    fun `the outcome line separates a switch from a refusal`() {
        assertEquals(
            "the panel moved to mode 2 at 24.000 Hz",
            FrameRateMatch.describeOutcome(beforeModeId = 1, afterModeId = 2, afterRate = 24.0)
        )
        // The one worth reading: the request went out and nothing happened, so
        // the TV's own display setting is where to look next.
        assertEquals(
            "the panel is still in mode 1 \u2014 the request was not taken",
            FrameRateMatch.describeOutcome(beforeModeId = 1, afterModeId = 1, afterRate = 60.0)
        )
    }

    @Test
    fun `the path line matches what this Android version will actually do`() {
        assertTrue(FrameRateMatch.describePath(33).contains("Android 12+"))
        assertTrue(FrameRateMatch.describePath(31).contains("blank-screen switch allowed"))
        assertTrue(FrameRateMatch.describePath(30).contains("Android 11"))
        assertTrue(FrameRateMatch.describePath(29).contains("Android 10 and below"))
    }
}
