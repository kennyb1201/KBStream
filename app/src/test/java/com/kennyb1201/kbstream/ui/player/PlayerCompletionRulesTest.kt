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
}
