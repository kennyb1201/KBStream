package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one-line title summary the player's because-you-watched strip shows.
 *
 * What matters: a missing field is omitted rather than leaving a dangling
 * separator, and a series reports its commitment (seasons/episodes) instead of
 * a single runtime that would read as the length of the whole show.
 */
class TitleMetaLineTest {

    private fun movie(
        runtime: Int? = 112,
        rating: Double? = 7.4,
        certification: String? = "PG-13",
        genres: List<String> = listOf("Action", "Crime")
    ) = TmdbDetail(
        id = 1,
        title = "Heat 2",
        runtime = runtime,
        voteAverage = rating,
        releaseDate = "2024-05-01",
        genres = genres.mapIndexed { i, name -> TmdbGenre(i + 1, name) },
        releaseDates = certification?.let { cert ->
            TmdbMovieReleaseDates(
                results = listOf(
                    TmdbMovieReleaseDatesResult(
                        iso31661 = "US",
                        releaseDates = listOf(
                            TmdbMovieReleaseDateEntry(
                                certification = cert,
                                releaseDate = "2024-05-01"
                            )
                        )
                    )
                )
            )
        }
    )

    private fun series(
        seasons: Int? = 3,
        episodes: Int? = 52,
        episodeRuntime: List<Int> = listOf(48),
        rating: Double? = 8.2,
        certification: String? = "TV-MA",
        genres: List<String> = listOf("Drama")
    ) = TmdbDetail(
        id = 2,
        name = "The Show",
        numberOfSeasons = seasons,
        numberOfEpisodes = episodes,
        episodeRunTime = episodeRuntime,
        voteAverage = rating,
        firstAirDate = "2019-03-04",
        genres = genres.mapIndexed { i, name -> TmdbGenre(i + 1, name) },
        contentRatings = certification?.let { cert ->
            TmdbTvContentRatings(results = listOf(TmdbTvContentRating("US", cert)))
        }
    )

    // ── the full line ───────────────────────────────────────────────

    @Test
    fun `a movie reports certification, year, runtime, genre and rating`() {
        assertEquals(
            "PG-13 • 2024 • 112 min • Action, Crime • \u2605 7.4",
            movie().displayMetaLine(isMovie = true)
        )
    }

    @Test
    fun `a series reports its commitment, not a single runtime`() {
        // "48 min" alone would read as the length of the whole show.
        assertEquals(
            "TV-MA • 2019 • 3 seasons • 52 eps • 48 min • Drama • \u2605 8.2",
            series().displayMetaLine(isMovie = false)
        )
    }

    @Test
    fun `a single season is not plural`() {
        val line = series(seasons = 1, episodes = 8).displayMetaLine(isMovie = false).orEmpty()
        assertEquals(true, line.contains("1 season"))
        assertEquals(false, line.contains("1 seasons"))
    }

    // ── missing pieces ──────────────────────────────────────────────

    @Test
    fun `missing fields are omitted, leaving no dangling separator`() {
        val line = movie(runtime = null, rating = null, genres = emptyList())
            .displayMetaLine(isMovie = true)
        assertEquals("PG-13 • 2024", line)
    }

    @Test
    fun `a title with no metadata at all produces no line`() {
        assertNull(TmdbDetail(id = 9).displayMetaLine(isMovie = true))
        assertNull(TmdbDetail(id = 9).displayMetaLine(isMovie = false))
    }

    @Test
    fun `a series with no counts falls back to its episode runtime`() {
        val line = series(seasons = null, episodes = null)
            .displayMetaLine(isMovie = false)
            .orEmpty()
        assertEquals(true, line.contains("48 min"))
        assertEquals(false, line.contains("null"))
    }

    @Test
    fun `zero and negative counts are treated as unknown`() {
        // TMDB ships 0 for shows it has no counts for.
        val line = series(seasons = 0, episodes = 0, episodeRuntime = emptyList())
            .displayMetaLine(isMovie = false)
            .orEmpty()
        assertEquals(false, line.contains("0 seasons"))
        assertEquals(false, line.contains("0 eps"))
    }

    // ── genres ──────────────────────────────────────────────────────

    @Test
    fun `genres are capped and blanks dropped`() {
        val line = movie(
            genres = listOf("Action", " ", "Crime", "Drama", "Thriller", "Comedy")
        ).displayMetaLine(isMovie = true).orEmpty()
        assertEquals(true, line.contains("Action, Crime, Drama"))
        assertEquals(false, line.contains("Thriller"))
    }

    @Test
    fun `genre list is null when there are none`() {
        assertNull(movie(genres = emptyList()).displayGenres())
        assertNull(movie(genres = listOf(" ", "")).displayGenres())
    }

    @Test
    fun `the credits strip asks for a single genre`() {
        // The because-you-watched strip is one line wide, so the usual three
        // read as a tag cloud there; the first genre is the descriptive one.
        val line = movie(
            genres = listOf("Action", "Crime", "Drama", "Thriller")
        ).displayMetaLine(isMovie = true, genreLimit = 1).orEmpty()
        assertEquals(true, line.contains("Action"))
        assertEquals(false, line.contains("Crime"))
        assertEquals(false, line.contains("Drama"))
    }

    @Test
    fun `the default line still names up to three genres`() {
        val line = movie(
            genres = listOf("Action", "Crime", "Drama", "Thriller")
        ).displayMetaLine(isMovie = true).orEmpty()
        assertEquals(true, line.contains("Action, Crime, Drama"))
        assertEquals(false, line.contains("Thriller"))
    }

    // ── card line ───────────────────────────────────────────────────

    @Test
    fun `a card shows year and runtime for a movie`() {
        assertEquals("2024 • 112 min", movie().displayCardMeta(isMovie = true))
    }

    @Test
    fun `a card shows year and season count for a series`() {
        // The full scope would not fit on a 108dp card.
        assertEquals("2019 • 3 seasons", series().displayCardMeta(isMovie = false))
    }

    @Test
    fun `a card falls back to runtime when a series has no season count`() {
        assertEquals("2019 • 48 min", series(seasons = null).displayCardMeta(isMovie = false))
    }

    @Test
    fun `a card with nothing known produces no line`() {
        assertNull(TmdbDetail(id = 9).displayCardMeta(isMovie = true))
        assertNull(TmdbDetail(id = 9).displayCardMeta(isMovie = false))
    }
}
