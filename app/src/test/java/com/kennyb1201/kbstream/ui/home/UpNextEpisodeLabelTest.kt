package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The season/episode label on a Continue Watching card.
 *
 * This is the exact string the viewer read as wrong: the card formats it in the
 * card's own corner, so a rail of them said "S02 · E00" next to blank artwork
 * for shows whose next episode was perfectly well known. The number came from a
 * source that writes 0 for "no episode", and the card printed it anyway.
 *
 * The rule lives in HomeUpNext.kt rather than inside the card so this can be
 * decided without a screen, which matters here: the failure is silent, it
 * compiles, and it is invisible until somebody is looking at a television.
 */
class UpNextEpisodeLabelTest {

    @Test
    fun `a real season and episode read as one pair`() {
        assertEquals("S02 · E08", upNextEpisodeLabel(2, 8))
        assertEquals("S01 · E01", upNextEpisodeLabel(1, 1))
        assertEquals("S12 · E103", upNextEpisodeLabel(12, 103))
    }

    @Test
    fun `an episode the sources only called zero is not printed`() {
        // The reported shape: season 2, episode 0, artwork blank. The label
        // must fall back to the season alone rather than invent an E00.
        assertEquals("S02", upNextEpisodeLabel(2, 0))
        assertNull(upNextEpisodeLabel(null, 0))
    }

    @Test
    fun `a missing episode leaves the season on its own`() {
        assertEquals("S02", upNextEpisodeLabel(2, null))
    }

    @Test
    fun `a missing season leaves the episode on its own`() {
        assertEquals("E08", upNextEpisodeLabel(null, 8))
    }

    @Test
    fun `a card with neither half has no label`() {
        assertNull(upNextEpisodeLabel(null, null))
    }

    @Test
    fun `season zero is a real season - specials - and still reads`() {
        // TMDB keeps a show's specials in season 0, so unlike an episode
        // number a zero season is a place a card can legitimately point at.
        assertEquals("S00 · E03", upNextEpisodeLabel(0, 3))
        assertEquals("S00", upNextEpisodeLabel(0, 0))
    }

    // --- The hero's line --------------------------------------------------

    @Test
    fun `the hero spells the pair the same way the card does`() {
        assertEquals("Resume  •  S02 · E08", upNextHeroEpisodeLabel("Resume", 2, 8))
        assertEquals("Next Up  •  S01 · E01", upNextHeroEpisodeLabel("Next Up", 1, 1))
    }

    @Test
    fun `a zero episode never reaches the hero line either`() {
        // The card's corner was fixed first, and the hero kept reading
        // "Resume  •  S02 · E00" for the same item - the report that the fix
        // had not taken.
        assertEquals("Resume  •  S02", upNextHeroEpisodeLabel("Resume", 2, 0))
        assertEquals("Resume", upNextHeroEpisodeLabel("Resume", null, 0))
    }

    @Test
    fun `a prefix with nothing to attach to stays a bare prefix`() {
        assertEquals("Resume", upNextHeroEpisodeLabel("Resume", null, null))
        assertEquals(
            "Airs Tomorrow  •  S00 · E03",
            upNextHeroEpisodeLabel("Airs Tomorrow", 0, 3)
        )
    }
}
