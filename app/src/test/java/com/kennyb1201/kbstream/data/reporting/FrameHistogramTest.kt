package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrolling frame meter's arithmetic (scrolling dig five, part 1).
 *
 * The sampler itself needs a device; the summariser is pure, so the numbers a
 * paste will carry are pinned here: a clean 60 Hz scroll reads 17/17/17, a
 * stuttering one shows up in p95 and the two counts, and a session too short to
 * mean anything is not reported at all.
 */
class FrameHistogramTest {

    @Test
    fun `a clean 60 Hz scroll has no misses`() {
        val stats = FrameHistogram.of(IntArray(60) { 17 })!!
        assertEquals(60, stats.frames)
        assertEquals(17, stats.p50Ms)
        assertEquals(17, stats.p95Ms)
        assertEquals(17, stats.maxMs)
        // 17 is over a 16 ms budget: the standard "over 16" count is expected to
        // be the whole clean sample. That is the honest reading at 60 Hz - what
        // matters is that nothing crossed 33.
        assertEquals(60, stats.over16Ms)
        assertEquals(0, stats.over33Ms)
    }

    @Test
    fun `a stutter lands in p95 and the hitch count, not the median`() {
        // 55 clean frames and 5 hitches (8% of the sample, past the 95th
        // percentile): the median must not move, the tail must.
        val deltas = IntArray(60) { if (it < 55) 16 else 50 }
        val stats = FrameHistogram.of(deltas)!!
        assertEquals(16, stats.p50Ms)
        assertEquals(50, stats.p95Ms)
        assertEquals(50, stats.maxMs)
        assertEquals(5, stats.over33Ms)
    }

    @Test
    fun `a session too short to be a scroll is not reported`() {
        assertNull(FrameHistogram.of(IntArray(0)))
        assertNull(FrameHistogram.of(IntArray(3) { 16 }))
        assertTrue(FrameHistogram.of(IntArray(FrameHistogram.MIN_FRAMES) { 16 }) != null)
    }

    @Test
    fun `non-positive deltas are dropped rather than averaged in`() {
        // The first delta after a begin is measured against nothing; a zero must
        // not drag the median down.
        val deltas = intArrayOf(0, -5, 16, 16, 16, 16, 16, 40)
        val stats = FrameHistogram.of(deltas)!!
        assertEquals(6, stats.frames)
        assertEquals(16, stats.p50Ms)
        assertEquals(40, stats.maxMs)
        assertEquals(1, stats.over33Ms)
    }

    // ── 2026-11-18 meter spec F2: longest wins per label ─────────────────

    @Test
    fun `a session just under the floor still prints nothing`() {
        // The stray step on the way to Settings is the case: five frames is a tap
        // and must not enter the ring at all, so it cannot displace a walk by
        // being the only thing there.
        assertNull(FrameHistogram.of(IntArray(FrameHistogram.MIN_FRAMES - 1) { 16 }))
    }

    @Test
    fun `a shorter session does not displace a longer kept one`() {
        // 7-frame tail versus the 64-frame walk it would have overwritten.
        assertTrue(!FrameHistogram.supersedes(frames = 7, keptFrames = 64))
    }

    @Test
    fun `a longer session replaces the kept one`() {
        assertTrue(FrameHistogram.supersedes(frames = 64, keptFrames = 7))
    }

    @Test
    fun `a tie keeps the newer line`() {
        // Equal length is replaced: a second full walk refreshes the numbers
        // instead of leaving the first one's on screen forever.
        assertTrue(FrameHistogram.supersedes(frames = 64, keptFrames = 64))
    }

    @Test
    fun `the first session for a label is always kept`() {
        assertTrue(FrameHistogram.supersedes(frames = 6, keptFrames = null))
    }

    // ── F4 (2026-11-19, churn): the frames a decode landed in ────────────

    @Test
    fun `a session with no decode says so, and a session with no frames says nothing`() {
        assertEquals(
            "the 15:52 paste printed no split on either frame line while " +
                "image: decode: reported 470 decodes, which read as a broken " +
                "instrument - it was not (those decodes landed in the walk's tail), " +
                "and the line now says which of the two happened",
            " decodeFrames=0",
            FrameHistogram.decodeSplit(null, decodeFrames = 0, sessionFrames = 31)
        )
        assertEquals(
            "a session that sampled nothing has nothing to add",
            "",
            FrameHistogram.decodeSplit(null, decodeFrames = 0, sessionFrames = 0)
        )
        assertEquals(
            "and a decode or two under the percentile floor still reports the count",
            " decodeFrames=2",
            FrameHistogram.decodeSplit(null, decodeFrames = 2, sessionFrames = 4)
        )
    }

    @Test
    fun `the decode half carries its own percentiles beside the whole`() {
        val all = FrameStats(
            frames = 177, p50Ms = 33, p95Ms = 83, maxMs = 183, over16Ms = 118, over33Ms = 78
        )
        val decode = FrameStats(
            frames = 61, p50Ms = 83, p95Ms = 150, maxMs = 183, over16Ms = 61, over33Ms = 61
        )
        val line = FrameHistogram.line("home-walk", all) +
            FrameHistogram.decodeSplit(decode, decodeFrames = 61, sessionFrames = 177)
        assertEquals(
            "the build-390 walk: 177 frames at p50=33ms with the decoded ones at " +
                "83ms says the loader is the stop frame; the same numbers with the " +
                "decoded ones at 16ms says it is the draw and the loader is cleared",
            "frame: home-walk frames=177 p50=33ms p95=83ms max=183ms >16=118 >33=78 " +
                "decodeFrames=61 decodeP50=83ms decodeP95=150ms decode>33=61",
            line
        )
    }

    @Test
    fun `the line names the surface and carries every number`() {
        val line = FrameHistogram.line(
            "guide",
            FrameStats(frames = 42, p50Ms = 16, p95Ms = 48, maxMs = 96, over16Ms = 9, over33Ms = 2)
        )
        assertEquals(
            "frame: guide frames=42 p50=16ms p95=48ms max=96ms >16=9 >33=2",
            line
        )
    }
}
