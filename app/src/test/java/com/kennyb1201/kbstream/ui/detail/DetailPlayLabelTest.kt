package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Detail page's Play button says.
 *
 * The reported shape was a film with a saved position still offering "Play",
 * and a paused episode offering "Play S2E3" for a press that resumed it: the
 * label described the title, not the press. [detailPlayLabel] is the one rule
 * both screens' button reads now, so the label cannot be right on the phone and
 * wrong on the television.
 */
class DetailPlayLabelTest {

    @Test
    fun `a film that will resume says Resume`() {
        assertEquals("Resume", detailPlayLabel(isMovie = true, resuming = true))
    }

    @Test
    fun `a film with nothing to resume says Play`() {
        assertEquals("Play", detailPlayLabel(isMovie = true, resuming = false))
    }

    @Test
    fun `a paused episode names the episode it resumes`() {
        assertEquals(
            "Resume S2 E3",
            detailPlayLabel(isMovie = false, resuming = true, season = 2, episode = 3)
        )
    }

    @Test
    fun `an untouched episode says Play with its pair`() {
        assertEquals(
            "Play S2 E3",
            detailPlayLabel(isMovie = false, resuming = false, season = 2, episode = 3)
        )
    }

    @Test
    fun `the pair is never zero padded`() {
        // The label reads S1 E1, not S01 E01: it is a glance, not a column.
        assertEquals(
            "Play S1 E1",
            detailPlayLabel(isMovie = false, resuming = false, season = 1, episode = 1)
        )
        assertEquals("S1 E1", seasonEpisodeLabel(1, 1))
        assertEquals("S12 E103", seasonEpisodeLabel(12, 103))
    }

    @Test
    fun `a series whose pair is not known yet falls back to the bare verb`() {
        // Before the season listing lands there is no episode to promise, and
        // "Resume S2 E1" would be a guess about which episode plays.
        assertEquals("Play", detailPlayLabel(isMovie = false, resuming = false))
        assertEquals(
            "Play",
            detailPlayLabel(isMovie = false, resuming = true, season = 2, episode = null)
        )
        assertNull(seasonEpisodeLabel(2, null))
        assertNull(seasonEpisodeLabel(null, 3))
    }
}
