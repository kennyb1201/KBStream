package com.kennyb1201.kbstream.data.airdates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The air-date reconciliation rule. These pin the one case that must override
 * TMDB - a date TMDB still puts in the future for an episode that has already
 * aired (American Horror Story "13": TMDB says 2026-10-01 for a season that
 * started 2026-09-24) - and, just as importantly, every case that must NOT:
 * a genuinely unreleased season, a premiere pushed back, and a missing or
 * unusable second-source answer all keep TMDB's own date.
 */
class AirDateCorrectionTest {

    private val today = LocalDate.of(2026, 9, 26)

    // ── correctAirDate ────────────────────────────────────────────────

    @Test
    fun `future TMDB date yields to an already-aired second source date`() {
        // The reported bug: the season is airing, TMDB still says Oct 1.
        assertEquals(
            "2026-09-24",
            AirDateCorrection.correctAirDate(
                primary = "2026-10-01",
                secondary = "2026-09-24",
                today = today
            )
        )
    }

    @Test
    fun `a second source that also has the date in the future changes nothing`() {
        assertEquals(
            "2026-10-01",
            AirDateCorrection.correctAirDate(
                primary = "2026-10-01",
                secondary = "2026-10-01",
                today = today
            )
        )
    }

    @Test
    fun `an already-aired date from the second source never overrides TMDB's own aired date`() {
        // Both are in the past and they disagree (a rerun/shift). TMDB is the
        // primary catalog: no correction.
        assertEquals(
            "2026-09-24",
            AirDateCorrection.correctAirDate(
                primary = "2026-09-24",
                secondary = "2026-09-18",
                today = today
            )
        )
    }

    @Test
    fun `a premiere pushed further out is left alone`() {
        // TMDB knows about its own schedule changes; a LATER second-source date
        // must not pull a not-yet-released season forward into "available".
        assertEquals(
            "2026-10-08",
            AirDateCorrection.correctAirDate(
                primary = "2026-10-08",
                secondary = "2026-10-15",
                today = today
            )
        )
    }

    @Test
    fun `a missing TMDB date takes the second source's`() {
        assertEquals(
            "2026-09-24",
            AirDateCorrection.correctAirDate(
                primary = null,
                secondary = "2026-09-24",
                today = today
            )
        )
        assertEquals(
            "2026-09-24",
            AirDateCorrection.correctAirDate(
                primary = "  ",
                secondary = "2026-09-24",
                today = today
            )
        )
    }

    @Test
    fun `an unusable second source answer is ignored`() {
        assertNull(AirDateCorrection.correctAirDate(primary = null, secondary = null, today = today))
        assertNull(AirDateCorrection.correctAirDate(primary = null, secondary = "", today = today))
        assertEquals(
            "2026-10-01",
            AirDateCorrection.correctAirDate(
                primary = "2026-10-01",
                secondary = "not a date",
                today = today
            )
        )
    }

    @Test
    fun `a malformed TMDB date is replaced rather than shown raw`() {
        assertEquals(
            "2026-09-24",
            AirDateCorrection.correctAirDate(
                primary = "2026-13-45",
                secondary = "2026-09-24",
                today = today
            )
        )
    }

    // ── correctPremieres ──────────────────────────────────────────────

    @Test
    fun `a season TMDB dates in the future takes the aired premiere date`() {
        val corrected = AirDateCorrection.correctPremieres(
            primary = mapOf(12 to LocalDate.of(2024, 9, 18), 13 to LocalDate.of(2026, 10, 1)),
            secondary = mapOf(13 to LocalDate.of(2026, 9, 24)),
            today = today
        )
        assertEquals(LocalDate.of(2026, 9, 24), corrected[13])
        // Untouched seasons keep their own dates.
        assertEquals(LocalDate.of(2024, 9, 18), corrected[12])
    }

    @Test
    fun `a genuinely upcoming season keeps its future premiere`() {
        val corrected = AirDateCorrection.correctPremieres(
            primary = mapOf(4 to LocalDate.of(2026, 11, 6)),
            secondary = mapOf(4 to LocalDate.of(2026, 11, 13)),
            today = today
        )
        assertEquals(LocalDate.of(2026, 11, 6), corrected[4])
    }

    @Test
    fun `seasons only the second source knows about are not invented`() {
        val corrected = AirDateCorrection.correctPremieres(
            primary = mapOf(3 to LocalDate.of(2026, 12, 1)),
            secondary = mapOf(13 to LocalDate.of(2026, 9, 24)),
            today = today
        )
        assertTrue(13 !in corrected)
    }

    @Test
    fun `an empty second source leaves the map untouched`() {
        val primary = mapOf(13 to LocalDate.of(2026, 10, 1))
        assertEquals(
            primary,
            AirDateCorrection.correctPremieres(primary, emptyMap(), today)
        )
    }

    // ── seasonPremieresFrom ───────────────────────────────────────────

    @Test
    fun `a season's premiere is its earliest episode date`() {
        val premieres = AirDateCorrection.seasonPremieresFrom(
            mapOf(
                "13:1" to "2026-09-24",
                "13:2" to "2026-09-24",
                "13:3" to "2026-10-01",
                "12:1" to "2024-09-18"
            )
        )
        assertEquals(LocalDate.of(2026, 9, 24), premieres[13])
        assertEquals(LocalDate.of(2024, 9, 18), premieres[12])
    }

    @Test
    fun `unparseable entries are skipped`() {
        val premieres = AirDateCorrection.seasonPremieresFrom(
            mapOf(
                "13:1" to "2026-09-24",
                "13:2" to "",
                "specials:1" to "2026-01-01",
                "12:1" to "TBA"
            )
        )
        assertEquals(mapOf(13 to LocalDate.of(2026, 9, 24)), premieres)
    }

    // ── nextAiring ────────────────────────────────────────────────────

    @Test
    fun `the next episode is the earliest one still to air`() {
        // American Horror Story: 13 again - TVmaze knows E1-E3 aired on
        // 2026-09-24 and E4 follows on 2026-10-01, so the show's real next
        // episode is E4, not the E1 TMDB was still pointing at.
        val airing = AirDateCorrection.nextAiring(
            mapOf(
                "13:1" to "2026-09-24",
                "13:2" to "2026-09-24",
                "13:3" to "2026-09-24",
                "13:4" to "2026-10-01"
            ),
            today = today
        )
        assertEquals(AirDateCorrection.Airing(13, 4, "2026-10-01"), airing)
    }

    @Test
    fun `episodes that have already aired are skipped`() {
        val airing = AirDateCorrection.nextAiring(
            mapOf("1:1" to "2020-01-01", "1:2" to "2020-01-08"),
            today = today
        )
        assertNull(airing)
    }

    @Test
    fun `a show with nothing left to air has no next episode`() {
        assertNull(AirDateCorrection.nextAiring(emptyMap(), today))
    }

    @Test
    fun `same-night episodes resolve to the lowest season and episode`() {
        val airing = AirDateCorrection.nextAiring(
            mapOf(
                "13:3" to "2026-10-01",
                "13:1" to "2026-10-01",
                "13:2" to "2026-10-01"
            ),
            today = today
        )
        assertEquals(AirDateCorrection.Airing(13, 1, "2026-10-01"), airing)
    }

    @Test
    fun `an episode airing today still counts as upcoming`() {
        val airing = AirDateCorrection.nextAiring(
            mapOf("13:4" to "2026-09-26"),
            today = today
        )
        assertEquals(AirDateCorrection.Airing(13, 4, "2026-09-26"), airing)
    }

    @Test
    fun `nextAiring ignores unusable entries`() {
        val airing = AirDateCorrection.nextAiring(
            mapOf(
                "specials:1" to "2026-10-01",
                "13:4" to "TBA",
                "13:5" to "2026-10-08"
            ),
            today = today
        )
        assertEquals(AirDateCorrection.Airing(13, 5, "2026-10-08"), airing)
    }

    @Test
    fun `episode keys and empty state`() {
        assertEquals("13:4", AirDateCorrection.episodeKey(13, 4))
        assertTrue(AirDateCorrections.NONE.isEmpty)
    }
}
