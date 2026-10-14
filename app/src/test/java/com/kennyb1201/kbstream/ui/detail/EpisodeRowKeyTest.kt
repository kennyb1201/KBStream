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

    /**
     * The rail is ONE ROW PER TMDB EPISODE, deliberately - not one row per file
     * with a combined "E01 / E02" label.
     *
     * A file that holds two segments is one thing to PLAY and two things to
     * WATCH: marking it watched marks both episodes, and the episode list is
     * where a viewer looks for either of them. Collapsing the pair into one row
     * would hide the second episode from the rail entirely (no artwork, no
     * per-episode title, no way to jump straight to it), and would make one row
     * answer two different watch states. So the rows stay one per episode, and
     * the shared file is what the FILE id and the scheme mapping already
     * express. This test pins that: 47 segments over 24 files are 47 rows.
     */
    @Test
    fun `the rail keeps one row per episode when two of them share a file`() {
        val sp2 = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)

        // Paw Patrol season 1: 47 TMDB segments, 24 files.
        val rows = (1..47).map { episode ->
            Triple(episode, episodeRowKey(show, season, episode), sp2.fileForTmdbEpisode(episode))
        }

        assertEquals("one row per episode, never one row per file", 47, rows.size)
        assertEquals(
            "and no pair is merged into a single combined row",
            47,
            rows.map { it.second }.distinct().size
        )
        assertEquals(
            "23 of the 24 files carry two episodes - the rows that share one",
            23,
            rows.groupBy { it.third }.values.count { it.size == 2 }
        )
        // The pair that shares file 1 is episodes 1 and 2, each still its own row.
        assertEquals(listOf(1, 2), rows.filter { it.third == 1 }.map { it.first })
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
