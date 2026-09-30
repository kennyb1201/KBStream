package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pair a Continue Watching card prints.
 *
 * A resume row does not always name an episode. The reported shape was a card
 * reading "S02" with nothing after it and a hero line saying "0 of 310 aired
 * episodes watched" - a series row with a season but no named episode, which
 * the local builder used to read as "not an episode playback" and so never
 * resolved. The episode therefore has to be completed from the show's own
 * resolution, and only from there: a row that already names an episode is a
 * genuine resume and must keep its own pair untouched.
 *
 * The rule lives in HomeUpNext.kt rather than inside the builder for the same
 * reason the label rule does: the failure is silent and only shows up on a
 * television.
 */
class UpNextCardEpisodePairTest {

    @Test
    fun `a row that names an episode keeps its own pair`() {
        // A paused S4E5 is a real resume: the resolved continue point is the
        // same episode, and even if it were not, the card must point where the
        // viewer actually stopped.
        assertEquals(
            4 to 5,
            upNextCardEpisodePair(
                rowSeason = 4,
                rowEpisode = 5,
                resolvedSeason = 4,
                resolvedEpisode = 5
            )
        )

        // And a disagreeing resolution does not overwrite it.
        assertEquals(
            4 to 5,
            upNextCardEpisodePair(
                rowSeason = 4,
                rowEpisode = 5,
                resolvedSeason = 4,
                resolvedEpisode = 6
            )
        )
    }

    @Test
    fun `a season with no episode takes the resolved episode`() {
        // The reported card: season 2, no episode. The resolution walked
        // season 2 and knows the next unwatched episode there.
        assertEquals(
            2 to 8,
            upNextCardEpisodePair(
                rowSeason = 2,
                rowEpisode = null,
                resolvedSeason = 2,
                resolvedEpisode = 8
            )
        )
    }

    @Test
    fun `an episode the sources only called zero reads as no episode`() {
        // 0 is how a source spells "no episode" (see EpisodeNumbering), so this
        // row is the same case as a missing one - not an E00 to print.
        assertEquals(
            2 to 8,
            upNextCardEpisodePair(
                rowSeason = 2,
                rowEpisode = 0,
                resolvedSeason = 2,
                resolvedEpisode = 8
            )
        )
    }

    @Test
    fun `a row with neither half takes both from the resolution`() {
        assertEquals(
            3 to 1,
            upNextCardEpisodePair(
                rowSeason = null,
                rowEpisode = null,
                resolvedSeason = 3,
                resolvedEpisode = 1
            )
        )
    }

    @Test
    fun `an episode with no season keeps its episode and invents no season`() {
        // The resolution cannot know which season that episode belonged to, so
        // a season guessed for it would be a lie. Keep the half the row has.
        assertEquals(
            null to 5,
            upNextCardEpisodePair(
                rowSeason = null,
                rowEpisode = 5,
                resolvedSeason = 1,
                resolvedEpisode = 1
            )
        )
    }

    @Test
    fun `an unresolved show leaves the row exactly as it was`() {
        // Nothing resolved (offline / caught up): the season already on the row
        // still shows, and the episode stays unknown rather than guessed.
        assertEquals(
            2 to null,
            upNextCardEpisodePair(
                rowSeason = 2,
                rowEpisode = null,
                resolvedSeason = null,
                resolvedEpisode = null
            )
        )

        assertEquals(
            null to 5,
            upNextCardEpisodePair(
                rowSeason = null,
                rowEpisode = 5,
                resolvedSeason = null,
                resolvedEpisode = null
            )
        )
    }

    @Test
    fun `a movie row stays empty`() {
        val (season, episode) =
            upNextCardEpisodePair(
                rowSeason = null,
                rowEpisode = null,
                resolvedSeason = null,
                resolvedEpisode = null
            )

        assertNull(season)
        assertNull(episode)
    }
}
