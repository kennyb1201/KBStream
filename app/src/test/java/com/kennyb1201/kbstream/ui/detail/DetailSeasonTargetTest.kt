package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The season the Detail screen opens on.
 *
 * Reported problem: every show opened on season 2. The walk used to start from
 * the season the history pointed at (or, with no history, from the show's first
 * season) and return the next season holding an unwatched episode — which a new
 * season always does, so the target was always "latest watched + 1", and season
 * 2 for a show nobody had watched yet.
 */
class DetailSeasonTargetTest {

    private val tenEpisodes = (1..10).toList()

    private fun pick(
        startSeason: Int,
        seasons: List<Int>,
        watched: Set<Pair<Int, Int>> = emptySet(),
        released: Set<Int> = seasons.toSet(),
        episodeNumbers: (season: Int) -> List<Int> = { tenEpisodes }
    ): Int = DetailSeasonTarget.pick(
        startSeason = startSeason,
        seasons = seasons,
        episodeNumbers = episodeNumbers,
        isWatched = { season, episode -> season to episode in watched },
        isReleased = { it in released }
    )

    private fun watchedSeason(season: Int, count: Int = 10): Set<Pair<Int, Int>> =
        (1..count).map { season to it }.toSet()

    @Test
    fun `no history opens the first season`() {
        assertEquals(1, pick(startSeason = 1, seasons = listOf(1, 2, 3)))
    }

    @Test
    fun `a season with unwatched episodes is not left behind`() {
        // Three of ten watched: this is the "everything opened on season 2"
        // regression, with the history pointing at season 1.
        assertEquals(
            1,
            pick(startSeason = 1, seasons = listOf(1, 2, 3), watched = watchedSeason(1, count = 3))
        )
    }

    @Test
    fun `a finished season advances to the next released one`() {
        assertEquals(
            2,
            pick(startSeason = 1, seasons = listOf(1, 2, 3), watched = watchedSeason(1))
        )
    }

    @Test
    fun `a run of finished seasons is walked past`() {
        assertEquals(
            3,
            pick(
                startSeason = 1,
                seasons = listOf(1, 2, 3),
                watched = watchedSeason(1) + watchedSeason(2)
            )
        )
    }

    @Test
    fun `an unreleased next season is skipped`() {
        assertEquals(
            3,
            pick(
                startSeason = 1,
                seasons = listOf(1, 2, 3),
                watched = watchedSeason(1),
                released = setOf(1, 3)
            )
        )
    }

    @Test
    fun `everything finished stays on the latest watched season`() {
        assertEquals(
            2,
            pick(
                startSeason = 2,
                seasons = listOf(1, 2, 3),
                watched = watchedSeason(1) + watchedSeason(2) + watchedSeason(3)
            )
        )
    }

    @Test
    fun `a season with no episode list is kept, not skipped`() {
        assertEquals(
            1,
            pick(
                startSeason = 1,
                seasons = listOf(1, 2, 3),
                episodeNumbers = { emptyList() }
            )
        )
    }

    @Test
    fun `a later season with no episode list is still opened`() {
        // The season being left is finished; the next one has no usable list,
        // and opening it beats replaying the finished one.
        assertEquals(
            2,
            pick(
                startSeason = 1,
                seasons = listOf(1, 2, 3),
                watched = watchedSeason(1),
                episodeNumbers = { season -> if (season == 2) emptyList() else tenEpisodes }
            )
        )
    }

    @Test
    fun `seasons are walked in order whatever order they arrive in`() {
        assertEquals(
            2,
            pick(
                startSeason = 1,
                seasons = listOf(3, 1, 2),
                watched = watchedSeason(1)
            )
        )
    }
}
