package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PlaybackEngineTrace] is what lets a diagnostics capture answer the one
 * playback question that had no answer before it existed: "after Skip Intro
 * this TV is out of decoder sources". The ladder in `NativePlayerActivity`
 * already logged every step under `PLAYER_RETRY` / `PLAYER_DV`, but logcat is
 * not reachable from a TV remote, so the failure was invisible in the artifact
 * that actually gets shared.
 *
 * The contract worth pinning is small and silent on failure: the ring must not
 * grow without bound, a clean session must not add a header at all, and the
 * sentence the ladder logs is the sentence the report shows — [describe] exists
 * so those cannot drift.
 *
 * [PlaybackEngineTrace] is a process-wide object, so every case calls `reset()`
 * on entry and the object under test is the only state involved.
 */
class PlaybackEngineTraceTest {

    @Test
    fun `a session with no decoder trouble has no line`() {
        PlaybackEngineTrace.reset()

        assertNull("absence is the answer to 'was this a decoder session'", PlaybackEngineTrace.summary())
        assertEquals(emptyList<String>(), PlaybackEngineTrace.lines())
    }

    @Test
    fun `the ring keeps events in order, oldest first`() {
        PlaybackEngineTrace.reset()

        PlaybackEngineTrace.note("first")
        PlaybackEngineTrace.note("second")

        assertEquals(listOf("first", "second"), PlaybackEngineTrace.lines())
    }

    @Test
    fun `a note is trimmed and a blank note is ignored`() {
        PlaybackEngineTrace.reset()

        PlaybackEngineTrace.note("  decoder resources exhausted (0x80001000)  ")
        PlaybackEngineTrace.note("   ")
        PlaybackEngineTrace.note("")

        assertEquals(listOf("decoder resources exhausted (0x80001000)"), PlaybackEngineTrace.lines())
    }

    @Test
    fun `the ring drops the oldest events past its cap`() {
        PlaybackEngineTrace.reset()

        // One more than the cap, named so the surviving window is obvious.
        for (i in 1..17) PlaybackEngineTrace.note("event $i")

        val kept = PlaybackEngineTrace.lines()
        assertEquals("the ring must not grow without bound", 16, kept.size)
        assertEquals("the oldest event must be the one dropped", "event 2", kept.first())
        assertEquals("event 17", kept.last())
    }

    @Test
    fun `the summary names the event count and joins the events`() {
        PlaybackEngineTrace.reset()

        PlaybackEngineTrace.note("decoder resources exhausted (0x80001000)")
        PlaybackEngineTrace.note("engine switch to MPV (decoder)")

        assertEquals(
            "decoders: 2 event(s) · decoder resources exhausted (0x80001000) · engine switch to MPV (decoder)",
            PlaybackEngineTrace.summary(),
        )
    }

    @Test
    fun `describe appends the detail only when there is one`() {
        assertEquals("no decoder for this format", PlaybackEngineTrace.describe("no decoder for this format"))
        assertEquals("no decoder for this format", PlaybackEngineTrace.describe("no decoder for this format", "  "))
        assertEquals(
            "no decoder for this format (video/avc)",
            PlaybackEngineTrace.describe("no decoder for this format", "video/avc"),
        )
    }

    @Test
    fun `reset clears the ring`() {
        PlaybackEngineTrace.note("decoder resources exhausted (0x80001000)")

        PlaybackEngineTrace.reset()

        assertNull(PlaybackEngineTrace.summary())
    }
}
