package com.kennyb1201.kbstream.ui.player

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two facts a session reads off the process's decoder pool: whether it has
 * ever worked here, and whether it just ran out.
 *
 * They are what separates the two 0x80001000 cases, which need opposite
 * recoveries — a pool that has rendered a frame is wedged by this session's own
 * rebuilds (skip the source ladder, hand to MPV), while a pool that refuses the
 * first configure may genuinely be looking at a file too big for the box (one
 * smaller source is legitimate).
 *
 * The object is deliberately process-wide and un-clearable in production: a
 * reset would let the next session walk the source ladder into a pool it has
 * already wedged. [DecoderPoolHealth.resetForTest] exists only for these tests,
 * so each one starts from a fresh process.
 */
class DecoderPoolHealthTest {

    @Before
    fun freshProcess() {
        DecoderPoolHealth.resetForTest()
    }

    @After
    fun leaveClean() {
        DecoderPoolHealth.resetForTest()
    }

    @Test
    fun `a fresh process has never decoded and never run out`() {
        assertFalse(DecoderPoolHealth.everRenderedFirstFrame)
        assertFalse(DecoderPoolHealth.everExhausted)
        assertFalse(DecoderPoolHealth.exhaustedRecently(nowMs = 1_000L))
    }

    @Test
    fun `a rendered frame latches the pool as working here`() {
        DecoderPoolHealth.noteFirstFrame()
        assertTrue(DecoderPoolHealth.everRenderedFirstFrame)
        // Latched, not observed: a second session must still see it, because
        // the pool working earlier in this process is exactly what makes a later
        // exhaustion our own rebuilds' doing.
        assertTrue(DecoderPoolHealth.everRenderedFirstFrame)
    }

    @Test
    fun `exhaustion is remembered with its time`() {
        DecoderPoolHealth.noteExhaustion(nowMs = 10_000L)
        assertTrue(DecoderPoolHealth.everExhausted)
        assertTrue(DecoderPoolHealth.exhaustedRecently(nowMs = 10_500L))
    }

    @Test
    fun `a stall inside the window is read as pool pressure`() {
        DecoderPoolHealth.noteExhaustion(nowMs = 10_000L)
        val inside = 10_000L + DecoderPoolHealth.EXHAUSTION_WINDOW_MS - 1
        assertTrue(DecoderPoolHealth.exhaustedRecently(nowMs = inside))
    }

    @Test
    fun `a stall after the window is a source problem again`() {
        // Past the window the pool has had its ~15s release and any retry
        // backoff, so a stall is evidence about the source once more and the
        // ordinary downshift applies.
        DecoderPoolHealth.noteExhaustion(nowMs = 10_000L)
        val after = 10_000L + DecoderPoolHealth.EXHAUSTION_WINDOW_MS
        assertFalse(DecoderPoolHealth.exhaustedRecently(nowMs = after))
        assertFalse(DecoderPoolHealth.exhaustedRecently(nowMs = after + 60_000L))
    }

    @Test
    fun `the window covers the vendor's fifteen second decoder release`() {
        assertTrue(
            "the window must outlast the ~15s the Realtek stack takes to hand a " +
                "decoder back, or the retry backoffs re-trigger inside it",
            DecoderPoolHealth.EXHAUSTION_WINDOW_MS >= 15_000L
        )
    }

    @Test
    fun `the latest exhaustion is the one that counts`() {
        DecoderPoolHealth.noteExhaustion(nowMs = 10_000L)
        DecoderPoolHealth.noteExhaustion(nowMs = 10_000L + DecoderPoolHealth.EXHAUSTION_WINDOW_MS)
        assertTrue(
            "a second exhaustion inside the session's life re-arms the window",
            DecoderPoolHealth.exhaustedRecently(
                nowMs = 10_000L + DecoderPoolHealth.EXHAUSTION_WINDOW_MS + 1_000L
            )
        )
    }

    @Test
    fun `a working frame and an exhaustion are independent facts`() {
        DecoderPoolHealth.noteFirstFrame()
        DecoderPoolHealth.noteExhaustion(nowMs = 5_000L)
        assertTrue(DecoderPoolHealth.everRenderedFirstFrame)
        assertTrue(DecoderPoolHealth.exhaustedRecently(nowMs = 5_100L))
    }
}
