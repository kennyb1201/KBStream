package com.kennyb1201.kbstream.ui.home

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure helpers extracted out of HomeViewModel: the S·E line, the key the
 * up-next rail regroups by, the progress bar's position, the catalog name and
 * media type an addon's row is spelled with, and the date arithmetic behind
 * the upcoming rails.
 *
 * They were private members of the view model, which is why none of this was
 * pinned before - the only way to reach them was to build the whole class.
 * Nothing here touches state, a database or the network, so every case is
 * decided in the same way the rails decide it.
 */
class HomeRulesTest {

    // --- Episode labels ---------------------------------------------------

    @Test
    fun `the season and episode line drops what is missing`() {
        assertEquals("S3E4", formatSeasonEpisode(3, 4))
        assertEquals("S3", formatSeasonEpisode(3, null))
        assertEquals("E4", formatSeasonEpisode(null, 4))
        assertEquals("", formatSeasonEpisode(null, null))
    }

    @Test
    fun `an episode key reads back the show it belongs to`() {
        assertEquals(Triple("show", 3, 4), parseEpisodeKey("show:3:4"))
        // Both separators are required: the season marker does not stand in
        // for the episode's own colon.
        assertEquals(Triple("show", 3, 4), parseEpisodeKey("show:S3:E4"))
        assertNull(parseEpisodeKey("show:S3E4"))
        // The key is trimmed before it is read, and its case does not matter.
        assertEquals(Triple("show", 3, 4), parseEpisodeKey("  show:s3:E4  "))
        // The show id is allowed to contain the separator itself.
        assertEquals(Triple("a:b", 1, 2), parseEpisodeKey("a:b:1:2"))
    }

    @Test
    fun `anything that is not a whole season and episode pair is not a key`() {
        assertNull(parseEpisodeKey("show"))
        assertNull(parseEpisodeKey("show:3"))
        assertNull(parseEpisodeKey("show:x:y"))
        assertNull(parseEpisodeKey(":3:4"))
    }

    @Test
    fun `the progress bar keeps a sliver at each end`() {
        // Nothing to show, and no division by zero.
        assertNull(progressFromHistory(0L, 1_000L))
        assertNull(progressFromHistory(1_000L, 0L))
        assertNull(progressFromHistory(-1L, 1_000L))

        assertEquals(0.5f, progressFromHistory(500L, 1_000L)!!, 0.0001f)
        // A film that has barely started still draws a visible sliver.
        assertEquals(0.005f, progressFromHistory(1L, 1_000L)!!, 0.0001f)
        // And one that is all but over never draws a full bar.
        assertEquals(0.99f, progressFromHistory(1_000L, 1_000L)!!, 0.0001f)
    }

    // --- Catalog spelling -------------------------------------------------

    @Test
    fun `a raw catalog name is title-cased for the rail`() {
        assertEquals("Top Movies", formatCatalogName("top_movies"))
        assertEquals("Top Movies", formatCatalogName("Top Movies"))
        assertEquals("Trending Now", formatCatalogName("trending_now"))
    }

    @Test
    fun `only the two media types the rest of the app knows survive`() {
        assertEquals("movie", normalizeMediaType("Movie"))
        assertEquals("series", normalizeMediaType("series"))
        assertEquals("series", normalizeMediaType("SHOW"))
        assertEquals("series", normalizeMediaType("tv"))
        assertNull(normalizeMediaType("book"))
        assertNull(normalizeMediaType(null))
    }

    // --- Dates ------------------------------------------------------------

    @Test
    fun `a release date is read from either catalog shape`() {
        assertEquals(
            LocalDate.of(2026, 12, 25),
            parseReleaseDate("2026-12-25T00:00:00.000Z")
        )
        assertEquals(LocalDate.of(2026, 12, 25), parseReleaseDate("2026-12-25"))
        // A bare year is not a date: the caller handles that shape itself.
        assertNull(parseReleaseDate("2026"))
    }

    @Test
    fun `an unknown air date counts as already aired`() {
        // Failing open is the point: a missing date must not hide a show.
        assertTrue(isAiredOrUnknown(null))
        assertTrue(isAiredOrUnknown(""))
        assertTrue(isAiredOrUnknown("garbage"))
        assertTrue(isAiredOrUnknown(LocalDate.now().minusDays(1).toString()))
        assertFalse(isAiredOrUnknown(LocalDate.now().plusDays(1).toString()))
    }

    @Test
    fun `a date window counts the days behind today`() {
        val today = LocalDate.now()
        assertTrue(isWithinDays(today.toString(), 0))
        assertTrue(isWithinDays(today.minusDays(3).toString(), 3))
        assertFalse(isWithinDays(today.minusDays(4).toString(), 3))
        // Tomorrow is not within any window of past days.
        assertFalse(isWithinDays(today.plusDays(1).toString(), 7))
        assertFalse(isWithinDays("not a date", 7))
    }

    @Test
    fun `the air-date label names the near days and dates the far ones`() {
        val today = LocalDate.now()
        assertEquals("Date TBA", formatAirDateLabel(null))
        assertEquals("Date TBA", formatAirDateLabel(""))
        assertEquals("Date TBA", formatAirDateLabel("garbage"))
        assertEquals("Today", formatAirDateLabel(today.toString()))
        assertEquals("Tomorrow", formatAirDateLabel(today.plusDays(1).toString()))
        assertEquals("In 3 days", formatAirDateLabel(today.plusDays(3).toString()))

        val far = today.plusDays(30)
        assertEquals(
            far.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
            formatAirDateLabel(far.toString())
        )
    }
}
