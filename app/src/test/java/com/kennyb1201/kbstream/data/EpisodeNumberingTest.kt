package com.kennyb1201.kbstream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that keeps a "0" from being read as an episode.
 *
 * The report this comes from: a viewer caught up on a season bar its last
 * episode saw Continue Watching say "season 2 episode 00", and every chip in
 * the season browser read "EPISODE 0" over blank artwork. Both screens render a
 * number straight from an upstream source, and both sources spell "no episode
 * here" as 0 - the tracker in a playback session it could not place, an add-on
 * in the video list it serves when it has no episode names. Nothing downstream
 * of them treats 0 as anything but a valid episode number, so the whole rail
 * and the whole season list read as zero.
 *
 * A 0 is never a real episode, so the conversion is total and this is the one
 * place it is decided.
 */
class EpisodeNumberingTest {

    @Test
    fun `an episode number a source named comes back unchanged`() {
        assertEquals(1, namedEpisodeNumber(1))
        assertEquals(8, namedEpisodeNumber(8))
        assertEquals(1_000, namedEpisodeNumber(1_000))
    }

    @Test
    fun `a zero is the sources' way of saying there is no episode`() {
        // Not 0, and never a default: null is what makes every caller fall
        // back to its own answer - the next unwatched episode TMDB knows
        // about, for a resume card.
        assertNull(namedEpisodeNumber(0))
    }

    @Test
    fun `an absent number stays absent`() {
        assertNull(namedEpisodeNumber(null))
    }

    @Test
    fun `a negative number is not an episode either`() {
        // Nothing sends one, but a rule with a loophole is not a rule: the
        // test is "positive", not "non-zero".
        assertNull(namedEpisodeNumber(-1))
        assertNull(namedEpisodeNumber(Int.MIN_VALUE))
    }
}
