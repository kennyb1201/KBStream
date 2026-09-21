package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A network screen's rail set.
 *
 * The movie rails are the part that matters: TMDB has no movies-by-network
 * discover, so a network's movies only exist when the browse entry carries
 * the brand's company id. These tests pin that a network WITHOUT one stays
 * series-only (no empty "MOVIES ·" sections), and that adding the company id
 * only ever adds movie rails — the series rails keep their order, because the
 * ViewModel's paging is keyed on the title.
 */
class NetworkRailTitlesTest {

    @Test
    fun `network without a company id is series-only`() {
        val titles = TmdbRailPages.networkRailTitles(companyId = null)

        assertEquals(
            listOf("SERIES · RECENT", "SERIES · POPULAR", "SERIES · TOP RATED"),
            titles
        )
        assertFalse(titles.any { it.startsWith("MOVIES") })
    }

    @Test
    fun `network with a company id also gets the movie rails`() {
        val titles = TmdbRailPages.networkRailTitles(companyId = 3507)

        assertEquals(
            listOf(
                "SERIES · RECENT",
                "SERIES · POPULAR",
                "SERIES · TOP RATED",
                "MOVIES · RECENT",
                "MOVIES · POPULAR",
                "MOVIES · TOP RATED"
            ),
            titles
        )
    }

    @Test
    fun `series rails stay first so the page still leads with television`() {
        val titles = TmdbRailPages.networkRailTitles(companyId = 3507)

        assertEquals(0, titles.indexOf("SERIES · RECENT"))
        assertTrue(titles.indexOf("MOVIES · RECENT") > titles.indexOf("SERIES · TOP RATED"))
    }

    @Test
    fun `every movie rail is parseable by the rail page loader`() {
        // TmdbRailPages.networkPage routes a "MOVIES …" title to company
        // discover, which parses the media type from the title prefix and the
        // sort from the suffix — a title it cannot parse silently returns an
        // empty page, i.e. a rail that renders nothing.
        val movies = TmdbRailPages.networkRailTitles(companyId = 3507)
            .filter { it.startsWith("MOVIES") }

        assertEquals(
            listOf("MOVIES · RECENT", "MOVIES · POPULAR", "MOVIES · TOP RATED"),
            movies
        )
    }
}
