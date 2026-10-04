package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule behind "the episode I finished was not marked watched and its
 * progress bar was still there": a session the player declared over, or one
 * left from the end-of-episode card in the closing minutes, is recorded as
 * watched.
 *
 * It also pins the opposite complaint: four failed add-on sources in a row
 * that never played a frame were each filed as watched and auto-advanced while
 * the error card was up. A session that never played is never a completion,
 * however it ended.
 */
class PlayerCompletionRulesTest {

    private val duration = 1_000_000L

    @Test
    fun `the player's own end always completes`() {
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = true,
                endPanelsShown = false,
                positionMs = 0L,
                durationMs = duration,
                played = true
            )
        )
    }

    @Test
    fun `leaving from the card in the credits tail completes`() {
        // 95% of the runtime: the default card point (98%) and the last
        // minutes around it.
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.98).toLong(),
                durationMs = duration,
                played = true
            )
        )
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.95).toLong(),
                durationMs = duration,
                played = true
            )
        )
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.90).toLong(),
                durationMs = duration,
                played = true
            )
        )
    }

    @Test
    fun `a card opened mid-episode is not the end`() {
        // The panel point can be moved down to 80%; leaving there must stay a
        // resume point, not a completion.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.80).toLong(),
                durationMs = duration,
                played = true
            )
        )
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.50).toLong(),
                durationMs = duration,
                played = true
            )
        )
    }

    @Test
    fun `no card and no end stays a resume point`() {
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = (duration * 0.99).toLong(),
                durationMs = duration,
                played = true
            )
        )
    }

    @Test
    fun `an unknown duration only completes on the ended verdict`() {
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = true,
                endPanelsShown = true,
                positionMs = 0L,
                durationMs = 0L,
                played = true
            )
        )
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = 0L,
                durationMs = 0L,
                played = true
            )
        )
    }

    @Test
    fun `a session that never played is never completed`() {
        // The player's own ended verdict with no frame and no playhead: an
        // empty/errored source, or the stall fallback firing at position 0.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = true,
                endPanelsShown = false,
                positionMs = 0L,
                durationMs = duration,
                played = false
            )
        )
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = true,
                endPanelsShown = true,
                positionMs = 0L,
                durationMs = 0L,
                played = false
            )
        )
        // Nor can the card fallback finish a session that never played, however
        // far its (bogus) position reads.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = duration,
                durationMs = duration,
                played = false
            )
        )
    }

    /**
     * The transport's own Next, pressed in the credits before the
     * end-of-episode card had been raised.
     *
     * This is the "tick and a 2-minute progress bar on the same episode"
     * report: with no card the rule's fallback could not fire, so the handoff
     * filed a RESUME row locally while the same handoff's "stop" scrobble told
     * the tracker the episode was watched. Two systems, two verdicts, and the
     * rail went on offering to resume a finished episode.
     */
    @Test
    fun `an explicit advance inside the credits counts as finished`() {
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = (duration * 0.95).toLong(),
                durationMs = duration,
                played = true,
                explicitAdvance = true
            )
        )
    }

    @Test
    fun `an explicit advance before the credits is still a resume`() {
        // Next is also an ordinary mid-episode button: advancing from the
        // middle of an episode must still leave a resumable position rather
        // than ticking off an episode the viewer has not seen.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = duration / 2,
                durationMs = duration,
                played = true,
                explicitAdvance = true
            )
        )
    }

    @Test
    fun `an explicit advance cannot finish a session that never played`() {
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = duration,
                durationMs = duration,
                played = false,
                explicitAdvance = true
            )
        )
    }

    @Test
    fun `an unknown duration is still never completed by an advance`() {
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = 0L,
                durationMs = 0L,
                played = true,
                explicitAdvance = true
            )
        )
    }

    @Test
    fun `the exit path is unchanged by the advance rule`() {
        // onStop passes no advance, so leaving a session in the credits with
        // no card raised still files a resume point - the behavior that path
        // has always had, and the reason this rule is opt-in per call site.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = (duration * 0.95).toLong(),
                durationMs = duration,
                played = true
            )
        )
    }

    // --- isCreditsTail: the "leaving row even when not completed" window the
    // players use to ask Home for one more Continue Watching re-merge ---

    @Test
    fun `the credits tail starts at ninety percent`() {
        assertTrue(isCreditsTail(positionMs = (duration * 0.90).toLong(), durationMs = duration))
    }

    @Test
    fun `just under the tail is not in it`() {
        assertFalse(
            isCreditsTail(positionMs = (duration * 0.899).toLong(), durationMs = duration)
        )
    }

    @Test
    fun `an unknown duration is never a tail`() {
        assertFalse(isCreditsTail(positionMs = 5_000L, durationMs = 0L))
    }

    @Test
    fun `a zero playhead is never a tail`() {
        assertFalse(isCreditsTail(positionMs = 0L, durationMs = duration))
    }

    @Test
    fun `the whole runtime past the tail qualifies`() {
        assertTrue(isCreditsTail(positionMs = duration, durationMs = duration))
    }
}
