package com.kennyb1201.kbstream.data.watched

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manual mark/unmark must refresh Continue Watching promptly.
 *
 * [ContinueWatchingRefreshBus] is the "Home, re-merge the Continue Watching
 * rail" signal. It used to be emitted only by the two player activities, so a
 * mark made by hand - the detail screen, a poster menu, any rail - wrote its
 * rows and pushed to Simkl but never told Home, and the card sat on the rail
 * for minutes until the next incidental refresh. Home does not ON_RESUME for
 * in-app navigation, so nothing rebuilt the rail in between.
 *
 * This is wiring across several view models and a repository, and the failure
 * is a missing line that compiles cleanly, so the emission sites are pinned at
 * the source.
 */
class ManualWatchRefreshContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun read(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The slice of [src] from [from] up to the next [until]. */
    private fun body(src: String, from: String, until: String): String {
        val start = src.indexOf(from)
        assertTrue("source missing: $from", start >= 0)
        val end = src.indexOf(until, start + from.length)
        assertTrue("body must be bounded by $until", end > start)
        return src.substring(start, end)
    }

    private val repository: String by lazy { read(REPO) }
    private val detail: String by lazy { read(DETAIL) }

    @Test
    fun `every manual mark path signals Home`() {
        val markWatched =
            body(repository, "suspend fun markWatchedLocal(", "suspend fun markEpisodeWatchedLocal(")
        val markEpisode = body(
            repository,
            "suspend fun markEpisodeWatchedLocal(",
            "suspend fun markPartiallyWatchedLocal("
        )
        val unmark =
            body(repository, "suspend fun markUnwatchedLocal(", "suspend fun clearWatchedOverride(")

        assertTrue(
            "a whole-title mark must re-merge Continue Watching",
            markWatched.contains("ContinueWatchingRefreshBus.requestRefresh()")
        )
        assertTrue(
            "a single-episode mark must re-merge Continue Watching",
            markEpisode.contains("ContinueWatchingRefreshBus.requestRefresh()")
        )
        assertTrue(
            "an unmark must bring the card back promptly too",
            unmark.contains("ContinueWatchingRefreshBus.requestRefresh()")
        )
    }

    @Test
    fun `the detail marks that bypass the repository signal Home themselves`() {
        val seasonWatched =
            body(detail, "fun markSeasonWatched(", "fun markSeasonUnwatched(")
        val specificUnwatched = body(
            detail,
            "private fun markSpecificEpisodesUnwatched(",
            "suspend fun resolveImdbId("
        )

        assertTrue(
            "markSeasonWatched writes its own rows and pushes its own tracker " +
                "records - it never runs through the repository - so it must " +
                "signal Home itself",
            seasonWatched.contains("ContinueWatchingRefreshBus.requestRefresh()")
        )
        assertTrue(
            "markSpecificEpisodesUnwatched has the same bypass and must signal too",
            specificUnwatched.contains("ContinueWatchingRefreshBus.requestRefresh()")
        )
        assertTrue(
            "DetailViewModel must import the bus",
            detail.contains(
                "import com.kennyb1201.kbstream.data.watched.ContinueWatchingRefreshBus"
            )
        )
    }

    @Test
    fun `the repository reaches the bus without an import`() {
        // Same package as the bus, so a missing import is not the failure mode;
        // what matters is that the call is spelled fully and unqualified.
        assertTrue(
            "the emission must be the object's own call",
            repository.contains("ContinueWatchingRefreshBus.requestRefresh()")
        )
    }

    @Test
    fun `every caller of the mark paths is a UI surface, not a sync pull`() {
        // The bus is a UI refresh signal. If a background sync path ever began
        // calling these, its bulk writes would spam Home with refetches.
        val calls =
            listOf(
                ".markWatchedLocal(",
                ".markEpisodeWatchedLocal(",
                ".markUnwatchedLocal("
            )
        val offenders = ArrayList<String>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                if (calls.any { text.contains(it) }) {
                    val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                    if (!relative.startsWith("com/kennyb1201/kbstream/ui/")) {
                        offenders += relative
                    }
                }
            }
        assertTrue(
            "only view models may call the manual mark paths, found: $offenders",
            offenders.isEmpty()
        )
    }

    private companion object {
        private const val REPO = "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
        private const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
    }
}
