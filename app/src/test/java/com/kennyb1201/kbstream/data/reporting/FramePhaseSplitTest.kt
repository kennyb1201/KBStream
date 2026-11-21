package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk line's third half: where the frame time went (2026-11-21).
 *
 * `frame:` answers how the frames went and `decodeFrames` answers which of them
 * carried a bitmap. The warm walk that closed the 33 ms question left both of
 * those answered and the next one open: 250 frames at `p50=16ms` with only 26
 * carrying a decode, so 79 of the 105 slow frames had no decode in them at all.
 * "The loader is innocent" does not name a culprit, and the three remaining
 * fixes - composition scoping, draw cost, vsync pacing - live on different
 * threads.
 *
 * The sampler needs a Window; the summariser and the line are pure, so the
 * numbers a paste will carry are pinned here.
 */
class FramePhaseSplitTest {

    // ── the summariser keeps a zero, because a zero mm is a reading ──────

    @Test
    fun `a phase sample counts frames that spent nothing in it`() {
        // Two frames with no UI-thread time at all. `of` drops them (its zeros
        // are the first delta measured against nothing); phases must not, or the
        // median would answer "how long did the frames that worked take".
        val values = intArrayOf(0, 0, 16, 16, 16, 33)

        val all = FrameHistogram.phases(values)!!
        assertEquals("every frame is in the sample", 6, all.frames)
        assertEquals("the median is the median of the session", 16, all.p50Ms)

        assertNull(
            "the same array through `of` is under the floor once its zeros are " +
                "dropped - which is exactly the reading phases must not give",
            FrameHistogram.of(values)
        )
    }

    @Test
    fun `a phase sample under the floor prints no percentiles but keeps the count`() {
        assertNull(FrameHistogram.phases(IntArray(FrameHistogram.MIN_FRAMES - 1) { 16 }))
        assertTrue(FrameHistogram.phases(IntArray(FrameHistogram.MIN_FRAMES) { 16 }) != null)
    }

    // ── the line ─────────────────────────────────────────────────────────

    @Test
    fun `the three aggregates carry their own percentiles`() {
        val line = FrameHistogram.phaseSplit(
            ui = FrameStats(frames = 250, p50Ms = 6, p95Ms = 20, maxMs = 120, over16Ms = 12, over33Ms = 3),
            render = FrameStats(frames = 250, p50Ms = 8, p95Ms = 30, maxMs = 200, over16Ms = 40, over33Ms = 9),
            total = FrameStats(frames = 250, p50Ms = 15, p95Ms = 40, maxMs = 260, over16Ms = 90, over33Ms = 20),
            phaseFrames = 250,
            sessionFrames = 250
        )
        assertEquals(
            " phaseFrames=250 uiP50=6ms uiP95=20ms " +
                "renderP50=8ms renderP95=30ms totalP50=15ms totalP95=40ms",
            line
        )
    }

    @Test
    fun `a session that sampled no phases says so rather than going quiet`() {
        // The rule `decodeFrames` already follows: a blank suffix on a session
        // that DID measure frames reads as a broken instrument, and the 15:52
        // paste was read exactly that way.
        assertEquals(
            " phaseFrames=0",
            FrameHistogram.phaseSplit(null, null, null, phaseFrames = 0, sessionFrames = 31)
        )
        assertEquals(
            "a session that sampled nothing has nothing to add",
            "",
            FrameHistogram.phaseSplit(null, null, null, phaseFrames = 0, sessionFrames = 0)
        )
    }

    @Test
    fun `the warm walk's line reads as one sentence about one gesture`() {
        // The 2026-11-21 warm walk, which is the paste this instrument exists to
        // finish: a clean median, a tail that is not the loader, and - once the
        // phases land - which thread held the tail.
        val all = FrameStats(
            frames = 250, p50Ms = 16, p95Ms = 50, maxMs = 266, over16Ms = 105, over33Ms = 30
        )
        val decode = FrameStats(
            frames = 26, p50Ms = 33, p95Ms = 66, maxMs = 120, over16Ms = 26, over33Ms = 6
        )
        val line = FrameHistogram.line("home-walk", all) +
            FrameHistogram.decodeSplit(decode, decodeFrames = 26, sessionFrames = 250) +
            FrameHistogram.phaseSplit(
                ui = FrameStats(frames = 250, p50Ms = 6, p95Ms = 20, maxMs = 120, over16Ms = 12, over33Ms = 3),
                render = FrameStats(frames = 250, p50Ms = 8, p95Ms = 30, maxMs = 200, over16Ms = 40, over33Ms = 9),
                total = FrameStats(frames = 250, p50Ms = 15, p95Ms = 40, maxMs = 260, over16Ms = 90, over33Ms = 20),
                phaseFrames = 250,
                sessionFrames = 250
            )
        assertEquals(
            "frame: home-walk frames=250 p50=16ms p95=50ms max=266ms >16=105 >33=30 " +
                "decodeFrames=26 decodeP50=33ms decodeP95=66ms decode>33=6 " +
                "phaseFrames=250 uiP50=6ms uiP95=20ms " +
                "renderP50=8ms renderP95=30ms totalP50=15ms totalP95=40ms",
            line
        )
    }

    // ── the ui split ──────────────────────────────────────────────────────

    @Test
    fun `the ui aggregate is split into animation, layout and draw`() {
        // The 0.9.431 walk: uiP50=107ms with renderP50=8ms. "Composition, layout
        // or recordDisplayList" is three different fixes, and this is the line
        // that tells them apart.
        val line = FrameHistogram.uiParts(
            anim = FrameStats(frames = 25, p50Ms = 2, p95Ms = 9, maxMs = 30, over16Ms = 0, over33Ms = 0),
            layout = FrameStats(frames = 25, p50Ms = 92, p95Ms = 600, maxMs = 1800, over16Ms = 20, over33Ms = 18),
            draw = FrameStats(frames = 25, p50Ms = 13, p95Ms = 150, maxMs = 300, over16Ms = 7, over33Ms = 4),
            phaseFrames = 25,
            sessionFrames = 149
        )
        assertEquals(
            " uiAnimP50=2ms uiAnimP95=9ms" +
                " uiLayoutP50=92ms uiLayoutP95=600ms" +
                " uiDrawP50=13ms uiDrawP95=150ms",
            line
        )
    }

    @Test
    fun `an unsampled ui split adds nothing to a line that already says so`() {
        assertEquals(
            "",
            FrameHistogram.uiParts(null, null, null, phaseFrames = 0, sessionFrames = 149)
        )
        assertEquals(
            "a session with no frames has no parts",
            "",
            FrameHistogram.uiParts(null, null, null, phaseFrames = 0, sessionFrames = 0)
        )
    }
}
