package com.kennyb1201.kbstream.data.mdblist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mapping of MDBList's `ratings` array onto the detail screen's chips.
 *
 * The array is `[{source, value, score}]`, where `score` is normalized to
 * 0-100 and `value` is the provider's own number (out of 10 for IMDb/TMDB/MAL,
 * stars for Letterboxd, out of 100 for RT/Metacritic). Getting that backwards
 * renders "88%" where IMDb says 8.8, or "9%" where IMDb says 8.8 the other way
 * round — and the whole row silently disappears when the sources are not
 * recognized at all, which is what the missing `/ratings` route looked like
 * from outside.
 */
class MdbListRatingsTest {

    private fun entry(source: String, value: Double? = null, score: Double? = null) =
        MdbListRatingEntry(source = source, value = value, score = score)

    @Test
    fun `a full payload renders every source in its own convention`() {
        val ratings = buildMdbListRatings(
            listOf(
                entry("imdb", score = 88.0),
                entry("tomatoes", score = 91.0),
                entry("tmdb", score = 81.0),
                entry("metacritic", score = 78.0),
                entry("trakt", score = 85.0),
                entry("letterboxd", score = 90.0),
                entry("mal", score = 85.0)
            )
        )

        assertEquals("8.8", ratings?.imdb)
        assertEquals("91%", ratings?.rottenTomatoes)
        assertEquals("8.1", ratings?.tmdb)
        assertEquals("78/100", ratings?.metacritic)
        assertEquals("85%", ratings?.trakt)
        assertEquals("90%", ratings?.letterboxd)
        assertEquals("8.5", ratings?.myAnimeList)
    }

    @Test
    fun `native values are scaled by the source's own range`() {
        // No `score` on any of these: the fallback has to know that IMDb's 8.8
        // is out of ten, Letterboxd's 4.2 out of five, and RT's 91 already a
        // percentage.
        val ratings = buildMdbListRatings(
            listOf(
                entry("imdb", value = 8.8),
                entry("tomatoes", value = 91.0),
                entry("letterboxd", value = 4.2),
                entry("metacritic", value = 78.0),
                entry("mal", value = 8.5)
            )
        )

        assertEquals("8.8", ratings?.imdb)
        assertEquals("91%", ratings?.rottenTomatoes)
        assertEquals("84%", ratings?.letterboxd)
        assertEquals("78/100", ratings?.metacritic)
        assertEquals("8.5", ratings?.myAnimeList)
    }

    @Test
    fun `a value that cannot fit the native range is read as a percentage`() {
        // A payload that normalizes already (TMDB 81 rather than 8.1) must not
        // be divided twice and shown as "81.0".
        assertEquals("8.1", buildMdbListRatings(listOf(entry("tmdb", value = 81.0)))?.tmdb)
        // Trakt is conventionally a percentage, and 85 cannot be out of ten.
        assertEquals("85%", buildMdbListRatings(listOf(entry("trakt", value = 85.0)))?.trakt)
    }

    @Test
    fun `an explicit score wins over the native value`() {
        val ratings = buildMdbListRatings(
            listOf(entry("imdb", value = 8.8, score = 90.0))
        )
        assertEquals("9.0", ratings?.imdb)
    }

    @Test
    fun `zero and unusable values are treated as no rating`() {
        assertNull(buildMdbListRatings(listOf(entry("imdb", value = 0.0, score = 0.0))))
        assertNull(buildMdbListRatings(listOf(entry("imdb"))))
        assertNull(buildMdbListRatings(emptyList()))
    }

    @Test
    fun `a source the row does not display is ignored`() {
        // MDBList also carries audience / rogerebert / score entries; they must
        // not create an empty row or displace a source that is displayed.
        assertNull(buildMdbListRatings(listOf(entry("audience", score = 88.0))))
        val ratings = buildMdbListRatings(
            listOf(entry("audience", score = 88.0), entry("imdb", score = 74.0))
        )
        assertEquals("7.4", ratings?.imdb)
    }

    @Test
    fun `source names are matched case-insensitively with aliases`() {
        val ratings = buildMdbListRatings(
            listOf(
                entry("IMDb", score = 88.0),
                entry("rotten_tomatoes", score = 91.0),
                entry("myanimelist", score = 85.0)
            )
        )
        assertEquals("8.8", ratings?.imdb)
        assertEquals("91%", ratings?.rottenTomatoes)
        assertEquals("8.5", ratings?.myAnimeList)
    }

    @Test
    fun `the first entry per source wins`() {
        // A payload that repeats a source must not flip the chip value between
        // refreshes.
        val ratings = buildMdbListRatings(
            listOf(entry("tomatoes", score = 91.0), entry("tomatoes", score = 55.0))
        )
        assertEquals("91%", ratings?.rottenTomatoes)
    }

    @Test
    fun `percentages round to whole numbers`() {
        assertEquals("91%", buildMdbListRatings(listOf(entry("tomatoes", score = 90.6)))?.rottenTomatoes)
        assertEquals("78/100", buildMdbListRatings(listOf(entry("metacritic", score = 77.5)))?.metacritic)
    }

    @Test
    fun `values above one hundred are clamped rather than shown as is`() {
        assertEquals("100%", buildMdbListRatings(listOf(entry("tomatoes", score = 130.0)))?.rottenTomatoes)
    }
}
