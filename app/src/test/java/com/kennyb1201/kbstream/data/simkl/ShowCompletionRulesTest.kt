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

    /**
     * Reported bug: an episode the user never started showed up on Continue
     * Watching at "99% watched". A tracker session that late is a finished
     * record (the player treats 95% as complete), not a resume point.
     */
    @Test
    fun `a tracker session at the finished mark is not a resume point`() {
        assertTrue(
            ShowCompletionRules.isFinishedPlaybackSession(99f)
        )
        assertTrue(
            ShowCompletionRules.isFinishedPlaybackSession(
                ShowCompletionRules.FINISHED_PLAYBACK_PERCENT
            )
        )
        assertTrue(
            ShowCompletionRules.isFinishedPlaybackSession(100f)
        )

        // Below the mark there is still something to resume.
        assertFalse(
            ShowCompletionRules.isFinishedPlaybackSession(94.9f)
        )

        // No progress reported at all is not "finished".
        assertFalse(
            ShowCompletionRules.isFinishedPlaybackSession(null)
        )
    }

    /**
     * Reported gap: a show the user is caught up on (American Horror Story)
     * showed in other apps' Upcoming but not ours. Its card comes from this
     * rule: following and caught up on everything aired.
     */
    @Test
    fun `a caught up show with unaired episodes has an upcoming card`() {
        assertTrue(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = "watching",
                watchedEpisodesCount = 20,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 10
            )
        )

        // Simkl casing/spacing is not guaranteed.
        assertTrue(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = " Watching ",
                watchedEpisodesCount = 20,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 10
            )
        )
    }

    @Test
    fun `a show with aired episodes left is not an upcoming catch-up card`() {
        // Mid-season with an episode to resume: that belongs on Continue
        // Watching, not here.
        assertFalse(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = "watching",
                watchedEpisodesCount = 19,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 10
            )
        )
    }

    /**
     * Reported gap: on profiles with a tracker connected, Upcoming showed
     * new seasons but never the coming episode. The tracker's own unaired
     * tally used to veto the card, and it lags: a show still airing whose
     * remaining episodes - or whose announced next season - the tracker's
     * episode list has not caught up with reports NOTHING unaired while TMDB
     * has the next episode dated. The tally is out of the rule; TMDB answers
     * whether there is a dated episode, and a dateless answer is dropped by
     * the rail's schedule builder.
     */
    @Test
    fun `a caught up show the tracker has no unaired tally for is still asked about`() {
        assertTrue(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = "watching",
                watchedEpisodesCount = 30,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 0
            )
        )
    }

    /**
     * Reported gap: a show the user has finished a season of reads as
     * "completed" on the tracker, and Simkl only moves it back to "watching"
     * once the new season's first episode AIRS - so a returning show with an
     * announced season was invisible in Upcoming while other apps showed it.
     * Being CAUGHT UP with everything aired is what makes accepting
     * "completed" safe: there is nothing to resume, so the rail is the only
     * place the show can surface at all.
     */
    @Test
    fun `a finished show with a new season coming is an upcoming card`() {
        assertTrue(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = "completed",
                watchedEpisodesCount = 30,
                totalEpisodesCount = 40,
                notAiredEpisodesCount = 10
            )
        )

        // Simkl casing/spacing is not guaranteed.
        assertTrue(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = " COMPLETED ",
                watchedEpisodesCount = 30,
                totalEpisodesCount = 40,
                notAiredEpisodesCount = 10
            )
        )
    }

    /**
     * The same widening for a finished show: with the tally out of the rule,
     * a completely watched show is asked about too - TMDB has nothing dated
     * for a show that really is over, which is what keeps it off the rail.
     */
    @Test
    fun `a completed show with nothing unaired is still asked about`() {
        assertTrue(
            ShowCompletionRules.isCaughtUpUpcomingCandidate(
                status = "completed",
                watchedEpisodesCount = 30,
                totalEpisodesCount = 30,
                notAiredEpisodesCount = 0
            )
        )
    }

    @Test
    fun `a show the user is not following never becomes an upcoming card`() {
        // "hold" is a deliberate pause and "dropped" is off the list on
        // purpose; blank/unknown statuses are not a following signal either.
        for (
            status in listOf(
                "dropped",
                "hold",
                "plantowatch",
                null,
                "",
                "   "
            )
        ) {
            assertFalse(
                status.orEmpty(),
                ShowCompletionRules.isCaughtUpUpcomingCandidate(
                    status = status,
                    watchedEpisodesCount = 20,
                    totalEpisodesCount = 30,
                    notAiredEpisodesCount = 10
                )
            )
        }
    }
}
