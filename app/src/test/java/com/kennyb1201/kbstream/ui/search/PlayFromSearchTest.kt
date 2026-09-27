package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Play <title> on KBStream" is answered by picking one result and opening it,
 * so a wrong pick is the whole feature failing — the viewer asked for a title
 * and got someone else's. These cases pin the two rules that decide it: art
 * beats a stray record, and TMDB's own relevance order beats popularity.
 *
 * The fixtures are modelled on real searches rather than invented: "the office"
 * is the case that motivated votes being a tie-breaker rather than the ranking
 * (two dozen US and UK namesakes, one of them far more voted than the one the
 * viewer meant), and the posterless entries are the unaired-pilot / duplicate
 * records TMDB carries for famous titles.
 */
class PlayFromSearchTest {

    private fun result(
        id: Int,
        title: String? = null,
        name: String? = null,
        poster: String? = "/poster.jpg",
        votes: Double? = 7.0
    ) = TmdbSearchTitleResult(
        id = id,
        title = title,
        name = name,
        posterPath = poster,
        voteAverage = votes
    )

    @Test
    fun `the top result wins over a more popular one further down`() {
        // Both lists rank by relevance, so the first entry is TMDB's answer and
        // the third is a better-known namesake.
        val movies = listOf(
            result(1, title = "The Office", votes = 6.4),
            result(2, title = "The Office US", votes = 8.6),
            result(3, title = "Office Space", votes = 7.8)
        )

        assertEquals(1, pickPlayFromSearchMatch(movies, emptyList())?.tmdbId)
    }

    @Test
    fun `a result with artwork beats one without`() {
        val movies = listOf(
            result(1, title = "Severance", poster = null, votes = 9.0),
            result(2, title = "Severance", poster = "/severance.jpg", votes = 8.0)
        )

        assertEquals(2, pickPlayFromSearchMatch(movies, emptyList())?.tmdbId)
    }

    @Test
    fun `the artwork filter is dropped when nothing has a poster`() {
        // An obscure title with no art anywhere is still what the viewer asked
        // for; refusing to open it would be worse than opening it.
        val movies = listOf(
            result(1, title = "Obscure Feature", poster = null, votes = 2.0),
            result(2, title = "Obscure Feature II", poster = null, votes = 1.0)
        )

        assertEquals(1, pickPlayFromSearchMatch(movies, emptyList())?.tmdbId)
    }

    @Test
    fun `the two lists are ranked against each other on their own positions`() {
        // A show at rank 0 and a film at rank 1: the show is the better answer,
        // and the type must come back with it.
        val movies = listOf(
            result(10, title = "Fargo Two"),
            result(11, title = "Fargo", votes = 9.5)
        )
        val shows = listOf(result(20, name = "Fargo", votes = 8.5))

        val match = pickPlayFromSearchMatch(movies, shows)

        assertEquals(20, match?.tmdbId)
        assertEquals("tv", match?.type)
        assertEquals("Fargo", match?.title)
    }

    @Test
    fun `a movie wins a tie against a show`() {
        // Both at rank 0 with the same votes: a bare title is more often a film
        // than a series, and the tie has to resolve the same way every time.
        val movies = listOf(result(10, title = "Dune", votes = 8.0))
        val shows = listOf(result(20, name = "Dune", votes = 8.0))

        val match = pickPlayFromSearchMatch(movies, shows)

        assertEquals("movie", match?.type)
        assertEquals(10, match?.tmdbId)
    }

    @Test
    fun `votes break a rank tie between the lists`() {
        val movies = listOf(result(10, title = "Dune", votes = 6.0))
        val shows = listOf(result(20, name = "Dune", votes = 9.0))

        assertEquals(20, pickPlayFromSearchMatch(movies, shows)?.tmdbId)
    }

    @Test
    fun `a series carries its own name and its own type back`() {
        val shows = listOf(result(1396, name = "Breaking Bad", title = null))

        val match = pickPlayFromSearchMatch(emptyList(), shows)

        assertEquals("tv", match?.type)
        assertEquals("Breaking Bad", match?.title)
    }

    @Test
    fun `nameless records are ignored whatever else they carry`() {
        val movies = listOf(
            result(1, title = "   ", poster = "/poster.jpg", votes = 9.9),
            result(2, name = "")
        )
        val shows = listOf(result(3, title = null, name = null))

        assertNull(pickPlayFromSearchMatch(movies, shows))
    }

    @Test
    fun `nothing at all resolves to nothing so the caller can fall back to search`() {
        assertNull(pickPlayFromSearchMatch(emptyList(), emptyList()))
    }

    @Test
    fun `a missing vote average counts as the lowest, not as a crash`() {
        // voteAverage is nullable on the wire. At equal rank it must lose to a
        // real score rather than throwing or tying.
        val movies = listOf(result(1, title = "Dune", votes = null))
        val shows = listOf(result(2, name = "Dune", votes = 8.0))

        val match = pickPlayFromSearchMatch(movies, shows)

        assertEquals("tv", match?.type)
        assertEquals(2, match?.tmdbId)
    }
}
