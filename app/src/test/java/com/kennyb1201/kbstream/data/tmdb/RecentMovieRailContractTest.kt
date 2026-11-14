package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Search browse chips' RECENT rails: movies get a floor and no shorts, TV is
 * untouched (spec 2026-11-14, F1/F2).
 *
 * Kenny, 2026-11-14: the newest-first rails were mostly shorts and obscure
 * zero-vote releases, hardly any blockbusters. His scope was deliberate and
 * narrow - "cant we only do it with movies" / "shows are fine" - so this suite
 * pins BOTH halves of the split: what a movie RECENT rail now asks TMDB for, and
 * that no series rail asks for any of it.
 *
 * The floors are pure ([TmdbRailFloors]) and are asserted directly; the wiring is
 * pinned by reading the sources, because the repositories talk to Retrofit and an
 * Android context and cannot be constructed on a plain JVM.
 */
class RecentMovieRailContractTest {

    private val repository by lazy { source(REPOSITORY) }
    private val rails by lazy { source(RAILS) }
    private val api by lazy { source(API) }

    // ── F1: the split floors ──────────────────────────────────────────────

    @Test
    fun `movie RECENT gets five votes and TV RECENT keeps none`() {
        assertEquals("the old floor, restored for movies", 5, TmdbRailFloors.RECENT_MOVIE_VOTE_FLOOR)
        assertEquals("a premiere has no votes yet - that is the point", 0, TmdbRailFloors.RECENT_TV_VOTE_FLOOR)
        assertEquals(5, TmdbRailFloors.recentVoteFloor(isTv = false))
        assertEquals(0, TmdbRailFloors.recentVoteFloor(isTv = true))
    }

    @Test
    fun `POPULAR and TOP RATED keep ten on both media types`() {
        assertEquals(10, TmdbRailFloors.POPULAR_VOTE_FLOOR)
        assertEquals(10, TmdbRailFloors.TOP_RATED_VOTE_FLOOR)
        assertTrue(
            "the repository still reads the untouched floors from the policy",
            repository.contains("minVoteCount = TmdbRailFloors.POPULAR_VOTE_FLOOR") &&
                repository.contains(
                    "minTopRatedVoteCount = TmdbRailFloors.TOP_RATED_VOTE_FLOOR"
                )
        )
    }

    // ── F2: the runtime cut, and the hard movie-only rule ─────────────────

    @Test
    fun `a movie RECENT rail cuts at forty minutes and a TV rail never does`() {
        assertEquals(40, TmdbRailFloors.recentRuntimeGte(isTv = false))
        assertNull(
            "a series' runtime is per episode: this must never reach a TV rail",
            TmdbRailFloors.recentRuntimeGte(isTv = true)
        )
    }

    @Test
    fun `the movie discover endpoints carry the runtime parameter and the TV twins do not`() {
        listOf("discoverMovieByCompany", "discoverMovieByGenre", "discoverMovieByKeyword")
            .forEach { name ->
                assertTrue(
                    "$name must be able to send the feature cut",
                    methodSignature(name).contains("with_runtime.gte")
                )
            }
        listOf(
            "discoverTvByCompany",
            "discoverTvByGenre",
            "discoverTvByKeyword",
            "discoverByNetwork"
        ).forEach { name ->
            assertFalse(
                "$name must have no runtime parameter at all - that is what makes " +
                    "the movie-only rule structural rather than a comment",
                methodSignature(name).contains("withRuntime")
            )
        }
    }

    @Test
    fun `every movie RECENT rail asks for the floor and the cut, and no series rail does`() {
        assertEquals(
            "the floor is sent by all three movie RECENT loaders " +
                "(genre, keyword, company - the last also serves network and " +
                "ORIGINALS movie rails)",
            3,
            countOf(rails, "minRecentMovieVoteCount")
        )
        assertEquals(
            "and so is the runtime cut",
            3,
            countOf(rails, "minRecentMovieRuntimeMinutes")
        )

        val seriesArms = seriesRecentArms(rails)
        assertEquals("there are four series RECENT arms", 4, seriesArms.size)
        seriesArms.forEach { arm ->
            assertTrue(
                "a series RECENT arm keeps the TV floor",
                arm.contains("repo.minRecentVoteCount")
            )
            assertFalse(
                "and carries no runtime cut, ever: $arm",
                arm.contains("Runtime") || arm.contains("runtime")
            )
            assertTrue(
                "and is still newest-first by air date",
                arm.contains("first_air_date.desc")
            )
        }
    }

    // ── the sort and the gates ───────────────────────────────────────────

    @Test
    fun `movie RECENT is still newest-first, not re-sorted by popularity`() {
        assertEquals(
            "one sort per movie RECENT loader, unchanged",
            3,
            countOf(rails, "\"primary_release_date.desc\"")
        )
        assertTrue(
            "the cross-genre rails pick the date sort by media type",
            repository.contains(
                "\"RECENT\" -> if (isTv) \"first_air_date.desc\" else " +
                    "\"primary_release_date.desc\""
            )
        )
        assertTrue(
            "and so do the provider rails",
            repository.contains(
                "\"recent\" -> if (isTv) \"first_air_date.desc\" else " +
                    "\"primary_release_date.desc\""
            )
        )
    }

    @Test
    fun `the mode-driven loaders pick the floor and the runtime by media type`() {
        assertTrue(
            "cross-genre RECENT: movie floor, TV floor",
            repository.contains("\"RECENT\" -> recentVoteFloor(isTv)")
        )
        assertTrue(
            "provider RECENT: the same gate",
            repository.contains("\"recent\" -> recentVoteFloor(isTv)")
        )
        assertTrue(
            "the runtime cut is gated on the mode AND the media type",
            repository.contains(
                "withRuntimeGte = if (mode == \"RECENT\") recentRuntimeGte(isTv) else null"
            ) &&
                repository.contains(
                    "withRuntimeGte = if (mode == \"recent\") recentRuntimeGte(isTv) else null"
                )
        )
        assertTrue(
            "the decade loader follows its sort, and its runtime gate is the same one",
            repository.contains("withRuntimeGte = if (isRecent) recentRuntimeGte(isTv) else null")
        )
        assertFalse(
            "the old single floor must not still be wired to either RECENT mode",
            repository.contains("\"RECENT\" -> minRecentVoteCount") ||
                repository.contains("\"recent\" -> minRecentVoteCount")
        )
    }

    @Test
    fun `the TV floor is still zero in the tree, and the movie floor is five`() {
        assertTrue(
            repository.contains("minRecentVoteCount = TmdbRailFloors.RECENT_TV_VOTE_FLOOR") &&
                repository.contains(
                    "minRecentMovieVoteCount = TmdbRailFloors.RECENT_MOVIE_VOTE_FLOOR"
                ) &&
                repository.contains(
                    "minRecentMovieRuntimeMinutes =\n        TmdbRailFloors.RECENT_MOVIE_MIN_RUNTIME_MINUTES"
                )
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** One `MOVIES · RECENT` / `SERIES · RECENT` arm of a rail-page loader. */
    private fun seriesRecentArms(text: String): List<String> {
        val arms = ArrayList<String>()
        var from = text.indexOf("\"SERIES · RECENT\" -> runCatchingCancellable {")
        while (from >= 0) {
            val end = text.indexOf(".results", from)
            assertTrue("an arm must end with .results", end > from)
            arms += text.substring(from, end)
            from = text.indexOf("\"SERIES · RECENT\" -> runCatchingCancellable {", end)
        }
        return arms
    }

    private fun methodSignature(name: String): String {
        val start = api.indexOf("suspend fun $name(")
        assertTrue("missing method: $name", start >= 0)
        val end = api.indexOf("): TmdbDiscoverResponse", start)
        assertTrue("unterminated signature: $name", end > start)
        return api.substring(start, end)
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }

    private companion object {
        const val TMDB = "java/com/kennyb1201/kbstream/data/tmdb/"
        const val REPOSITORY = "${TMDB}TmdbRepository.kt"
        const val RAILS = "${TMDB}TmdbRailPages.kt"
        const val API = "${TMDB}TmdbApiService.kt"
    }
}
