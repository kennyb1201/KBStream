package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule behind "the episode I finished was not marked watched and its
 * progress bar was still there": a session the player declared over, or one
 * left from the end-of-episode card in the closing minutes, is recorded as
 * watched.
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
                durationMs = duration
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
                durationMs = duration
            )
        )
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.95).toLong(),
                durationMs = duration
            )
        )
        assertTrue(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.90).toLong(),
                durationMs = duration
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
                durationMs = duration
            )
        )
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = (duration * 0.50).toLong(),
                durationMs = duration
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
                durationMs = duration
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
                durationMs = 0L
            )
        )
        assertFalse(
            shouldRecordCompletion(
                playbackEnded = false,
                endPanelsShown = true,
                positionMs = 0L,
                durationMs = 0L
            )
        )
    }
}
