package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker rules behind the poster badges.
 *
 * Reported bug: manually marking a series watched "up to where my progress
 * is" painted the finished CHECKMARK even though the show was still airing,
 * instead of the eye marker for a started-but-unfinished show. The cause was
 * the aired-episode tally rule (watched >= aired) declaring a caught-up show
 * finished.
 */
class ShowCompletionRulesTest {

    @Test
    fun `a caught-up but still airing show is not finished`() {
        // 20 of 30 episodes watched, 10 still to air: every AIRED episode is
        // watched, but the show is not finished - this must be the eye badge.
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "watching",
                watchedEpisodesCount = 20,
                totalEpisodesCount = 30,
                nextToWatch = null
            )
        )

        // Same show, next episode already listed: still not finished.
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "watching",
                watchedEpisodesCount = 20,
                totalEpisodesCount = 30,
                nextToWatch = "S3E1"
            )
        )
    }

    @Test
    fun `every episode watched is finished`() {
        assertTrue(
            ShowCompletionRules.isFullyWatched(
                status = "watching",
                watchedEpisodesCount = 30,
                totalEpisodesCount = 30,
                nextToWatch = null
            )
        )
    }

    @Test
    fun `a finished status is finished whatever the counts say`() {
        for (status in listOf("completed", "ended", "canceled", " COMPLETED ")) {
            assertTrue(
                status,
                ShowCompletionRules.isFullyWatched(
                    status = status,
                    watchedEpisodesCount = 12,
                    totalEpisodesCount = 12,
                    nextToWatch = null
                )
            )
        }
    }

    @Test
    fun `an episode still waiting to be watched means unfinished`() {
        // The counters claim a full tally, but Simkl still lists an episode
        // to watch: the tally lost, the show stays unfinished (the eye).
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "watching",
                watchedEpisodesCount = 5,
                totalEpisodesCount = 5,
                nextToWatch = "S2E1"
            )
        )
    }

    @Test
    fun `an unknown episode count never counts as finished`() {
        // Simkl sometimes omits the counters entirely; guessing "finished"
        // there is what left posters stuck on a checkmark nobody earned.
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "watching",
                watchedEpisodesCount = 4,
                totalEpisodesCount = null,
                nextToWatch = null
            )
        )
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = null,
                watchedEpisodesCount = null,
                totalEpisodesCount = null,
                nextToWatch = null
            )
        )
    }

    @Test
    fun `a dropped or on-hold show is not finished`() {
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "hold",
                watchedEpisodesCount = 8,
                totalEpisodesCount = 24,
                nextToWatch = null
            )
        )
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "dropped",
                watchedEpisodesCount = 8,
                totalEpisodesCount = 24,
                nextToWatch = null
            )
        )
    }

    /**
     * Continue Watching keeps its own, looser rule: a caught-up show has
     * nothing to resume, so it must stay off the rail even though its poster
     * badge is the eye.
     */
    @Test
    fun `a caught-up show stays off continue watching`() {
        assertTrue(
            ShowCompletionRules.isCaughtUpOnAiredEpisodes(
                watchedEpisodesCount = 20,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 10
            )
        )

        // Still mid-season: there is something to resume.
        assertFalse(
            ShowCompletionRules.isCaughtUpOnAiredEpisodes(
                watchedEpisodesCount = 19,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 10
            )
        )

        // No counters at all: do not claim the show is caught up.
        assertFalse(
            ShowCompletionRules.isCaughtUpOnAiredEpisodes(
                watchedEpisodesCount = null,
                totalEpisodesCount = null,
                notAiredEpisodesCount = null
            )
        )
    }
}
