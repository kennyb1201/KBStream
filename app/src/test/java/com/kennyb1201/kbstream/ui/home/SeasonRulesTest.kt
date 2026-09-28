package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season-finale rule the Home cards read.
 *
 * The bug these pin: the rule was "the target is the season's highest listed
 * episode", which is only true of a finished listing. TMDB opens a season with
 * whatever has been announced — right after a premiere that is episode 1 alone
 * — so a brand-new season's first episode counted as its last. The reported
 * card was "Universal Basic Guys" S03E01, the premiere of a just-started
 * season, wearing a SEASON FINALE badge where NEW SEASON belonged.
 */
class SeasonRulesTest {

    @Test
    fun `a one-episode listing is not a season, so it has no finale`() {
        assertFalse(
            SeasonRules.isSeasonFinale(
                episodeNumber = 1,
                seasonLength = 1
            )
        )
    }

    @Test
    fun `the last episode of a listed season is the finale`() {
        assertTrue(SeasonRules.isSeasonFinale(episodeNumber = 10, seasonLength = 10))
        assertTrue(SeasonRules.isSeasonFinale(episodeNumber = 8, seasonLength = 8))
        assertTrue(SeasonRules.isSeasonFinale(episodeNumber = 13, seasonLength = 13))
    }

    @Test
    fun `a mid-season episode is never the finale`() {
        assertFalse(SeasonRules.isSeasonFinale(episodeNumber = 5, seasonLength = 10))
        assertFalse(SeasonRules.isSeasonFinale(episodeNumber = 9, seasonLength = 13))
        // A lone premiere of a season known to be ten episodes long is the
        // same case as the bug, arrived at from the other direction.
        assertFalse(SeasonRules.isSeasonFinale(episodeNumber = 1, seasonLength = 10))
    }

    @Test
    fun `the shortest season that can end is two episodes, and not on its premiere`() {
        assertTrue(SeasonRules.isSeasonFinale(episodeNumber = 2, seasonLength = 2))
        assertFalse(SeasonRules.isSeasonFinale(episodeNumber = 1, seasonLength = 2))
    }

    @Test
    fun `an unmeasurable season is never a finale`() {
        assertFalse(SeasonRules.isSeasonFinale(episodeNumber = 0, seasonLength = 0))
        assertFalse(SeasonRules.isSeasonFinale(episodeNumber = 1, seasonLength = 0))
    }

    @Test
    fun `a season premiere is never a season finale, whatever the length`() {
        for (seasonLength in 0..40) {
            assertFalse(
                "E01 of a $seasonLength-episode season must not be a finale",
                SeasonRules.isSeasonFinale(
                    episodeNumber = 1,
                    seasonLength = seasonLength
                )
            )
        }
    }

    @Test
    fun `only the first episode is a season premiere`() {
        assertTrue(SeasonRules.isSeasonPremiere(1))
        // A tracker can queue episode 0 for the start of a season.
        assertTrue(SeasonRules.isSeasonPremiere(0))
        assertFalse(SeasonRules.isSeasonPremiere(2))
        assertFalse(SeasonRules.isSeasonPremiere(26))
    }
}
