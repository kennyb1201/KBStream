package com.kennyb1201.kbstream.ui.components

import com.kennyb1201.kbstream.data.mdblist.MdbListRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mdbListRatingSources] turns a title's MDBList figures into the chip list the
 * detail page and the Home hero both draw.
 *
 * The order and the fallback rule are the two things both surfaces depend on:
 * the detail page's RATINGS row and the hero's chip strip must name and order
 * the sources identically, and the TMDB score must only ever appear where it
 * belongs (as the TMDB chip, and only when MDBList sent no TMDB figure) rather
 * than leaking in as some other source's value.
 */
class MdbListRatingChipsTest {

    @Test
    fun `sources keep MDBList's own order`() {
        val names = mdbListRatingSources(
            MdbListRatings(
                imdb = "8.8",
                rottenTomatoes = "94%",
                tmdb = "8.1",
                metacritic = "78/100",
                trakt = "91%",
                letterboxd = "82%",
                myAnimeList = "8.4"
            )
        ).map { it.name }

        assertEquals(
            listOf(
                "IMDb",
                "Rotten Tomatoes",
                "TMDB",
                "Metacritic",
                "Trakt",
                "Letterboxd",
                "MyAnimeList"
            ),
            names
        )
    }

    @Test
    fun `absent sources are omitted and the values pass through verbatim`() {
        val sources = mdbListRatingSources(
            MdbListRatings(imdb = "8.8", trakt = "91%")
        )

        assertEquals(listOf("IMDb", "Trakt"), sources.map { it.name })
        assertEquals(listOf("8.8", "91%"), sources.map { it.value })
    }

    @Test
    fun `tmdb fallback becomes the TMDB chip only when MDBList sent no tmdb`() {
        val withFallback = mdbListRatingSources(
            ratings = MdbListRatings(imdb = "8.8"),
            tmdbFallback = 7.5
        )
        assertEquals(listOf("IMDb", "TMDB"), withFallback.map { it.name })
        assertEquals("7.5", withFallback.first { it.name == "TMDB" }.value)

        // MDBList's own TMDB figure wins over the fallback.
        val withBoth = mdbListRatingSources(
            ratings = MdbListRatings(imdb = "8.8", tmdb = "8.1"),
            tmdbFallback = 7.5
        )
        assertEquals("8.1", withBoth.first { it.name == "TMDB" }.value)
    }

    @Test
    fun `no ratings and no fallback yields no chips`() {
        assertTrue(mdbListRatingSources(null).isEmpty())
        assertTrue(mdbListRatingSources(MdbListRatings()).isEmpty())
        // A non-positive fallback is not a score.
        assertTrue(mdbListRatingSources(null, tmdbFallback = 0.0).isEmpty())
    }

    @Test
    fun `every source carries its own mark and tint`() {
        val sources = mdbListRatingSources(
            MdbListRatings(
                imdb = "8.8",
                rottenTomatoes = "94%",
                tmdb = "8.1",
                metacritic = "78/100",
                trakt = "91%",
                letterboxd = "82%",
                myAnimeList = "8.4"
            )
        )

        assertTrue(sources.all { it.icon != 0 })
        assertEquals(sources.size, sources.map { it.icon }.distinct().size)
        assertEquals(sources.size, sources.map { it.tint }.distinct().size)
        // The label is the icon's content description, so it must be unique too.
        assertEquals(sources.size, sources.map { it.name }.distinct().size)
        // And the short label rides the hero's meta line, so it must be
        // unambiguous there as well.
        assertEquals(sources.size, sources.map { it.shortName }.distinct().size)
    }

    @Test
    fun `every source carries a compact short name`() {
        val sources = mdbListRatingSources(
            MdbListRatings(
                imdb = "8.8",
                rottenTomatoes = "94%",
                tmdb = "8.1",
                metacritic = "78/100",
                trakt = "91%",
                letterboxd = "82%",
                myAnimeList = "8.4"
            )
        )

        assertEquals(
            listOf("IMDb", "RT", "TMDB", "MC", "Trakt", "LB", "MAL"),
            sources.map { it.shortName }
        )
    }

    @Test
    fun `hero tokens take the first three sources in meta-line format`() {
        val sources = mdbListRatingSources(
            MdbListRatings(
                imdb = "8.8",
                rottenTomatoes = "94%",
                tmdb = "8.1",
                metacritic = "78/100",
                trakt = "91%"
            )
        )

        assertEquals(
            listOf("IMDb 8.8", "RT 94%", "TMDB 8.1"),
            heroRatingTokens(sources)
        )
    }

    @Test
    fun `hero tokens degenerate gracefully with fewer sources`() {
        // Only two sources present: the line carries two tokens, not three.
        val two = mdbListRatingSources(MdbListRatings(imdb = "8.8", trakt = "91%"))
        assertEquals(listOf("IMDb 8.8", "Trakt 91%"), heroRatingTokens(two))

        // Nothing at all yields nothing to append.
        assertTrue(heroRatingTokens(emptyList()).isEmpty())
        assertTrue(heroRatingTokens(mdbListRatingSources(MdbListRatings())).isEmpty())
    }
}
