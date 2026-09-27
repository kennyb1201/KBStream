package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A show with nothing left to watch gets no Continue Watching card.
 *
 * Reported bug: a completely finished show still sat on the rail as "Next up",
 * and an episode watched the day before came back as the show's next episode -
 * while the detail page said "caught up". The series resolver's fall-through
 * returned the position it had STARTED from (the last watched episode) with
 * `episodesRemaining = 0`, and every caller reads a non-null target as "there
 * is something here".
 *
 * [hasNothingLeftToWatch] is the guard that turns that fall-through into a
 * null - i.e. "no next episode" - and these cases pin down where it must and
 * must not fire. The two exclusions matter as much as the rule: a show we
 * could not walk (offline) must not be hidden, and a tracker's queued next
 * episode outranks our own aired count.
 */
class CaughtUpContinueWatchingTest {

    @Test
    fun `every aired episode watched means nothing left to watch`() {
        assertTrue(
            hasNothingLeftToWatch(
                simklSeason = null,
                simklEpisode = null,
                watchedAiredEpisodes = 26,
                totalAiredEpisodes = 26
            )
        )
    }

    @Test
    fun `a stale tally above the total still counts as caught up`() {
        assertTrue(
            hasNothingLeftToWatch(
                simklSeason = null,
                simklEpisode = null,
                watchedAiredEpisodes = 27,
                totalAiredEpisodes = 26
            )
        )
    }

    @Test
    fun `an unwatched aired episode keeps the show on the rail`() {
        assertFalse(
            hasNothingLeftToWatch(
                simklSeason = null,
                simklEpisode = null,
                watchedAiredEpisodes = 25,
                totalAiredEpisodes = 26
            )
        )
    }

    @Test
    fun `a show that was never watched is untouched`() {
        assertFalse(
            hasNothingLeftToWatch(
                simklSeason = null,
                simklEpisode = null,
                watchedAiredEpisodes = 0,
                totalAiredEpisodes = 26
            )
        )
    }

    @Test
    fun `a tracker's queued next episode outranks the aired count`() {
        // Simkl can queue an episode TMDB has not aired or listed yet, and the
        // feed deliberately keeps such a show on the rail.
        assertFalse(
            hasNothingLeftToWatch(
                simklSeason = 3,
                simklEpisode = 1,
                watchedAiredEpisodes = 26,
                totalAiredEpisodes = 26
            )
        )

        // A half-hint is still a hint: the caller named where to start.
        assertFalse(
            hasNothingLeftToWatch(
                simklSeason = 3,
                simklEpisode = null,
                watchedAiredEpisodes = 26,
                totalAiredEpisodes = 26
            )
        )
    }

    @Test
    fun `an unwalked show is never hidden`() {
        // Every season lookup failed: an offline device cannot prove the
        // viewer is caught up, so the old behaviour stands.
        assertFalse(
            hasNothingLeftToWatch(
                simklSeason = null,
                simklEpisode = null,
                watchedAiredEpisodes = 0,
                totalAiredEpisodes = null
            )
        )

        assertFalse(
            hasNothingLeftToWatch(
                simklSeason = null,
                simklEpisode = null,
                watchedAiredEpisodes = 0,
                totalAiredEpisodes = 0
            )
        )
    }
}
