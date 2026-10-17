package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the wrong-type recovery is wired in.
 *
 * The recovery itself is unit-tested with a fake API ([TmdbWrongTypeRecoveryTest]);
 * what cannot fail at compile time and cannot be seen on screen is whether the
 * name ever reaches it, and whether every reader of a stored root goes through
 * it. A recovery nobody calls is indistinguishable from the bug it fixes, so
 * the call sites are pinned by reading the sources - the same pattern this
 * tree's other wiring contracts use.
 *
 * Also pinned: the recovered detail is cached under the ORIGINAL catalog key,
 * which is what makes the second open free rather than another find+search.
 */
class TmdbWrongTypeWiringContractTest {

    private val repository by lazy {
        squash(source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"))
    }
    private val detailViewModel by lazy {
        squash(source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"))
    }
    private val migration by lazy {
        squash(source("com/kennyb1201/kbstream/data/history/HistoryRootMigration.kt"))
    }
    private val homeScreen by lazy {
        squash(source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt"))
    }
    private val homeViewModel by lazy {
        squash(source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"))
    }

    // ── the name reaches the lookup ──────────────────────────────────

    @Test
    fun `the detail screen passes the name it was opened with`() {
        val call = section(
            detailViewModel,
            "tmdbRepository.fetchEnrichedMetaCached( id, normalizedType, full = true,",
            "// 1. Await structural"
        )
        assertTrue(
            "the recovery has nothing to search for without the title's name",
            call.contains("fallbackName = initialMeta?.name")
        )
        assertTrue(
            "and a raw id must never be searched for as if it were a title",
            call.contains("?.takeIf { it.isNotBlank() && it.trim() != id.trim() }")
        )
    }

    @Test
    fun `the history migration passes the row's own name`() {
        val call = section(
            migration,
            "repository.fetchEnrichedMetaCached(",
            ")?.id"
        )
        assertTrue(
            "the migration's lookup is the same unresolvable one that filed the row " +
                "wrong unless the name goes with it",
            call.contains("fallbackName = row.name")
        )
        assertTrue(
            "with the same raw-id guard the detail screen uses",
            call.contains(".takeIf { it.isNotBlank() && it.trim() != route.trim() }")
        )
    }

    @Test
    fun `the name is threaded through both id flavors`() {
        val fetch = section(
            repository,
            "suspend fun fetchEnrichedMeta( rawId: String, type: String, fallbackName: String? = null",
            "private suspend fun findAndFetch("
        )
        assertTrue(
            "the TVDB branch must forward it too - a wrong-type id can arrive either way",
            fetch.contains("findAndFetch(tvdbId, \"tvdb_id\", normalizedType, fallbackName)")
        )
        assertTrue(
            "and so must the IMDB branch",
            fetch.contains("findAndFetch(imdbId, \"imdb_id\", normalizedType, fallbackName)")
        )
        assertTrue(
            "the cached fetch must hand it on to the plain one",
            repository.contains("fetchEnrichedMeta(imdbId, type, fallbackName)")
        )
    }

    // ── the recovered detail is cached under the catalog's key ────────

    @Test
    fun `the recovered detail is stored under the original catalog key`() {
        val cached = section(
            repository,
            "suspend fun fetchEnrichedMetaCached(",
            "suspend fun resolveImdbId("
        )
        assertTrue(
            "the cache key must be the catalog id the caller asked for, not any recovered " +
                "id: 'series:tt3121722' means 'what the catalog calls tt3121722 as a series', " +
                "which is PAW Patrol",
            cached.contains("val key = \"\${normalizeType(type)}:\$imdbId\"")
        )
        assertTrue(
            "and the write must use that same key",
            cached.contains("detailCache[key] = now to result")
        )
        assertTrue(
            "only a real answer may be cached - a miss must stay retryable",
            cached.contains("if (result != null) {")
        )
    }

    // ── a stored id is re-verified before it is played ────────────────

    @Test
    fun `the verifier keeps the episode tail under the verified root`() {
        val verify = section(
            repository,
            "internal suspend fun verifiedEpisodeStreamId(",
            "suspend fun resolveTmdbId("
        )
        assertTrue(
            "a series id's root is everything before its season/episode tail",
            verify.contains("EpisodeMatch.seriesRootFrom(streamId)")
        )
        assertTrue(
            "and the tail is read by the same parser the rest of the app uses",
            verify.contains("EpisodeMatch.requestedFrom(streamId)")
        )
        assertTrue(
            "the verified id is rebuilt, not returned as-is",
            verify.contains("return if (episode == null) verified else \"\$verified:\${episode.first}:\${episode.second}\"")
        )
        assertTrue(
            "anything that cannot be resolved keeps the stored id - today's behavior",
            verify.contains("?: return streamId")
        )
    }

    @Test
    fun `the continue watching resume asks for the verified id`() {
        assertEquals(
            "both play paths (Play Manually and the autoplay target) must go through the " +
                "verification, or one of them still names the wrong show",
            2,
            Regex("openWithVerifiedRoot \\{").findAll(homeScreen).count()
        )
        assertTrue(
            "the lookup is bounded like every other root wait",
            homeScreen.contains("withTimeoutOrNull(CW_ROOT_VERIFY_TIMEOUT_MS)")
        )
        assertTrue(
            "and a timeout keeps the stored id rather than cancelling the press",
            homeScreen.contains("} ?: targetStreamId")
        )
        assertTrue(
            "the show name is what the recovery searches for",
            homeScreen.contains("val showName = item.showTitle ?: item.title")
        )
        assertTrue(
            "the view model owns the repository call",
            homeScreen.contains("viewModel.verifiedResumeStreamId(")
        )
        assertTrue(
            "which delegates to the repository's verifier",
            homeViewModel.contains(
                "): String = tmdbRepository.verifiedEpisodeStreamId(streamId, showName, type)"
            )
        )
    }

    /** The text between two anchors, with both anchors asserted to exist. */
    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing after '$from': $to", end > start)
        return text.substring(start, end)
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")
}
