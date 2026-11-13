package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The large-seek resume rule (spec 2026-11-13).
 *
 * Field: scrubbing through almost the whole of a file played "really bad, real
 * stuttery" afterwards, while seeking a little at a time was fine. A far seek
 * discards the buffer and starts a cold re-fetch, which resumes on the ordinary
 * 5s after-rebuffer threshold - too little runway for a burst-gap source, so it
 * stalls again a moment later, repeatedly. The rule here is the one difference
 * between that case and a nudge: distance, then runway.
 */
class LargeSeekResumeRulesTest {

    // ── What counts as large ──────────────────────────────────────────────

    @Test
    fun `a jump bigger than the threshold in either direction is large`() {
        assertTrue(LargeSeekResumeRules.isLargeSeek(0L, 45 * 60_000L))
        assertTrue(LargeSeekResumeRules.isLargeSeek(45 * 60_000L, 2 * 60_000L))
        assertTrue(LargeSeekResumeRules.isLargeSeek(0L, 30_001L))
        assertTrue(LargeSeekResumeRules.isLargeSeek(30_001L, 0L))
    }

    @Test
    fun `nudges, ten-second steps and resume seeks are small`() {
        assertEquals(30_000L, LargeSeekResumeRules.LARGE_SEEK_THRESHOLD_MS)

        assertFalse(LargeSeekResumeRules.isLargeSeek(1_000L, 11_000L))
        assertFalse("the ±10s button", LargeSeekResumeRules.isLargeSeek(600_000L, 610_000L))
        assertFalse(LargeSeekResumeRules.isLargeSeek(600_000L, 590_000L))
        assertFalse("exactly at the threshold", LargeSeekResumeRules.isLargeSeek(0L, 30_000L))
        assertFalse(LargeSeekResumeRules.isLargeSeek(600_000L, 600_000L))
    }

    @Test
    fun `an unknown position is never treated as a large seek`() {
        // No capture, no comparison: the ordinary path, exactly as before this
        // rule existed.
        assertFalse(LargeSeekResumeRules.isLargeSeek(null, 5_000_000L))
        assertFalse(LargeSeekResumeRules.isLargeSeek(0L, null))
        assertFalse(LargeSeekResumeRules.isLargeSeek(null, null))
    }

    // ── The hold ──────────────────────────────────────────────────────────

    @Test
    fun `ten seconds of runway is what a large seek waits for`() {
        assertEquals(10_000L, LargeSeekResumeRules.RESUME_BUFFER_MS)
        assertTrue("twice the after-rebuffer threshold", LargeSeekResumeRules.RESUME_BUFFER_MS > 5_000L)
        assertTrue("and short of the 15s minBuffer", LargeSeekResumeRules.RESUME_BUFFER_MS < 15_000L)

        assertFalse(LargeSeekResumeRules.hasRunway(5_000L))
        assertFalse(LargeSeekResumeRules.hasRunway(9_999L))
        assertTrue(LargeSeekResumeRules.hasRunway(10_000L))
        assertTrue(LargeSeekResumeRules.hasRunway(60_000L))
    }

    @Test
    fun `the hold resumes the moment the buffer is ready`() {
        assertFalse(LargeSeekResumeRules.shouldResume(heldMs = 0L, bufferedMs = 4_000L, playerFailed = false))
        assertTrue(LargeSeekResumeRules.shouldResume(heldMs = 1_200L, bufferedMs = 10_000L, playerFailed = false))
    }

    @Test
    fun `a hold that never fills still lets go`() {
        // The bound that keeps a dead source from leaving the viewer paused:
        // after it expires the ordinary rebuffer path owns the session.
        val max = LargeSeekResumeRules.HOLD_MAX_MS
        assertEquals(20_000L, max)
        assertFalse(LargeSeekResumeRules.shouldResume(max - 1, bufferedMs = 0L, playerFailed = false))
        assertTrue(LargeSeekResumeRules.shouldResume(max, bufferedMs = 2_000L, playerFailed = false))
    }

    @Test
    fun `a failed player resumes at once`() {
        assertTrue(LargeSeekResumeRules.shouldResume(0L, bufferedMs = 0L, playerFailed = true))
    }

    @Test
    fun `the poll is frequent enough to resume promptly`() {
        assertTrue(LargeSeekResumeRules.HOLD_POLL_MS in 1L..250L)
    }
}
