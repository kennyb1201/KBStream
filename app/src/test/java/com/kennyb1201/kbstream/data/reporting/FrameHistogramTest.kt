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
