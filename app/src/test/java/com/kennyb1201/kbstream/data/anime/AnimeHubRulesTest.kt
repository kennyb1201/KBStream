package com.kennyb1201.kbstream.data.anime

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anime layer's pure rules (spec 2026-11-13).
 *
 * Everything here is a decision the ID hub, the Detail header or a tracker leg
 * makes without a network: a title match (the gate that stops a wrong anime from
 * lending its ids), an absolute episode count turned into TMDB's (season,
 * episode), the two header labels, and the services' status strings. The hub's
 * own chain is asserted in the contract test.
 */
class AnimeHubRulesTest {

    // ── F1: the title gate ────────────────────────────────────────────────

    @Test
    fun `a title matches across case, punctuation and spacing`() {
        assertTrue(animeTitleMatches("Attack on Titan", "attack on titan", null, emptyList()))
        assertTrue(
            animeTitleMatches(
                "Frieren Beyond Journeys End",
                "Sousou no Frieren",
                "Frieren: Beyond Journey's End",
                emptyList()
            )
        )
    }

    @Test
    fun `a subtitle is a different show, not a match`() {
        // A prefix match is exactly how a wrong anime would be picked, so the
        // rule is equality after normalization and nothing softer.
        assertFalse(
            animeTitleMatches(
                "Attack on Titan",
                "Attack on Titan: Junior High",
                null,
                emptyList()
            )
        )
        assertFalse(animeTitleMatches("The Office", "Office", null, emptyList()))
    }

    @Test
    fun `every name a service carries is compared`() {
        assertTrue(
            animeTitleMatches(
                showTitle = "Fullmetal Alchemist Brotherhood",
                title = "Hagane no Renkinjutsushi",
                titleEnglish = null,
                titles = listOf("\u92fc\u306e\u935b\u91d1\u8853\u5e2b", "Fullmetal Alchemist: Brotherhood")
            )
        )
        assertFalse(animeTitleMatches(null, "Anything", null, emptyList()))
        assertFalse(animeTitleMatches("   ", "Anything", null, emptyList()))
    }

    // ── F2: absolute progress -> (season, episode) ────────────────────────

    @Test
    fun `absolute progress walks the seasons in order`() {
        val seasons = listOf(1 to 12, 2 to 24)

        assertEquals(
            (1..12).map { 1 to it }.toSet(),
            animeWatchedEpisodes(12, seasons)
        )
        assertEquals(
            (1..12).map { 1 to it }.toSet() + setOf(2 to 1),
            animeWatchedEpisodes(13, seasons)
        )
    }

    @Test
    fun `specials are not part of the count`() {
        // TMDB lists season 0 as specials; AniList/Kitsu never count them.
        val withSpecials = listOf(0 to 40, 1 to 12)

        assertEquals(
            (1..12).map { 1 to it }.toSet(),
            animeWatchedEpisodes(12, withSpecials)
        )
    }

    @Test
    fun `progress past the last episode stops at it, and zero watches nothing`() {
        val seasons = listOf(1 to 12)

        assertEquals((1..12).map { 1 to it }.toSet(), animeWatchedEpisodes(400, seasons))
        assertTrue(animeWatchedEpisodes(0, seasons).isEmpty())
        assertTrue(animeWatchedEpisodes(-4, seasons).isEmpty())
        assertTrue(animeWatchedEpisodes(5, emptyList()).isEmpty())
    }

    // ── F2: the TMDB bridge ───────────────────────────────────────────────

    @Test
    fun `a tracker entry matches a TMDB name only exactly`() {
        val names = listOf("Attack on Titan", "Shingeki no Kyojin")

        assertTrue(animeTmdbTitleMatch(names, "Attack on Titan"))
        assertTrue(animeTmdbTitleMatch(names, "attack on titan"))
        assertFalse("a near miss is no card at all", animeTmdbTitleMatch(names, "Attack on Titan: Junior High"))
        assertFalse(animeTmdbTitleMatch(emptyList(), "Attack on Titan"))
        assertFalse(animeTmdbTitleMatch(names, null))
    }

    // ── F2: the services' status strings ──────────────────────────────────

    @Test
    fun `statuses map to the rail's three buckets`() {
        assertEquals(AnimeTrackerStatus.IN_PROGRESS, aniListStatus("CURRENT"))
        assertEquals(AnimeTrackerStatus.IN_PROGRESS, aniListStatus("REPEATING"))
        assertEquals(AnimeTrackerStatus.COMPLETED, aniListStatus("completed"))
        assertEquals(AnimeTrackerStatus.OTHER, aniListStatus("PLANNING"))
        assertEquals(AnimeTrackerStatus.OTHER, aniListStatus(null))

        assertEquals(AnimeTrackerStatus.IN_PROGRESS, kitsuStatus("current"))
        assertEquals(AnimeTrackerStatus.COMPLETED, kitsuStatus("COMPLETED"))
        assertEquals(AnimeTrackerStatus.OTHER, kitsuStatus("on_hold"))
    }

    // ── F3: the Detail labels ─────────────────────────────────────────────

    @Test
    fun `the AniList score is shown as a tenth, and never as a zero`() {
        assertEquals("AniList 8.5", aniListScoreLabel(85))
        assertEquals("AniList 8.4", aniListScoreLabel(84))
        assertEquals("AniList 10.0", aniListScoreLabel(100))
        assertNull(aniListScoreLabel(null))
        assertNull("an unscored title is not a bad score", aniListScoreLabel(0))
    }

    @Test
    fun `the next-airing line only exists when TMDB has no next episode`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 13)
        val airingAt = today.plusDays(7).atStartOfDay(zone).toEpochSecond()

        val label =
            aniListNextAiringLabel(
                episode = 9,
                airingAtSeconds = airingAt,
                tmdbHasNextEpisode = false,
                today = today,
                zone = zone
            )

        assertTrue("names the episode and its date: $label", label!!.startsWith("Next E9"))
        assertNull(
            "TMDB wins - one next-episode line, never two",
            aniListNextAiringLabel(9, airingAt, tmdbHasNextEpisode = true, today = today, zone = zone)
        )
        assertNull(
            "a past date is not a next episode",
            aniListNextAiringLabel(9, airingAt, false, today = today.plusDays(8), zone = zone)
        )
        assertNull(
            "no episode or no date is no line",
            aniListNextAiringLabel(null, airingAt, false, today = today, zone = zone)
        )
        assertNull(aniListNextAiringLabel(9, null, false, today = today, zone = zone))
    }

    @Test
    fun `the URL encoder escapes what the query values contain`() {
        assertEquals("tt0903747", animeUrlEncode("tt0903747"))
        assertEquals("a%20b", animeUrlEncode("a b"))
        assertEquals("Frieren%3A%20Beyond", animeUrlEncode("Frieren: Beyond"))
        assertTrue(jikanAnimeSearchUrl("Frieren: Beyond").contains("q=Frieren%3A%20Beyond"))
    }

    @Test
    fun `the hub's identity is empty until a service answers`() {
        assertFalse(AnimeIdentity().hasAnyId)
        assertTrue(AnimeIdentity(malId = 16498).hasAnyId)
        assertTrue(AnimeIdentity(kitsuId = "7442").hasAnyId)
    }

    @Test
    fun `a zone-sensitive date is not invented for another day`() {
        // The airing instant is one instant; the label is the viewer's date.
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 11, 13)
        val airingAt = today.plusDays(1).atTime(1, 0).atZone(zone).toEpochSecond()

        assertTrue(aniListNextAiringLabel(3, airingAt, false, today, zone) != null)
    }
}
