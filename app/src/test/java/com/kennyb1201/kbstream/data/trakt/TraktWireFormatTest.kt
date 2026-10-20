package com.kennyb1201.kbstream.data.trakt

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * The same builder WITHOUT the reflection fallback.
     *
     * Moshi's built-in factory resolves a `@JsonClass(generateAdapter = true)`
     * class by the generated `<Name>JsonAdapter`; with no Kotlin factory behind
     * it, a model that lost its annotation cannot parse at all. That is what
     * makes the two tests below evidence of codegen rather than of Moshi's
     * ability to reflect.
     */
    private val codegenOnly: Moshi = Moshi.Builder().build()

    private fun <T> listAdapter(type: Class<T>) = moshi.adapter<List<T>>(
        Types.newParameterizedType(List::class.java, type)
    )

    /**
     * The failure this pins was invisible here and fatal in the release APK: the
     * token response's optional `error` fields rely on a defaulted constructor
     * call, and in an R8 build the reflective adapter threw "This callable does
     * not support a default call" instead. Trakt had already consumed the code
     * by then, so the next poll read 404 and the screen said "expired".
     *
     * A generated adapter does not call the constructor reflectively at all, so
     * the assertion is that Moshi resolves the CODEGEN class by name rather than
     * falling through to [KotlinJsonAdapterFactory].
     */
    @Test
    fun `every wire model resolves to its generated adapter, not the reflective one`() {
        listOf(
            TraktTokenResponse::class.java,
            TraktDeviceCodeResponse::class.java,
            TraktWatchedShow::class.java,
            TraktListItem::class.java,
            TraktComment::class.java,
            // Request bodies too: reflective serialization carries the same R8 risk.
            TraktScrobbleRequest::class.java,
            TraktRefreshRequest::class.java,
            TraktDeviceTokenRequest::class.java,
            TraktRevokeRequest::class.java,
            TraktSyncHistoryRequest::class.java
        ).forEach { type ->
            // The generated class is the one Moshi looks up by name.
            val generated = Class.forName("${type.name}JsonAdapter")
            assertTrue(
                "${type.simpleName} must ship a codegen adapter, or a release " +
                    "build reflects into a defaulted constructor",
                com.squareup.moshi.JsonAdapter::class.java.isAssignableFrom(generated)
            )
            // And Moshi must actually find it, with nothing to reflect with.
            codegenOnly.adapter(type)
        }
    }

    /**
     * The exact 200 body the live flow returns after approval: the token fields
     * are all there and `error`/`error_description` are absent. That combination
     * is what the reflective adapter could not construct.
     */
    @Test
    fun `the live 200 token body parses with its error fields absent`() {
        val json = """
            {
              "access_token": "live-access-token",
              "token_type": "bearer",
              "expires_in": 7776000,
              "refresh_token": "live-refresh-token",
              "scope": "public",
              "created_at": 1761000000
            }
        """.trimIndent()

        // Codegen ONLY: this is the path the minified release build takes.
        val body = codegenOnly.adapter(TraktTokenResponse::class.java).fromJson(json)

        assertNotNull("a 200 with a token must construct, not throw", body)
        assertEquals("live-access-token", body?.accessToken)
        assertEquals("live-refresh-token", body?.refreshToken)
        assertEquals("bearer", body?.tokenType)
        assertEquals("public", body?.scope)
        assertEquals(7_776_000L, body?.expiresInSeconds)
        assertEquals(1_761_000_000L, body?.createdAtEpochSeconds)
        assertNull("the grant carries no OAuth error", body?.error)
        assertNull(body?.errorDescription)
    }

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
