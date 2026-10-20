package com.kennyb1201.kbstream.data.trakt

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire names, parsed and serialized without a network.
 *
 * Retrofit's Moshi converter leaves an unknown/misspelled field as null, which
 * is silent at runtime: a watched snapshot whose `show`/`ids` never parse looks
 * exactly like an account with nothing watched. These samples are the shapes
 * Trakt's docs publish, so a rename shows up here instead of on a device.
 */
class TraktWireFormatTest {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private fun <T> listAdapter(type: Class<T>) = moshi.adapter<List<T>>(
        Types.newParameterizedType(List::class.java, type)
    )

    @Test
    fun `a watched-shows page parses its nested seasons and episodes`() {
        val json = """
            [
              {
                "plays": 12,
                "last_watched_at": "2026-10-01T20:00:00.000Z",
                "show": {
                  "title": "Breaking Bad",
                  "year": 2008,
                  "ids": {
                    "trakt": 1,
                    "slug": "breaking-bad",
                    "imdb": "tt0903747",
                    "tmdb": 1396,
                    "tvdb": 81189
                  }
                },
                "seasons": [
                  {
                    "number": 1,
                    "episodes": [
                      {"number": 1, "plays": 1, "last_watched_at": "2026-10-01T20:00:00.000Z"},
                      {"number": 2, "plays": 1}
                    ]
                  }
                ]
              }
            ]
        """.trimIndent()

        val rows = listAdapter(TraktWatchedShow::class.java).fromJson(json).orEmpty()

        assertEquals(1, rows.size)
        assertEquals("Breaking Bad", rows[0].show?.title)
        assertEquals("tt0903747", rows[0].show?.ids?.imdb)
        assertEquals(1396, rows[0].show?.ids?.tmdb)
        assertEquals(1, rows[0].seasons[0].number)
        assertEquals(2, rows[0].seasons[0].episodes?.size)

        val snapshot = TraktWatchRules.snapshot(shows = rows, movies = null)
        assertEquals(setOf(1 to 1, 1 to 2), snapshot.watchedEpisodesFor(listOf("tt0903747")))
    }

    @Test
    fun `a watched-movies page parses its movie ids`() {
        val json = """
            [
              {
                "plays": 2,
                "movie": {
                  "title": "Batman Begins",
                  "year": 2005,
                  "ids": {"trakt": 1, "slug": "batman-begins-2005", "imdb": "tt0372784", "tmdb": 272}
                }
              }
            ]
        """.trimIndent()

        val rows = listAdapter(TraktWatchedMovie::class.java).fromJson(json).orEmpty()
        val snapshot = TraktWatchRules.snapshot(shows = null, movies = rows)

        assertTrue("tt0372784" in snapshot.movieKeys)
        assertTrue("tmdb:272" in snapshot.movieKeys)
    }

    @Test
    fun `a movie scrobble serializes the ids and a whole percent`() {
        val adapter = moshi.adapter(TraktScrobbleRequest::class.java)
        val json = adapter.toJson(
            TraktScrobbleRequest(
                movie = TraktMediaEntry(ids = TraktIds(imdb = "tt0372784", tmdb = 272)),
                progress = 42
            )
        )

        assertTrue(json.contains("\"movie\""))
        assertTrue(json.contains("\"imdb\":\"tt0372784\""))
        assertTrue(json.contains("\"tmdb\":272"))
        assertTrue(json.contains("\"progress\":42"))
    }

    @Test
    fun `an episode scrobble names the show, the season and the number`() {
        val adapter = moshi.adapter(TraktScrobbleRequest::class.java)
        val json = adapter.toJson(
            TraktScrobbleRequest(
                show = TraktMediaEntry(ids = TraktIds(imdb = "tt0903747")),
                episode = TraktEpisodeNumber(season = 2, number = 7),
                progress = 10
            )
        )

        assertTrue(json.contains("\"show\""))
        assertTrue(json.contains("\"episode\":{\"season\":2,\"number\":7"))
    }

    @Test
    fun `a history write nests the episode under its season`() {
        val adapter = moshi.adapter(TraktSyncHistoryRequest::class.java)
        val json = adapter.toJson(
            TraktSyncHistoryRequest(
                shows = listOf(
                    TraktShowHistoryEntry(
                        ids = TraktIds(imdb = "tt0903747", tmdb = 1396),
                        seasons = listOf(
                            TraktSeasonHistoryEntry(
                                number = 2,
                                episodes = listOf(TraktEpisodeNumber(2, 7))
                            )
                        )
                    )
                )
            )
        )

        assertTrue(json.contains("\"shows\""))
        assertTrue(json.contains("\"seasons\":[{\"number\":2"))
        assertTrue(json.contains("\"episodes\":[{\"season\":2,\"number\":7"))
    }

    @Test
    fun `a list read parses artwork and both media columns`() {
        val json = """
            [
              {
                "rank": 1,
                "type": "movie",
                "movie": {
                  "title": "Batman Begins",
                  "year": 2005,
                  "ids": {"trakt": 1, "imdb": "tt0372784", "tmdb": 272},
                  "images": {
                    "poster": ["https://walter.trakt.tv/images/movies/000/000/001/posters/thumb/1.jpg"],
                    "fanart": ["https://walter.trakt.tv/images/movies/000/000/001/fanarts/thumb/1.jpg"]
                  }
                }
              }
            ]
        """.trimIndent()

        val rows = listAdapter(TraktListItem::class.java).fromJson(json).orEmpty()

        assertEquals(1, rows.size)
        assertEquals("movie", rows[0].type)
        assertEquals(272, rows[0].movie?.ids?.tmdb)
        assertTrue(rows[0].movie?.images?.poster?.first().orEmpty().startsWith("https://"))
    }

    @Test
    fun `a comments page parses author, spoiler and review flags`() {
        val json = """
            [
              {
                "id": 417,
                "comment": "A slow burn that pays off.",
                "spoiler": true,
                "review": true,
                "likes": 12,
                "replies": 3,
                "created_at": "2026-09-20T18:04:11.000Z",
                "user": {"username": "alice", "name": "Alice"}
              }
            ]
        """.trimIndent()

        val comments = listAdapter(TraktComment::class.java).fromJson(json).orEmpty()

        assertEquals(1, comments.size)
        assertEquals("alice", comments[0].user?.username)
        assertEquals("Alice", comments[0].user?.name)
        assertTrue(comments[0].spoiler)
        assertTrue(comments[0].review)
        assertEquals(12, comments[0].likes)
        assertEquals("2026-09-20T18:04:11.000Z", comments[0].createdAt)
    }
}
