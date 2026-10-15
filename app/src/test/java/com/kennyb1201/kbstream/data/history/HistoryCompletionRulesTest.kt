package com.kennyb1201.kbstream.data.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Completion is monotonic against the saves that RACED it, and only those.
 *
 * A save's verdict is decided on the main thread and written on IO, so a
 * pause-save decided an instant before the episode's end could land after the
 * completion write and put a resume row over the watched marker: the episode
 * lost its tick and returned to Continue Watching at 99%.
 *
 * The rule is an ORDER, not a permission — playing a finished episode again in
 * a later session still files resume progress — so the discriminating fact is
 * the save's own decision time against the stored completion's.
 */
class HistoryCompletionRulesTest {

    @Test
    fun `a save decided before the completion cannot overwrite it`() {
        assertTrue(
            HistoryCompletionRules.staleResumeOverCompletion(
                storedCompletedAt = 2_000L,
                incomingUpdatedAt = 1_999L
            )
        )
    }

    @Test
    fun `a save decided in the same millisecond as the completion is the race, and loses`() {
        // Both stamps come from `System.currentTimeMillis()` a few statements
        // apart, so equal is exactly the case the race produces.
        assertTrue(
            HistoryCompletionRules.staleResumeOverCompletion(
                storedCompletedAt = 2_000L,
                incomingUpdatedAt = 2_000L
            )
        )
    }

    @Test
    fun `a later save is a new session and wins`() {
        // The viewer played the finished episode again: its progress belongs on
        // the row, exactly as it did before the completion existed.
        assertFalse(
            HistoryCompletionRules.staleResumeOverCompletion(
                storedCompletedAt = 2_000L,
                incomingUpdatedAt = 2_001L
            )
        )
    }

    @Test
    fun `a row with no completion stamp takes every save`() {
        // Nothing to compare: rows completed before `completedAt` was tracked.
        // Failing open is the only option left, and it is the old behavior.
        assertFalse(
            HistoryCompletionRules.staleResumeOverCompletion(
                storedCompletedAt = null,
                incomingUpdatedAt = 1L
            )
        )
    }
}
