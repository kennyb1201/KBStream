package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of the launcher's global-search rows: what a query's
 * results become, and the link a picked row carries.
 *
 * Both are rules rather than glue, and both fail quietly when they are wrong -
 * a row with the wrong link opens the wrong title, and a list that puts every
 * series under a page of films reads as "this app has no shows".
 */
class SearchSuggestionsTest {

    private fun movie(id: Int, title: String, year: String = "1999", votes: Double = 1.0) =
        TmdbSearchTitleResult(
            id = id,
            title = title,
            releaseDate = "$year-01-01",
            voteAverage = votes,
            posterPath = "/poster$id.jpg"
        )

    private fun show(id: Int, name: String, year: String = "2005", votes: Double = 1.0) =
        TmdbSearchTitleResult(
            id = id,
            name = name,
            firstAirDate = "$year-01-01",
            voteAverage = votes,
            posterPath = "/poster$id.jpg"
        )

    @Test
    fun `a row carries a drawable title, its kind and year, and the pick's link`() {
        val rows = buildSearchSuggestions(
            movies = listOf(movie(1, "The Office", "1999")),
            shows = listOf(show(2, "The Office", "2005"))
        )

        assertEquals(2, rows.size)
        val movieRow = rows.single { it.dataId == "movie:1" }
        val showRow = rows.single { it.dataId == "tv:2" }
        assertEquals("The Office", movieRow.title)
        assertEquals("Movie \u00B7 1999", movieRow.subtitle)
        assertEquals("TV \u00B7 2005", showRow.subtitle)
        assertEquals("https://image.tmdb.org/t/p/w154/poster1.jpg", movieRow.posterUrl)
        assertEquals(
            "kbstream://title/movie/1?q=The+Office",
            movieRow.deepLink
        )
        assertTrue("a pick must be able to route to the show", showRow.deepLink.startsWith("kbstream://title/tv/2"))
        // _ID is what the system uses to key rows; it must be unique.
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
    }

    @Test
    fun `the two kinds are interleaved so a show is not buried under its namesakes`() {
        val rows = buildSearchSuggestions(
            movies = listOf(movie(1, "Heat"), movie(2, "Heat Wave"), movie(3, "Heatstroke")),
            shows = listOf(show(9, "Heat"))
        )

        // TMDB's own order inside each kind, but the show is at the top rather
        // than after every film - "the office" returns about a dozen films.
        assertEquals("tv:9", rows.first().dataId)
        assertEquals("movie:1", rows[1].dataId)
        assertEquals("movie:2", rows[2].dataId)
    }

    @Test
    fun `the list is capped and a nameless result is dropped`() {
        val many = (1..20).map { movie(it, "Title $it") }
        val rows = buildSearchSuggestions(movies = many, shows = emptyList())
        assertEquals(MAX_SEARCH_SUGGESTIONS, rows.size)

        val unnamed = buildSearchSuggestions(
            movies = listOf(movie(1, "   "), movie(2, "Real")),
            shows = emptyList()
        )
        assertEquals(listOf("movie:2"), unnamed.map { it.dataId })
    }

    @Test
    fun `a title with no poster still makes a row`() {
        val rows = buildSearchSuggestions(
            movies = listOf(movie(1, "No Art").copy(posterPath = null)),
            shows = emptyList()
        )
        assertEquals(1, rows.size)
        assertNull(rows.single().posterUrl)
    }

    @Test
    fun `the title deep link round-trips, including a title with spaces and punctuation`() {
        val link = TitleDeepLink.build("tv", 1234, "The Office: An American Workplace")

        val parsed = TitleDeepLink.parse(link)
        assertEquals("tv", parsed?.type)
        assertEquals(1234, parsed?.tmdbId)
        assertEquals("The Office: An American Workplace", parsed?.title)
    }

    @Test
    fun `a link without a title still parses into a routable title`() {
        val parsed = TitleDeepLink.parse("kbstream://title/movie/603")
        assertEquals("movie", parsed?.type)
        assertEquals(603, parsed?.tmdbId)
        assertNull("nothing to fall back to, and that is allowed", parsed?.title)
    }

    @Test
    fun `anything that is not one of our title links parses to null`() {
        listOf(
            null,
            "",
            "kbstream://title",
            "kbstream://title/",
            // A kind this app cannot route: guessing would open a wrong screen.
            "kbstream://title/person/31",
            "kbstream://title/movie",
            "kbstream://title/movie/not-a-number",
            // Right shape, someone else's scheme/host.
            "https://title/movie/1",
            "kbstream://season/movie/1",
            "content://com.kennyb1201.kbstream.search/titles"
        ).forEach { data ->
            assertNull("must not parse: $data", TitleDeepLink.parse(data))
        }
    }

    @Test
    fun `the link's kind and id survive a stray query or trailing slash`() {
        val parsed = TitleDeepLink.parse("kbstream://title/tv/1399/?q=Game+of+Thrones&x=1")
        assertEquals("tv", parsed?.type)
        assertEquals(1399, parsed?.tmdbId)
        assertEquals("Game of Thrones", parsed?.title)
    }
}
