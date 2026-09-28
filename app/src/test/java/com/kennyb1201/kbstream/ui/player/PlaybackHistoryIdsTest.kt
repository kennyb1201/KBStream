package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
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
