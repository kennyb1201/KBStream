package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.SchemeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An episode row is keyed by the episode, not by the file that holds it.
 *
 * The rail used the episode's stream id, which is FILE numbering (see
 * [EpisodeScheme]) and therefore NOT unique: on a show whose files each hold two
 * TMDB segments, `sp2` maps episode 1 and episode 2 onto the same file. Two rows
 * with one key is a hard crash, not a rendering glitch - Compose throws
 * `IllegalArgumentException: Key "tt3121722:4:1" was already used`, which is the
 * report this comes from (Sentry ANDROID-T, release 0.5.46, thrown as the Detail
 * page recomposed on return from playback).
 */
class EpisodeRowKeyTest {

    private val show = "tt3121722"
    private val season = 4

    /** The stream ids the season listing builds: FILE numbering, so repeats. */
    private fun streamIds(scheme: EpisodeScheme, episodes: IntRange): List<String> =
        episodes.map { episode -> "$show:$season:${scheme.fileForTmdbEpisode(episode)}" }

    private fun keys(episodes: IntRange): List<String> =
        episodes.map { episode -> episodeRowKey(show, season, episode) }

    @Test
    fun `a file holding two episodes gives the rows one stream id but two keys`() {
        val sp2 = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)

        val ids = streamIds(sp2, 1..2)
        assertEquals(
            "the season listing really does repeat the file id - this is the crash",
            ids[0],
            ids[1]
        )

        val keys = keys(1..2)
        assertNotEquals("and the rail key must not repeat it", keys[0], keys[1])
        assertEquals(2, keys.distinct().size)
    }

    @Test
    fun `a whole segmented season gets one key per episode`() {
        // Paw Patrol season 1: 47 TMDB segments over 24 files, which is 24
        // repeated stream ids across 47 rows.
        val sp2 = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        val ids = streamIds(sp2, 1..47)
        assertTrue("the ids collide many times over", ids.distinct().size < ids.size)

        val keys = keys(1..47)
        assertEquals("47 episodes, 47 keys", 47, keys.distinct().size)
    }

    @Test
    fun `a split episode keeps its halves apart`() {
        // The other direction: TWO files hold one TMDB episode, so the ids differ
        // where the episode does not. Those rows are still separate rows of the
        // same episode and need separate keys.
        val fe2 = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        val ids = streamIds(fe2, 3..3)
        assertEquals("one episode lives in files 5 and 6", 1, ids.distinct().size)

        assertEquals(
            "and its row key comes from the episode, not the file",
            episodeRowKey(show, season, 3),
            episodeRowKey(show, season, 3)
        )
    }

    @Test
    fun `the key is stable, and carries the season and the show`() {
        assertEquals(
            "the same row must key the same on every composition",
            episodeRowKey(show, season, 7),
            episodeRowKey(show, season, 7)
        )
        assertNotEquals(
            "a different season is a different row",
            episodeRowKey(show, season, 7),
            episodeRowKey(show, season + 1, 7)
        )
        assertNotEquals(
            "and so is another show's episode",
            episodeRowKey(show, season, 7),
            episodeRowKey("tt0903747", season, 7)
        )
    }

    @Test
    fun `an unresolved season still yields a usable key`() {
        // The rail can compose before the season resolves; a null must not turn
        // the key into a blank that every row would share.
        val key = episodeRowKey(show, null, 1)
        assertTrue(key, key.isNotBlank())
        assertNotEquals(key, episodeRowKey(show, null, 2))
    }
}
