package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id forms and the watched-episode index, pinned without a network.
 *
 * The whole point of the union is that a show watched on Trakt counts as
 * watched here no matter which id flavor the rail that drew the poster used,
 * so the twin-id cases are the actual assertions rather than a formality.
 */
class TraktWatchStateTest {

    @Test
    fun `every id flavor a rail uses canonicalises to the same key`() {
        assertEquals("tt0903747", TraktIdKeys.canonical("tt0903747"))
        assertEquals("tt0903747", TraktIdKeys.canonical("imdb:tt0903747"))
        assertEquals("tmdb:1396", TraktIdKeys.canonical("1396"))
        assertEquals("tmdb:1396", TraktIdKeys.canonical("tmdb:1396"))
        assertEquals("trakt:1", TraktIdKeys.canonical("trakt:1"))
        assertEquals("slug:breaking-bad", TraktIdKeys.canonical("breaking-bad"))
        assertEquals("slug:breaking-bad", TraktIdKeys.canonical("slug:Breaking-Bad"))
        assertNull(TraktIdKeys.canonical("  "))
    }

    @Test
    fun `an ids object answers for both its imdb and tmdb forms`() {
        val keys = TraktIdKeys.from(
            TraktIds(imdb = "tt0903747", tmdb = 1396, trakt = 1, slug = "breaking-bad")
        )

        assertTrue("tt0903747" in keys)
        assertTrue("tmdb:1396" in keys)
        assertTrue("trakt:1" in keys)
        assertTrue("slug:breaking-bad" in keys)
    }

    @Test
    fun `watched episodes match whichever id the rail carried`() {
        val snapshot = TraktWatchRules.snapshot(
            shows = listOf(
                TraktWatchedShow(
                    show = TraktMediaEntry(
                        title = "Breaking Bad",
                        ids = TraktIds(imdb = "tt0903747", tmdb = 1396, slug = "breaking-bad")
                    ),
                    seasons = listOf(
                        TraktWatchedSeason(
                            number = 1,
                            episodes = listOf(
                                TraktWatchedEpisode(number = 1),
                                TraktWatchedEpisode(number = 2)
                            )
                        )
                    )
                )
            ),
            movies = emptyList()
        )

        assertEquals(
            setOf(1 to 1, 1 to 2),
            snapshot.watchedEpisodesFor(listOf("tt0903747"))
        )
        assertEquals(
            "the same show addressed by TMDB id",
            setOf(1 to 1, 1 to 2),
            snapshot.watchedEpisodesFor(listOf("tmdb:1396"))
        )
        assertEquals(
            "a bare numeric TMDB id",
            setOf(1 to 1, 1 to 2),
            snapshot.watchedEpisodesFor(listOf("1396"))
        )
        assertTrue(
            "an unrelated show has no watched episodes",
            snapshot.watchedEpisodesFor(listOf("tt0000001")).isEmpty()
        )
    }

    @Test
    fun `a watched movie is a canonical key both badge lookups can test`() {
        val snapshot = TraktWatchRules.snapshot(
            shows = emptyList(),
            movies = listOf(
                TraktWatchedMovie(
                    movie = TraktMediaEntry(
                        title = "Batman Begins",
                        ids = TraktIds(imdb = "tt0372784", tmdb = 272)
                    )
                )
            )
        )

        assertTrue("tt0372784" in snapshot.movieKeys)
        assertTrue("tmdb:272" in snapshot.movieKeys)
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun `started shows are every id form the show row carried`() {
        val snapshot = TraktWatchRules.snapshot(
            shows = listOf(
                TraktWatchedShow(
                    show = TraktMediaEntry(
                        ids = TraktIds(imdb = "tt1520211", tmdb = 1402)
                    ),
                    seasons = listOf(
                        TraktWatchedSeason(
                            number = 3,
                            episodes = listOf(TraktWatchedEpisode(number = 4))
                        )
                    )
                )
            ),
            movies = null
        )

        assertTrue("tt1520211" in snapshot.startedShowKeys)
        assertTrue("tmdb:1402" in snapshot.startedShowKeys)
    }

    @Test
    fun `progress is a whole percent inside the scrobble range`() {
        assertEquals(0, TraktScrobbleRules.progressPercent(0.0))
        assertEquals(42, TraktScrobbleRules.progressPercent(42.4))
        assertEquals(42, TraktScrobbleRules.progressPercent(41.6))
        assertEquals(100, TraktScrobbleRules.progressPercent(100.0))
        assertEquals("clamped", 100, TraktScrobbleRules.progressPercent(140.0))
        assertEquals("clamped", 0, TraktScrobbleRules.progressPercent(-4.0))
        assertEquals("not a number", 0, TraktScrobbleRules.progressPercent(Double.NaN))
    }

    @Test
    fun `the media type decides movie versus episode`() {
        assertTrue(TraktScrobbleRules.isMovie("movie"))
        assertTrue(TraktScrobbleRules.isMovie("MOVIE"))
        assertTrue(TraktScrobbleRules.isShow("series"))
        assertTrue(TraktScrobbleRules.isShow("tv"))
        assertFalse(TraktScrobbleRules.isMovie("series"))
    }

    @Test
    fun `a KB export's list reference is read from every shape it uses`() {
        assertEquals(
            TraktListRef(user = "alice", listId = "my-list"),
            TraktListRefParser.parse("https://trakt.tv/users/alice/lists/my-list")
        )
        assertEquals(
            TraktListRef(user = "alice", listId = "my-list"),
            TraktListRefParser.parse("trakt.tv/users/alice/lists/my-list/?foo=1")
        )
        assertEquals(
            TraktListRef(user = "alice", listId = "123"),
            TraktListRefParser.parse("users/alice/lists/123")
        )
        assertEquals(
            TraktListRef(user = "alice", listId = "my-list"),
            TraktListRefParser.parse("alice/lists/my-list")
        )
        assertEquals(
            TraktListRef(user = null, listId = "98765"),
            TraktListRefParser.parse("98765")
        )
        assertNull(TraktListRefParser.parse("   "))
    }

    @Test
    fun `the first candidate that parses wins`() {
        val ref = TraktListRefParser.fromSource(
            listOf(null, " ", "https://trakt.tv/users/bob/lists/watchlist", "ignored")
        )

        assertEquals(TraktListRef(user = "bob", listId = "watchlist"), ref)
    }
}
