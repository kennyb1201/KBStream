package com.kennyb1201.kbstream.data.watched

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eye badge's caught-up rule. The cases that matter are the ones that
 * decide whether a locally-watched show keeps its eye: a gap in a finished
 * season, an unfinished newest season, and the two "we can't tell" shapes
 * that must fall back to the eye rather than hide it.
 */
class LocalSeriesProgressTest {

    private fun eps(vararg pairs: Pair<Int, Int>) = pairs.toSet()

    @Test
    fun `every aired episode watched is caught up`() {
        assertTrue(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 2, 1 to 3),
                seasonEpisodeCounts = mapOf(1 to 3),
                lastAiredSeason = 1,
                lastAiredEpisode = 3
            )
        )
    }

    @Test
    fun `a gap in a finished season is not caught up`() {
        assertFalse(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 3),
                seasonEpisodeCounts = mapOf(1 to 3),
                lastAiredSeason = 1,
                lastAiredEpisode = 3
            )
        )
    }

    @Test
    fun `an earlier season finished but the newest one untouched is not caught up`() {
        assertFalse(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 2),
                seasonEpisodeCounts = mapOf(1 to 2, 2 to 10),
                lastAiredSeason = 2,
                lastAiredEpisode = 4
            )
        )
    }

    @Test
    fun `a partly watched newest season is not caught up`() {
        assertFalse(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 2, 2 to 1),
                seasonEpisodeCounts = mapOf(1 to 2, 2 to 4),
                lastAiredSeason = 2,
                lastAiredEpisode = 4
            )
        )
    }

    @Test
    fun `scheduled episodes past the aired frontier are not required`() {
        // Season 2 declares 10 episodes but only 4 have aired; finishing 1-4
        // is caught up even though the declared count is higher.
        assertTrue(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 2, 2 to 1, 2 to 2, 2 to 3, 2 to 4),
                seasonEpisodeCounts = mapOf(1 to 2, 2 to 10),
                lastAiredSeason = 2,
                lastAiredEpisode = 4
            )
        )
    }

    @Test
    fun `a missing frontier is unknown and keeps the eye`() {
        assertFalse(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1),
                seasonEpisodeCounts = mapOf(1 to 1),
                lastAiredSeason = null,
                lastAiredEpisode = null
            )
        )
    }

    @Test
    fun `a missing count for a finished season is unknown and keeps the eye`() {
        assertFalse(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 2, 2 to 1),
                seasonEpisodeCounts = mapOf(2 to 1),
                lastAiredSeason = 2,
                lastAiredEpisode = 1
            )
        )
    }

    @Test
    fun `nothing watched is never caught up`() {
        assertFalse(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = emptySet(),
                seasonEpisodeCounts = mapOf(1 to 1),
                lastAiredSeason = 1,
                lastAiredEpisode = 1
            )
        )
    }

    @Test
    fun `the frontier season can fall back on the aired episode when no count is known`() {
        assertTrue(
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = eps(1 to 1, 1 to 2),
                seasonEpisodeCounts = emptyMap(),
                lastAiredSeason = 1,
                lastAiredEpisode = 2
            )
        )
    }
}
