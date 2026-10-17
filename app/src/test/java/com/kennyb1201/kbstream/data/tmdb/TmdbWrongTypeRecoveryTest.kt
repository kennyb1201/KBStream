package com.kennyb1201.kbstream.data.tmdb

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A catalog id that TMDB knows under the OTHER type is recovered by name.
 *
 * The break this covers (PAW Patrol, 2026-10): the catalog gave the series the
 * IMDb id of its movie (`tt3121722`, the Dino Movie), so `/find` answered with
 * that movie in `movie_results` and an empty `tv_results`. Taking only the
 * requested bucket returned null on every open - deterministically, because a
 * miss is not cached - and the catalog id was published as the "resolved" root,
 * which made every episode request ask the add-ons for the other title.
 *
 * Nothing temporal can fix that: `57532` is undiscoverable from `tt3121722`.
 * The only signal is the title's name, so the recovery is pinned here with a
 * fake API: it fires only on the wrong-type signal, requires an exact title
 * match, and does nothing at all when there is no name or the id is unknown.
 */
class TmdbWrongTypeRecoveryTest {

    private val api = FakeTmdbApi()

    // ── the field case ───────────────────────────────────────────────

    @Test
    fun `a series id that TMDB only knows as a movie is recovered by name`() = runBlocking {
        api.findResults = TmdbFindResponse(
            movieResults = listOf(TmdbBasic(id = 929, title = "The Dino Movie"))
        )
        api.searchTvResults = TmdbSearchTitleResponse(
            listOf(TmdbSearchTitleResult(id = 57532, name = "PAW Patrol"))
        )
        api.detail = TmdbDetail(id = 57532, name = "PAW Patrol")

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt3121722",
            externalSource = "imdb_id",
            normalizedType = "series",
            fallbackName = "PAW Patrol"
        )

        assertEquals("the recovered TMDB id, not the movie's", 57532, detail?.id)
        assertEquals(
            "find, then the one name search, then the recovered id's detail - nothing else",
            listOf("find:tt3121722", "searchTv:PAW Patrol", "getTv:57532"),
            api.calls
        )
    }

    @Test
    fun `case and whitespace variants still match exactly`() = runBlocking {
        api.findResults = TmdbFindResponse(movieResults = listOf(TmdbBasic(id = 929)))
        api.searchTvResults = TmdbSearchTitleResponse(
            listOf(TmdbSearchTitleResult(id = 57532, name = "PAW Patrol"))
        )
        api.detail = TmdbDetail(id = 57532, name = "PAW Patrol")

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt3121722",
            externalSource = "imdb_id",
            normalizedType = "series",
            fallbackName = "  paw   PATROL  "
        )

        assertEquals(57532, detail?.id)
        assertTrue(
            "the search is made with the name as it arrived; only the MATCH is normalized",
            api.calls[1].startsWith("searchTv:")
        )
    }

    // ── the refusals ─────────────────────────────────────────────────

    @Test
    fun `a search result that is not an exact title match is not used`() = runBlocking {
        api.findResults = TmdbFindResponse(movieResults = listOf(TmdbBasic(id = 929)))
        api.searchTvResults = TmdbSearchTitleResponse(
            listOf(TmdbSearchTitleResult(id = 999, name = "PAW Patrol: The Movie"))
        )
        api.detail = TmdbDetail(id = 999)

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt3121722",
            externalSource = "imdb_id",
            normalizedType = "series",
            fallbackName = "PAW Patrol"
        )

        assertNull("a near-miss must keep today's behavior, not open a different title", detail)
        assertTrue(
            "and the unmatched result must never be fetched",
            api.calls.none { it.startsWith("getTv:") }
        )
    }

    @Test
    fun `a blank name never searches`() = runBlocking {
        api.findResults = TmdbFindResponse(movieResults = listOf(TmdbBasic(id = 929)))
        api.searchTvResults = TmdbSearchTitleResponse(
            listOf(TmdbSearchTitleResult(id = 57532, name = "PAW Patrol"))
        )

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt3121722",
            externalSource = "imdb_id",
            normalizedType = "series",
            fallbackName = "   "
        )

        assertNull(detail)
        assertEquals(
            "no name, no search: the only call is the find",
            listOf("find:tt3121722"),
            api.calls
        )
    }

    @Test
    fun `an id TMDB does not know at all is not guessed at`() = runBlocking {
        api.findResults = TmdbFindResponse()
        api.searchTvResults = TmdbSearchTitleResponse(
            listOf(TmdbSearchTitleResult(id = 57532, name = "PAW Patrol"))
        )

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt0000000",
            externalSource = "imdb_id",
            normalizedType = "series",
            fallbackName = "PAW Patrol"
        )

        assertNull(detail)
        assertEquals(
            "both buckets empty means truly unknown: the recovery must not fire",
            listOf("find:tt0000000"),
            api.calls
        )
    }

    // ── the mirror, and the path that always worked ──────────────────

    @Test
    fun `a movie request whose id sits in tv_results searches movies`() = runBlocking {
        api.findResults = TmdbFindResponse(
            tvResults = listOf(TmdbBasic(id = 57532, name = "PAW Patrol"))
        )
        api.searchMovieResults = TmdbSearchTitleResponse(
            listOf(TmdbSearchTitleResult(id = 140300, title = "PAW Patrol: The Movie"))
        )
        api.detail = TmdbDetail(id = 140300, title = "PAW Patrol: The Movie")

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt4873036",
            externalSource = "imdb_id",
            normalizedType = "movie",
            fallbackName = "PAW Patrol: The Movie"
        )

        assertEquals(140300, detail?.id)
        assertEquals(
            listOf("find:tt4873036", "searchMovie:PAW Patrol: The Movie", "getMovie:140300"),
            api.calls
        )
    }

    @Test
    fun `a correctly typed id still costs one find and one detail, no search`() = runBlocking {
        api.findResults = TmdbFindResponse(tvResults = listOf(TmdbBasic(id = 57532)))
        api.detail = TmdbDetail(id = 57532, name = "PAW Patrol")

        val detail = findAndFetchDetail(
            api = api.service,
            apiKey = KEY,
            externalId = "tt4873036",
            externalSource = "imdb_id",
            normalizedType = "series",
            fallbackName = "PAW Patrol"
        )

        assertEquals(57532, detail?.id)
        assertEquals(
            "the normal path must make zero extra API calls",
            listOf("find:tt4873036", "getTv:57532"),
            api.calls
        )
    }

    // ── the title rule ───────────────────────────────────────────────

    @Test
    fun `title normalization is lowercase, trimmed and whitespace-collapsed`() {
        assertEquals("paw patrol", normalizeTitle("PAW  Patrol"))
        assertEquals("paw patrol", normalizeTitle("  Paw\tPatrol \n"))
        assertEquals("", normalizeTitle(null))
        assertEquals("", normalizeTitle("   "))
        assertEquals(
            "hyphens are not whitespace: these are different titles",
            "spider man",
            normalizeTitle("Spider Man")
        )
        assertEquals("spider-man", normalizeTitle("Spider-Man"))
    }

    // ── the fake ─────────────────────────────────────────────────────

    /**
     * A `TmdbApiService` whose four used endpoints answer from fields and whose
     * every other endpoint fails the test, so an unexpected request can never
     * be mistaken for a stubbed answer. Built through a Proxy because the
     * interface is some sixty endpoints wide and only these four are in play.
     */
    private class FakeTmdbApi : InvocationHandler {
        var findResults: TmdbFindResponse = TmdbFindResponse()
        var searchTvResults: TmdbSearchTitleResponse = TmdbSearchTitleResponse()
        var searchMovieResults: TmdbSearchTitleResponse = TmdbSearchTitleResponse()
        var detail: TmdbDetail? = null

        val calls = mutableListOf<String>()

        val service: TmdbApiService =
            Proxy.newProxyInstance(
                TmdbApiService::class.java.classLoader,
                arrayOf(TmdbApiService::class.java),
                this
            ) as TmdbApiService

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val values = args.orEmpty()
            return when (method.name) {
                "find" -> {
                    calls += "find:${values[0]}"
                    findResults
                }
                "searchTv" -> {
                    calls += "searchTv:${values[0]}"
                    searchTvResults
                }
                "searchMovie" -> {
                    calls += "searchMovie:${values[0]}"
                    searchMovieResults
                }
                "getTv" -> {
                    calls += "getTv:${values[0]}"
                    detail ?: error("getTv(${values[0]}) was not stubbed")
                }
                "getMovie" -> {
                    calls += "getMovie:${values[0]}"
                    detail ?: error("getMovie(${values[0]}) was not stubbed")
                }
                "toString" -> "FakeTmdbApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === values.firstOrNull()
                else -> error("unexpected TMDB call in this test: ${method.name}")
            }
        }
    }

    private companion object {
        const val KEY = "test-key"
    }
}
