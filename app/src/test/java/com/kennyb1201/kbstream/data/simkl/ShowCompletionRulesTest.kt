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

    // Simkl's tally (watched_episodes_count / total_episodes_count) decides
    // whenever it is present; the show's list status only fills the gap when
    // there is no tally at all.

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
    fun `a finished status with every episode watched is finished`() {
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
    fun `a stale completed status over an incomplete tally is not finished`() {
        // Unmarking one season of a show marked watched as a whole leaves
        // exactly this on the tracker: the show still says "completed" while
        // its own tally knows a season is unwatched. The tally wins, so the
        // poster goes back to the eye instead of the checkmark.
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "completed",
                watchedEpisodesCount = 20,
                totalEpisodesCount = 30,
                nextToWatch = null
            )
        )
    }

    @Test
    fun `a finished status with no tally to go on is enough`() {
        assertTrue(
            ShowCompletionRules.isFullyWatched(
                status = "completed",
                watchedEpisodesCount = null,
                totalEpisodesCount = null,
                nextToWatch = null
            )
        )
    }

    @Test
    fun `an episode still listed as next to watch means unfinished`() {
        // No tally at all, and Simkl still lists something to watch: that is
        // an unfinished show (the eye), not a finished one.
        assertFalse(
            ShowCompletionRules.isFullyWatched(
                status = "watching",
                watchedEpisodesCount = null,
                totalEpisodesCount = null,
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
