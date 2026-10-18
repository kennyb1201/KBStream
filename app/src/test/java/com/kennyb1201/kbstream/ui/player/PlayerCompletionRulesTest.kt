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

    // --- completionFiled: a completion that was WRITTEN is never re-decided ---

    /**
     * The report this flag exists for: pause ten minutes from the end, resume,
     * watch past the 90% threshold (which files the completion), then move the
     * playhead back - a rewind, or the pause/exit save that follows. Every
     * position-based arm below the flag would call that "not finished" and let
     * the save write the old resume point back over the watched marker; the
     * filed completion outranks them, so the episode stays done and its resume
     * point stays gone.
     */
    @Test
    fun `a filed completion outranks a mid-episode position`() {
        listOf(0.95, 0.90, 0.80, 0.50, 0.0).forEach { fraction ->
            assertTrue(
                "pos=${(duration * fraction).toLong()} must stay completed: the " +
                    "completion was already filed this session",
                shouldRecordCompletion(
                    playbackEnded = false,
                    endPanelsShown = false,
                    positionMs = (duration * fraction).toLong(),
                    durationMs = duration,
                    played = true,
                    completionFiled = true
                )
            )
        }
    }

    @Test
    fun `a filed completion does not need a known duration`() {
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = 0L,
                durationMs = 0L,
                played = true,
                completionFiled = true
            )
        )
    }

    @Test
    fun `a filed completion still cannot complete a session that never played`() {
        // The flag is a fact about a session that played; a caller that set it
        // on a session which never produced a frame is wrong, and the "nothing
        // was watched, so nothing is completed" rule still wins - this is what
        // keeps a run of dead add-on sources from filing themselves watched.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = duration,
                durationMs = duration,
                played = false,
                completionFiled = true
            )
        )
    }

    @Test
    fun `an unfiled session is unchanged by the new arm`() {
        // The flag defaults off, so every call site that does not pass it keeps
        // the rule it had.
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = false,
                positionMs = duration / 2,
                durationMs = duration,
                played = true
            )
        )
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = true,
                endPanelsShown = false,
                positionMs = duration / 2,
                durationMs = duration,
                played = true
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

    // --- errorAtCompletionThreshold: a source error at the tail is the end ---

    /** The ratio both engines hand in - their own COMPLETION_THRESHOLD_RATIO. */
    private val threshold = 0.95f

    /**
     * The field case: an episode resumed from a 77.43% pause point, played on
     * to 99.28%, and then the Matroska tail threw. The error-time playhead is
     * what has to decide this - the stored resume point would say 77.43% and
     * send the session back into the recovery ladder (and back to 77.43%).
     */
    @Test
    fun `an error in the tail of the runtime is the end`() {
        val runtime = 1_500_000L
        assertTrue(
            errorAtCompletionThreshold((runtime * 0.9928).toLong(), runtime, threshold)
        )
        // The stale resume point, for contrast: the same episode an hour after
        // a pause is NOT at the threshold, and must keep its recovery path.
        assertFalse(
            errorAtCompletionThreshold((runtime * 0.7743).toLong(), runtime, threshold)
        )
    }

    @Test
    fun `the error threshold is the save's own ninety-five percent`() {
        assertTrue(errorAtCompletionThreshold(positionMs = (duration * 0.95).toLong(), durationMs = duration, thresholdRatio = threshold))
        assertTrue(errorAtCompletionThreshold(positionMs = duration, durationMs = duration, thresholdRatio = threshold))
        // One millisecond under it is a mid-episode error, and keeps the card.
        assertFalse(
            errorAtCompletionThreshold(
                positionMs = (duration * 0.95).toLong() - 1L,
                durationMs = duration,
                thresholdRatio = threshold
            )
        )
        assertFalse(
            errorAtCompletionThreshold(
                positionMs = duration / 2,
                durationMs = duration,
                thresholdRatio = threshold
            )
        )
    }

    @Test
    fun `an error with no known duration keeps the normal recovery`() {
        // No length, no tail: the threshold cannot be applied to an unknown
        // runtime, and guessing it from the position would complete an episode
        // on a stream that never reported how long it was.
        assertFalse(errorAtCompletionThreshold(positionMs = 0L, durationMs = 0L, thresholdRatio = threshold))
        assertFalse(
            errorAtCompletionThreshold(
                positionMs = 1_500_000L,
                durationMs = 0L,
                thresholdRatio = threshold
            )
        )
    }

    @Test
    fun `an error at the very start is never the end`() {
        // The replay-from-the-top case: a source that errors at 0ms must not
        // file a completion, however short the file is.
        assertFalse(errorAtCompletionThreshold(positionMs = 0L, durationMs = 1_000L, thresholdRatio = threshold))
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
