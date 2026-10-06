package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A superseded add-on search must not republish (LS-P2-2).
 *
 * The add-on wave answers add-on by add-on and writes each intermediate
 * snapshot straight to `_addonResultGroups`. Cancelling the job is not enough on
 * its own: Kotlin cancellation is cooperative, so a child that has already been
 * resumed runs its remaining non-suspending statements before it observes the
 * cancel - and the eager snapshot write sits right after `slots.set`, with no
 * suspension point in between. The result was the previous query's rails
 * landing on screen after the new search had already cleared them.
 *
 * The fix is a monotonically-increasing generation: every call that supersedes
 * the running search bumps it, and each publish checks it still owns the rails.
 * These assertions pin the token, the bump, and every guarded write.
 */
class AddonSearchStalePublishContractTest {

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The text from [startMarker] up to the first [endMarker] after it. */
    private fun block(startMarker: String, endMarker: String): String {
        val source = source(MODELS)
        val start = source.indexOf(startMarker)
        assertTrue("marker missing: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("end marker missing after $startMarker", end >= 0)
        return source.substring(start, end)
    }

    @Test
    fun `a superseding call bumps the add-on generation and cancels the job`() {
        val retire = block("private fun invalidateAddonSearch() {", "\n    }")
        assertTrue(
            "the token must advance or a pending publish stays valid",
            retire.contains("addonSearchGeneration += 1")
        )
        assertTrue(
            "and the running job is stopped outright",
            retire.contains("addonSearchJob?.cancel()")
        )
        val search = block("    fun search(query: String) {", "\n    }")
        assertTrue(
            "starting a new search retires the previous add-on wave",
            search.contains("invalidateAddonSearch()")
        )
        val launch = block("    private fun launchAddonSearch(", "addonSearchJob = viewModelScope.launch {")
        assertTrue(
            "and the wave captures the token it must still match before writing",
            launch.contains("val generation = addonSearchGeneration")
        )
    }

    @Test
    fun `every add-on publish is gated on the token it captured`() {
        val source = source(MODELS)
        assertTrue(
            "the eager per-add-on snapshot must check the token",
            source.contains("if (snapshot.isNotEmpty() && generation == addonSearchGeneration)")
        )
        assertTrue(
            "and so must the consolidated rail publish",
            source.contains("if (generation != addonSearchGeneration) return@launch")
        )
        assertTrue(
            "and the enrichment republish",
            source.contains("if (generation == addonSearchGeneration && enriched != visibleGroups)")
        )
        assertTrue(
            "the token is threaded into the collector that publishes snapshots",
            source.contains("searchAddonCatalogs(query, tmdbKeysDeferred, generation)") &&
                source.contains("private suspend fun searchAddonCatalogs(")
        )
    }

    private companion object {
        const val MODELS = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
