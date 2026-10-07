package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.SchemeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identifiers a playback session is filed under, and the one comparison the
 * app never used to make: the episode the id names against the episode the
 * session's own fields claim.
 *
 * Why it matters: the watch-history row, the watched marker, the Simkl scrobble
 * and the arithmetic next episode are all derived from `season`/`episode`,
 * while the stream was resolved for the id. When those disagree, an episode
 * plays and is filed as a different one — which the viewer sees as an already
 * watched episode being offered next, and as finished episodes with no marker.
 */
class PlaybackHistoryIdsTest {

    // ── the episode the id names ─────────────────────────────────────────────

    @Test
    fun `an episode id names its season and episode`() {
        assertEquals(3 to 26, PlaybackHistoryIds.episodeFromId("tt1534800:3:26"))
        assertEquals(1 to 1, PlaybackHistoryIds.episodeFromId("tt0903747:1:1"))
        // The show part is whatever the addon calls the show, so only the last
        // two segments are read.
        assertEquals(12 to 5, PlaybackHistoryIds.episodeFromId("kitsu:12:5"))
        assertEquals(3 to 26, PlaybackHistoryIds.episodeFromId(" tt1534800:3:26 "))
    }

    @Test
    fun `a movie or show-level id names no episode`() {
        assertNull(PlaybackHistoryIds.episodeFromId(null))
        assertNull(PlaybackHistoryIds.episodeFromId(""))
        assertNull(PlaybackHistoryIds.episodeFromId("tt0111161"))
        assertNull(PlaybackHistoryIds.episodeFromId("tmdb:603"))
        assertNull(PlaybackHistoryIds.episodeFromId("tt1534800:3"))
    }

    @Test
    fun `an id whose tail is not a number names no episode`() {
        // Addons do ship ids like this, and guessing a number out of them would
        // invent the very mismatch this is meant to catch.
        assertNull(PlaybackHistoryIds.episodeFromId("tt1534800:3:special"))
        assertNull(PlaybackHistoryIds.episodeFromId("tt1534800:3:"))
        assertNull(PlaybackHistoryIds.episodeFromId("tt1534800:s3:e26"))
    }

    // ── the row id a session files itself under ──────────────────────────────

    @Test
    fun `the row id prefers the episode id, which names the exact episode`() {
        assertEquals(
            "tt1534800:3:26",
            PlaybackHistoryIds.historyId("tt1534800", 3, 26, "tt1534800:3:26")
        )
        // No episode id: the show plus the numbers is the best identity there
        // is, and a blank id is the same as none.
        assertEquals(
            "tt1534800:3:26",
            PlaybackHistoryIds.historyId("tt1534800", 3, 26, null)
        )
        assertEquals(
            "tt1534800:3:26",
            PlaybackHistoryIds.historyId("tt1534800", 3, 26, "")
        )
        // A movie: the bare title id.
        assertEquals("tt0111161", PlaybackHistoryIds.historyId("tt0111161", null, null, null))
    }

    // ── the session line the diagnostics report carries ──────────────────────

    @Test
    fun `the session line flags an id that disagrees with the fields`() {
        val line = PlaybackHistoryIds.playbackSessionLine(
            season = 3,
            episode = 26,
            episodeStreamId = "tt1534800:3:23",
            historyId = "tt1534800:3:23"
        )

        assertTrue(line, line.contains("s=3 e=26"))
        assertTrue(line, line.contains("MISMATCH"))
        assertTrue(line, line.contains("id says s=3 e=23"))
    }

    @Test
    fun `an agreeing session says so`() {
        val line = PlaybackHistoryIds.playbackSessionLine(
            season = 3,
            episode = 26,
            episodeStreamId = "tt1534800:3:26",
            historyId = "tt1534800:3:26"
        )

        assertTrue(line, line.contains("agrees"))
    }

    // ── the same line under a detected scheme ────────────────────────────────
    //
    // A session's fields name a TMDB episode and its id names the FILE holding
    // it, so on a segmented show they are different numbers ON PURPOSE. A
    // watchdog that compared them raw called every correctly-mapped session of
    // such a show a MISMATCH; these pin the question it has to ask instead.

    @Test
    fun `a segmented session whose file holds the episode agrees`() {
        val line = PlaybackHistoryIds.playbackSessionLine(
            season = 5,
            episode = 11,
            episodeStreamId = "tmdb:57532:5:6",
            historyId = "tmdb:57532:5:6",
            scheme = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        )

        assertFalse(line, line.contains("MISMATCH"))
        assertTrue(line, line.contains("file 6 holds e=11"))
        // The mapping is named, so a reader can tell this from a 1:1 "agrees"
        // and from an off-by-one that merely looks close.
        assertTrue(line, line.contains("sp2"))
    }

    @Test
    fun `a segmented session whose file holds a different episode is a mismatch`() {
        // The observed line: file 6 holds TMDB 11-12, so filing this session as
        // e=8 is the drift the watchdog exists to catch - not the scheme
        // working, which the raw comparison could not tell apart.
        val line = PlaybackHistoryIds.playbackSessionLine(
            season = 5,
            episode = 8,
            episodeStreamId = "tmdb:57532:5:6",
            historyId = "tmdb:57532:5:6",
            scheme = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        )

        assertTrue(line, line.contains("MISMATCH"))
        assertTrue(line, line.contains("s=5 e=8"))
        assertTrue(line, line.contains("id says s=5 e=6"))
    }

    @Test
    fun `either half of a split episode agrees`() {
        val catDog = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        // Episode 8 of a split show lives in files 15 and 16; a session that
        // moved from the first half to the second is mapped correctly.
        listOf("tt1534800:5:15", "tt1534800:5:16").forEach { id ->
            val line = PlaybackHistoryIds.playbackSessionLine(
                season = 5,
                episode = 8,
                episodeStreamId = id,
                historyId = id,
                scheme = catDog
            )
            assertFalse("$id must not be a mismatch", line.contains("MISMATCH"))
            assertTrue(line, line.contains("file ${id.substringAfterLast(':')} holds e=8"))
        }
    }

    @Test
    fun `a scheme never forgives a wrong season`() {
        val line = PlaybackHistoryIds.playbackSessionLine(
            season = 5,
            episode = 11,
            episodeStreamId = "tmdb:57532:6:6",
            historyId = "tmdb:57532:6:6",
            scheme = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        )

        assertTrue(line, line.contains("MISMATCH"))
    }

    @Test
    fun `a movie under a scheme still has nothing to compare`() {
        val line = PlaybackHistoryIds.playbackSessionLine(
            season = null,
            episode = null,
            episodeStreamId = "tt0111161",
            historyId = "tt0111161",
            scheme = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        )

        assertEquals("s=- e=- row=tt0111161 id=tt0111161", line)
    }

    // ── the pair a session leaves in the resolution cache ────────────────────

    @Test
    fun `an imdb parent with a real tmdb id is worth recording`() {
        // Recording this is what lets a later "tmdb:<n>" route resolve the
        // title's canonical "tt..." flavor from disk, with no network - the
        // half of the widened resume lookup that a tt-first session used to
        // leave to an online-only resolve.
        assertTrue(recordsResolution("tt1534800", 1399))
        assertTrue(recordsResolution(" tt1534800 ", 1399))
    }

    @Test
    fun `nothing else is recorded`() {
        // A miss (null / a synthetic -1) must not forge a "tt..."->-1 mapping
        // that every later lookup would trust, and a non-imdb id has no IMDB
        // twin to name.
        assertFalse(recordsResolution("tt1534800", null))
        assertFalse(recordsResolution("tt1534800", 0))
        assertFalse(recordsResolution("tt1534800", -1))
        assertFalse(recordsResolution("tmdb:1399", 1399))
        assertFalse(recordsResolution("kitsu:12", 1399))
        assertFalse(recordsResolution("", 1399))
    }

    @Test
    fun `a session with no episode id says so rather than guessing`() {
        assertEquals(
            "s=3 e=26 row=tt1534800:3:26 id=null",
            PlaybackHistoryIds.playbackSessionLine(3, 26, null, "tt1534800:3:26")
        )
        // A movie session: no numbers to compare either.
        assertEquals(
            "s=- e=- row=tt0111161 id=tt0111161",
            PlaybackHistoryIds.playbackSessionLine(null, null, "tt0111161", "tt0111161")
        )
    }
}
