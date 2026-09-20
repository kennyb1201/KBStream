package com.kennyb1201.kbstream.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New-episode notification decisions.
 *
 * Two failures matter equally here: missing a genuinely new episode, and
 * buzzing about one the user has already been told about (or about a whole
 * back catalogue the first time the app ever looks).
 */
class NewEpisodeRulesTest {

    private val now = 1_700_000_000_000L

    // ── episode keys ────────────────────────────────────────────────

    @Test
    fun `episode keys round-trip`() {
        assertEquals("S3E4", NewEpisodeRules.episodeKey(3, 4))
        assertEquals(3 to 4, NewEpisodeRules.parseEpisodeKey("S3E4"))
        assertEquals("S12E105", NewEpisodeRules.episodeKey(12, 105))
    }

    @Test
    fun `special and missing episode numbers have no key`() {
        assertNull(NewEpisodeRules.episodeKey(null, 4))
        assertNull(NewEpisodeRules.episodeKey(3, null))
        // TMDB uses 0 for specials/specials-adjacent entries.
        assertNull(NewEpisodeRules.episodeKey(0, 4))
        assertNull(NewEpisodeRules.episodeKey(3, 0))
    }

    @Test
    fun `malformed keys do not parse`() {
        assertNull(NewEpisodeRules.parseEpisodeKey("3:4"))
        assertNull(NewEpisodeRules.parseEpisodeKey("S3"))
        assertNull(NewEpisodeRules.parseEpisodeKey("SE"))
        assertNull(NewEpisodeRules.parseEpisodeKey("S0E4"))
        assertNull(NewEpisodeRules.parseEpisodeKey("S3E"))
    }

    // ── ordering ────────────────────────────────────────────────────

    @Test
    fun `a later episode is later`() {
        assertTrue(NewEpisodeRules.isLater(3 to 5, 3 to 4))
        assertTrue(NewEpisodeRules.isLater(4 to 1, 3 to 12))
    }

    @Test
    fun `the same or an earlier episode is not later`() {
        assertFalse(NewEpisodeRules.isLater(3 to 4, 3 to 4))
        assertFalse(NewEpisodeRules.isLater(3 to 1, 3 to 4))
        assertFalse(NewEpisodeRules.isLater(2 to 9, 3 to 1))
    }

    // ── should notify ───────────────────────────────────────────────

    @Test
    fun `a newer aired episode notifies once`() {
        assertTrue(NewEpisodeRules.shouldNotify("S1E1", "S1E2", now - 1000, now))
    }

    @Test
    fun `the same episode never notifies twice`() {
        assertFalse(NewEpisodeRules.shouldNotify("S1E2", "S1E2", now - 1000, now))
    }

    @Test
    fun `a first sighting is a baseline, not an alert`() {
        // Install day: without this, every followed show would fire at once.
        assertFalse(NewEpisodeRules.shouldNotify(null, "S5E10", now - 1000, now))
    }

    @Test
    fun `an episode that has not aired yet waits`() {
        assertFalse(NewEpisodeRules.shouldNotify("S1E1", "S1E2", now + 60_000, now))
    }

    @Test
    fun `an unknown air date is not worth a buzz`() {
        assertFalse(NewEpisodeRules.shouldNotify("S1E1", "S1E2", null, now))
    }

    @Test
    fun `an older episode than the snapshot stays quiet`() {
        // History that moved backwards (a show re-numbered, a stale cache)
        // must not re-announce something already seen.
        assertFalse(NewEpisodeRules.shouldNotify("S2E5", "S2E5", now - 1000, now))
        assertFalse(NewEpisodeRules.shouldNotify("S2E5", "S2E4", now - 1000, now))
    }

    @Test
    fun `a corrupt snapshot never notifies`() {
        assertFalse(NewEpisodeRules.shouldNotify("garbage", "S1E2", now - 1000, now))
    }

    @Test
    fun `an episode airing right now notifies`() {
        assertTrue(NewEpisodeRules.shouldNotify("S1E1", "S1E2", now, now))
    }

    // ── notification ids ────────────────────────────────────────────

    @Test
    fun `notification ids are stable and non-negative`() {
        val id = NewEpisodeRules.notificationId("tt1234567")
        assertEquals(id, NewEpisodeRules.notificationId("tt1234567"))
        assertTrue(id >= 0)
        // A newer episode for the same show replaces the previous alert.
        assertEquals(id, NewEpisodeRules.notificationId("tt1234567"))
    }

    // ── series detection ────────────────────────────────────────────

    @Test
    fun `series dialects are recognized`() {
        assertTrue(NewEpisodeRules.isSeriesType("series"))
        assertTrue(NewEpisodeRules.isSeriesType("tv"))
        assertTrue(NewEpisodeRules.isSeriesType("Shows"))
        assertTrue(NewEpisodeRules.isSeriesType(" anime "))
    }

    @Test
    fun `movies and blanks are not series`() {
        assertFalse(NewEpisodeRules.isSeriesType("movie"))
        assertFalse(NewEpisodeRules.isSeriesType(null))
        assertFalse(NewEpisodeRules.isSeriesType(""))
        assertFalse(NewEpisodeRules.isSeriesType("channel"))
    }
}
