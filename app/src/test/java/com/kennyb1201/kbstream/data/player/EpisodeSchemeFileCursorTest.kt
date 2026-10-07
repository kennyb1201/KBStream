package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The file-cursor anchored helpers behind the binge handout.
 *
 * A session entered at a NON-first segment (an episode tap, a Continue Watching
 * resume, a random pick) carries a TMDB label that is not the file's first
 * covered episode: the stream id is file-numbered while the launched intent
 * keeps the tapped TMDB number. Deriving the covered set and the next label
 * from that label (old arithmetic, still what [EpisodeScheme.advance] does)
 * marked the wrong episodes and drifted the rest of the binge. These pin the
 * cursor-anchored answers the players now use.
 */
class EpisodeSchemeFileCursorTest {

    private val sp2 = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
    private val fe2 = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)

    @Test
    fun `a doubled file's covered set comes from the file, not the label`() {
        // File 2 holds TMDB episodes 3 and 4.
        assertEquals(listOf(3, 4), sp2.tmdbEpisodesOfFile(2))
        assertEquals(listOf(3, 4), sp2.episodesOfFileClamped(2, maxEpisodes = 47))
    }

    @Test
    fun `the E4 tap on an sp2 file covers 3 and 4, not 4 and 5`() {
        // The audit's deterministic repro: tapping E4 starts file 2 (which
        // holds [3,4]) while the session label stays 4. The file's own set is
        // [3,4] - the old `label + factor` said [4,5], marking E5 unwatched and
        // leaving E3 unmarked.
        assertEquals(listOf(3, 4), sp2.episodesOfFileClamped(2, maxEpisodes = null))
        // ...and the old arithmetic is exactly why this had to be anchored:
        assertEquals(6, sp2.advance(2, 4).second)
    }

    @Test
    fun `the next label is the next file's first segment`() {
        // After file 2 ([3,4]) comes file 3, whose first segment is 5.
        assertEquals(5, sp2.labelForFile(3))
        // Not the session label plus the factor (6).
        assertEquals(6, sp2.advance(2, 4).second)
    }

    @Test
    fun `the season tail clamps the covered set but never empties it`() {
        // File 24 of an sp2 show covers [47,48]; a 47-episode season keeps only
        // the real one.
        assertEquals(listOf(47), sp2.episodesOfFileClamped(24, maxEpisodes = 47))
        // A cursor entirely past the season still answers with its own set.
        assertEquals(listOf(49, 50), sp2.episodesOfFileClamped(25, maxEpisodes = 47))
    }

    @Test
    fun `a split episode's label is the group the file belongs to`() {
        assertEquals(1, fe2.labelForFile(1))
        assertEquals(1, fe2.labelForFile(2))
        assertEquals(2, fe2.labelForFile(3))
        assertEquals(listOf(2), fe2.episodesOfFileClamped(3, maxEpisodes = null))
    }

    @Test
    fun `one to one is the identity`() {
        val one = EpisodeScheme.ONE_TO_ONE
        assertEquals(5, one.labelForFile(5))
        assertEquals(listOf(5), one.episodesOfFileClamped(5, maxEpisodes = null))
        assertEquals(listOf(5), one.episodesOfFileClamped(5, maxEpisodes = 5))
    }
}
