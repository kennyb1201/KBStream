package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classification of mpv's one END_FILE event.
 *
 * The freeze this closes: a stream that loaded and then died mid-playback (a
 * segment answering 403, a reconnect that ran out of attempts) produced no log
 * line, no card, no callback and no retry — keep-open held the last frame and
 * the viewer was left looking at it. The same event, from a `loadfile replace`
 * of our own, used to be reported as an unplayable source instead, demoting the
 * add-on that had just been playing.
 */
class StreamEndRulesTest {

    private fun verdict(
        fileLoaded: Boolean = true,
        ourOwnStop: Boolean = false,
        released: Boolean = false,
        eofReached: Boolean = false,
        positionMs: Long = 60_000L,
        durationMs: Long = 1_200_000L
    ) = StreamEndRules.verdict(
        fileLoaded = fileLoaded,
        ourOwnStop = ourOwnStop,
        released = released,
        eofReached = eofReached,
        positionMs = positionMs,
        durationMs = durationMs
    )

    @Test
    fun `a file that never opened is the open-failure path`() {
        assertEquals(
            StreamEndRules.Verdict.OPEN_FAILED,
            verdict(fileLoaded = false, positionMs = 0L, durationMs = 0L)
        )
    }

    @Test
    fun `our own teardown is ignored`() {
        assertEquals(
            StreamEndRules.Verdict.IGNORE,
            verdict(released = true, positionMs = 60_000L)
        )
    }

    @Test
    fun `replacing a loaded file, or stopping it ourselves, is not a failure`() {
        // `loadfile replace` clears `fileLoaded` before the load, so the
        // outgoing file's END_FILE arrives looking exactly like a file that
        // never opened — which is how a source switch used to be reported as an
        // unplayable source, with the open-failure ladder demoting the add-on
        // that had been playing happily.
        assertEquals(
            StreamEndRules.Verdict.IGNORE,
            verdict(fileLoaded = false, ourOwnStop = true)
        )
    }

    @Test
    fun `an engine handoff's stop is not a failure either`() {
        // stopPlayback() issues mpv's own `stop`, which ends a LOADED file:
        // without this the switch to the other engine would raise the card it
        // was leaving behind.
        assertEquals(
            StreamEndRules.Verdict.IGNORE,
            verdict(fileLoaded = true, ourOwnStop = true, positionMs = 60_000L)
        )
    }

    @Test
    fun `reaching the end is not a failure even with no eof flag`() {
        // mpv raises eof-reached for an ordinary file end; a stream whose
        // demuxer ends without setting it still lands within the slack.
        assertEquals(
            StreamEndRules.Verdict.ENDED_NORMALLY,
            verdict(positionMs = 1_199_000L, durationMs = 1_200_000L)
        )
    }

    @Test
    fun `the eof flag alone is enough`() {
        assertEquals(
            StreamEndRules.Verdict.ENDED_NORMALLY,
            verdict(eofReached = true, positionMs = 0L)
        )
    }

    @Test
    fun `a stream without a duration is never called a failure`() {
        // A live channel, or a container that never reports a length: an end
        // here is indistinguishable from a normal one, and an error card over a
        // normal end is worse than the freeze this rule exists for.
        assertEquals(
            StreamEndRules.Verdict.IGNORE,
            verdict(positionMs = 900_000L, durationMs = 0L)
        )
    }

    @Test
    fun `a loaded stream that ended a minute in died mid-playback`() {
        assertEquals(
            StreamEndRules.Verdict.DIED_MID_STREAM,
            verdict(positionMs = 60_000L, durationMs = 1_200_000L)
        )
    }

    @Test
    fun `a stream that dies just inside the slack is a finished file`() {
        assertEquals(
            StreamEndRules.Verdict.ENDED_NORMALLY,
            verdict(positionMs = 1_196_000L, durationMs = 1_200_000L)
        )
        assertEquals(
            StreamEndRules.Verdict.DIED_MID_STREAM,
            verdict(positionMs = 1_190_000L, durationMs = 1_200_000L)
        )
    }

    @Test
    fun `a dead stream is never reported as an unplayable source`() {
        // The two callbacks are not interchangeable: onPlaybackError demotes the
        // add-on and walks to the next source, and a link that played for a
        // minute did not fail.
        assertEquals(
            StreamEndRules.Verdict.DIED_MID_STREAM,
            verdict(positionMs = 10_000L, durationMs = 600_000L)
        )
    }
}
