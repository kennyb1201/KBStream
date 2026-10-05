package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching's completed-list leg, pinned at the source.
 *
 * The rule that decides a returning show is a card
 * ([ShowCompletionRules.isContinueWatchingCandidate]) is unit-tested on its own;
 * what a pure test cannot see is whether the completed list is ever READ. The
 * reported bug was exactly that gap - Chad Powers, finished long ago, S2 aired,
 * Simkl never moved it back to "watching", so the rule never got a chance to
 * run. These assertions lock the wiring: a slim paged completed endpoint, a
 * paged walk in `getContinueWatching`, and both legs going through the same
 * per-item mapping (so the cards score and badge identically).
 */
class SimklCompletedContinueWatchingContractTest {

    private fun readSource(path: String): String {
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

    @Test
    fun `the completed list has its own slim endpoint`() {
        val api = readSource(API)
        assertTrue(
            "the slim completed endpoint must exist",
            api.contains("suspend fun getCompletedShows(")
        )
        // Slim, not the include_all_episodes variant: the tallies are all the
        // candidate rule needs.
        val slim =
            api.substringAfter("suspend fun getCompletedShows(")
                .substringBefore("suspend fun getCompletedShowsDetailed(")
        assertTrue(
            "the slim completed endpoint must not request all episodes",
            !slim.contains("include_all_episodes")
        )
        assertTrue(
            "the slim completed endpoint must page",
            slim.contains("@Query(\"page\")")
        )
    }

    @Test
    fun `the completed feed has a repository read mirroring the watching one`() {
        val reads = readSource(READS)
        assertTrue(
            "reads must expose getCompletedShowsImpl",
            reads.contains("suspend fun SimklRepository.getCompletedShowsImpl(")
        )
        assertTrue(
            "the impl must call the slim endpoint",
            reads.contains("api.getCompletedShows(")
        )
    }

    @Test
    fun `continue watching walks the completed list and maps it like the watching leg`() {
        val repo = readSource(REPO)
        assertTrue(
            "getContinueWatching must page the completed list",
            repo.contains("val completedShows =")
        )
        assertTrue(
            "the walk must be bounded",
            repo.contains("private const val MAX_COMPLETED_SHOW_PAGES")
        )
        assertTrue(
            "both tracker legs must share the per-item mapping",
            Regex("continueWatchingFromTrackerItem\\(").findAll(repo).count() >= 3
        )
        assertTrue(
            "the completed leg must not use the last_watched fallback",
            repo.contains("allowLastWatchedFallback = false")
        )
        assertTrue(
            "the completed cards must join the result",
            repo.contains("completedMapped")
        )
    }

    private companion object {
        const val API = "com/kennyb1201/kbstream/data/simkl/SimklApiService.kt"
        const val READS = "com/kennyb1201/kbstream/data/simkl/SimklReads.kt"
        const val REPO = "com/kennyb1201/kbstream/data/simkl/SimklRepository.kt"
    }
}
