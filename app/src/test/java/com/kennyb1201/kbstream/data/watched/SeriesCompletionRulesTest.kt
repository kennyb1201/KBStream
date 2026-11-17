package com.kennyb1201.kbstream.data.watched

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local checkmark rule (spec 2026-11-17).
 *
 * The check means ENDED and fully completed in history - not "nothing left to
 * watch right now". These legs are the spec's field tests, in the order the
 * ambiguity lives: an ended show earns it, a still-airing caught-up show must
 * NOT (it keeps the eye), and every unknown stays unfinished.
 */
class SeriesCompletionRulesTest {

    // Season 1 has 10 episodes, all aired; the show has ended.
    private val seasons = mapOf(1 to 10, 2 to 0)
    private val allAiredWatched = (1..10).map { 1 to it }.toSet()
    private val missingOne = (2..10).map { 1 to it }.toSet()

    @Test
    fun `an ended show with every aired episode watched is completed`() {
        assertTrue(
            SeriesCompletionRules.isCompleted(
                status = "Ended",
                completedEpisodes = allAiredWatched,
                seasonEpisodeCounts = mapOf(1 to 10),
                lastAiredSeason = 1,
                lastAiredEpisode = 10
            )
        )
    }

    @Test
    fun `a canceled show counts as ended`() {
        assertTrue(
            SeriesCompletionRules.isCompleted(
                status = "Canceled",
                completedEpisodes = allAiredWatched,
                seasonEpisodeCounts = mapOf(1 to 10),
                lastAiredSeason = 1,
                lastAiredEpisode = 10
            )
        )
        assertTrue(
            "the other spelling TMDB has used",
            SeriesCompletionRules.isCompleted(
                status = "cancelled",
                completedEpisodes = allAiredWatched,
                seasonEpisodeCounts = mapOf(1 to 10),
                lastAiredSeason = 1,
                lastAiredEpisode = 10
            )
        )
    }

    @Test
    fun `a still-airing caught-up show is NOT completed - it keeps the eye`() {
        // S2, the key guard: every aired episode is watched, but the show has
        // not ended, so the check must not leak onto it.
        assertFalse(
            SeriesCompletionRules.isCompleted(
                status = "Returning Series",
                completedEpisodes = allAiredWatched,
                seasonEpisodeCounts = mapOf(1 to 10),
                lastAiredSeason = 1,
                lastAiredEpisode = 10
            )
        )
        assertFalse(
            SeriesCompletionRules.isCompleted(
                status = "In Production",
                completedEpisodes = allAiredWatched,
                seasonEpisodeCounts = mapOf(1 to 10),
                lastAiredSeason = 1,
                lastAiredEpisode = 10
            )
        )
    }

    @Test
    fun `an ended show with an aired episode missing is not completed`() {
        // S3: the eye, not the check.
        assertFalse(
            SeriesCompletionRules.isCompleted(
                status = "Ended",
                completedEpisodes = missingOne,
                seasonEpisodeCounts = mapOf(1 to 10),
                lastAiredSeason = 1,
                lastAiredEpisode = 10
            )
        )
    }

    @Test
    fun `unknowns stay unfinished rather than guessing a check`() {
        assertFalse("no status", SeriesCompletionRules.isCompleted(null, allAiredWatched, mapOf(1 to 10), 1, 10))
        assertFalse("blank status", SeriesCompletionRules.isCompleted("", allAiredWatched, mapOf(1 to 10), 1, 10))
        assertFalse("an unknown status", SeriesCompletionRules.isCompleted("TBD", allAiredWatched, mapOf(1 to 10), 1, 10))
        assertFalse("no rows", SeriesCompletionRules.isCompleted("Ended", emptySet(), mapOf(1 to 10), 1, 10))
        // No per-season counts at all: a single-season show can still be proven
        // from its own aired frontier, but a multi-season one cannot prove the
        // earlier seasons and must not be checked on a guess.
        assertFalse(
            "no seasons reported for a multi-season show",
            SeriesCompletionRules.isCompleted(
                "Ended",
                allAiredWatched,
                emptyMap(),
                lastAiredSeason = 2,
                lastAiredEpisode = 3
            )
        )
        assertFalse(
            "no airing frontier",
            SeriesCompletionRules.isCompleted("Ended", allAiredWatched, mapOf(1 to 10), null, null)
        )
    }

    @Test
    fun `a mid-season frontier only asks for what has aired`() {
        // Last aired is 1x06 of a 10-episode season: the six that exist are
        // enough, and the four scheduled ones are not owed.
        val watched = (1..6).map { 1 to it }.toSet()
        assertTrue(
            SeriesCompletionRules.isCompleted("Ended", watched, seasons, lastAiredSeason = 1, lastAiredEpisode = 6)
        )
        assertFalse(
            "one short of the aired frontier",
            SeriesCompletionRules.isCompleted(
                "Ended",
                watched - (1 to 6),
                seasons,
                lastAiredSeason = 1,
                lastAiredEpisode = 6
            )
        )
    }

    @Test
    fun `status wording follows the Detail page`() {
        assertTrue(SeriesCompletionRules.isFinishedStatus("Ended"))
        assertTrue(SeriesCompletionRules.isFinishedStatus("  ended "))
        assertTrue(SeriesCompletionRules.isFinishedStatus("CANCELED"))
        assertFalse(SeriesCompletionRules.isFinishedStatus("Returning Series"))
        assertFalse(SeriesCompletionRules.isFinishedStatus("Planned"))
        assertFalse(SeriesCompletionRules.isFinishedStatus("Pilot"))
        assertFalse(SeriesCompletionRules.isFinishedStatus(null))
    }
}
