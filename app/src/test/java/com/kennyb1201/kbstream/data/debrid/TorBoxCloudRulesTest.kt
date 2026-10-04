package com.kennyb1201.kbstream.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a TorBox release name into a title + year, and matching that against
 * TMDB. Both are pure and both fail silently in production — a name parsed
 * wrongly searches for the wrong title, and a loose match adds a different film
 * to the Library — so the shapes the parser must cope with are pinned here.
 */
class TorBoxCloudRulesTest {

    // ── parsing release names ────────────────────────────────────────────────

    @Test
    fun `a dotted release name yields its title and year`() {
        val parsed = TorBoxCloudRules.parseName("The.Matrix.1999.1080p.BluRay.x264-GROUP")
        assertEquals("The Matrix", parsed?.title)
        assertEquals(1999, parsed?.year)
    }

    @Test
    fun `the last year wins so a title's own year stays in the title`() {
        val parsed = TorBoxCloudRules.parseName("Blade Runner 2049 2017 1080p BluRay")
        assertEquals("Blade Runner 2049", parsed?.title)
        assertEquals(2017, parsed?.year)
    }

    @Test
    fun `a season marker is stripped and leaves no year`() {
        val parsed = TorBoxCloudRules.parseName("Some.Show.S02.1080p.WEB-DL.x265")
        assertEquals("Some Show", parsed?.title)
        assertNull(parsed?.year)
    }

    @Test
    fun `an episode marker is stripped`() {
        val parsed = TorBoxCloudRules.parseName("Show.Name.S01E02.720p.HDTV")
        assertEquals("Show Name", parsed?.title)
        assertNull(parsed?.year)
    }

    @Test
    fun `without a year or quality token the whole name is the title`() {
        val parsed = TorBoxCloudRules.parseName("Ugly Title")
        assertEquals("Ugly Title", parsed?.title)
        assertNull(parsed?.year)
    }

    @Test
    fun `underscores and brackets are cleaned away`() {
        val parsed = TorBoxCloudRules.parseName("Show_Name_[1080p]")
        assertEquals("Show Name", parsed?.title)
    }

    @Test
    fun `a name with nothing usable parses to null`() {
        assertNull(TorBoxCloudRules.parseName("____"))
        assertNull(TorBoxCloudRules.parseName(""))
        assertNull(TorBoxCloudRules.parseName("1080p"))
    }

    @Test
    fun `a series marker is detected`() {
        assertTrue(TorBoxCloudRules.looksLikeSeries("Some.Show.S02.1080p"))
        assertTrue(TorBoxCloudRules.looksLikeSeries("Some Show Season 3"))
        assertTrue(!TorBoxCloudRules.looksLikeSeries("The.Matrix.1999.1080p"))
    }

    // ── matching against TMDB ────────────────────────────────────────────────

    private fun candidate(
        tmdbId: Int,
        title: String,
        mediaType: String = "movie",
        year: Int? = null,
        posterPath: String? = "/p.jpg",
        voteAverage: Double? = 7.0
    ) = TorBoxCloudRules.Candidate(
        mediaType = mediaType,
        tmdbId = tmdbId,
        title = title,
        year = year,
        posterPath = posterPath,
        voteAverage = voteAverage
    )

    private fun parsed(title: String, year: Int? = null) =
        TorBoxCloudRules.ParsedName(title = title, year = year)

    @Test
    fun `punctuation and case do not stop an exact match`() {
        val match = TorBoxCloudRules.bestMatch(
            parsed("Spider-Man No Way Home", 2021),
            listOf(candidate(1, "Spider-Man: No Way Home", year = 2021))
        )
        assertEquals(1, match?.tmdbId)
    }

    @Test
    fun `a sequel name never matches the original`() {
        val match = TorBoxCloudRules.bestMatch(
            parsed("The Matrix Reloaded", 2003),
            listOf(candidate(1, "The Matrix", year = 1999))
        )
        assertNull(match)
    }

    @Test
    fun `a year more than one apart is rejected`() {
        val match = TorBoxCloudRules.bestMatch(
            parsed("Dune", 2021),
            listOf(candidate(1, "Dune", year = 1984))
        )
        assertNull(match)
    }

    @Test
    fun `a one-year discrepancy is accepted`() {
        val match = TorBoxCloudRules.bestMatch(
            parsed("Movie", 2020),
            listOf(candidate(7, "Movie", year = 2019))
        )
        assertEquals(7, match?.tmdbId)
    }

    @Test
    fun `when a year is unknown the better-rated match wins`() {
        val match = TorBoxCloudRules.bestMatch(
            parsed("Ambiguous"),
            listOf(
                candidate(1, "Ambiguous", voteAverage = 5.0),
                candidate(2, "Ambiguous", voteAverage = 8.0)
            )
        )
        assertEquals(2, match?.tmdbId)
    }

    @Test
    fun `an empty candidate list yields no match`() {
        assertNull(TorBoxCloudRules.bestMatch(parsed("Anything"), emptyList()))
    }

    @Test
    fun `a candidate with no id is ignored`() {
        assertNull(
            TorBoxCloudRules.bestMatch(
                parsed("Movie"),
                listOf(candidate(0, "Movie"))
            )
        )
    }
}
