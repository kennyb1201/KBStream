package com.kennyb1201.kbstream.data.watched

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV launcher's Watch Next channel mirrors the active profile's resume
 * rows, and a manual mark never travels through a player — the one place that
 * used to republish them. Every local mark/unmark that removes resume rows
 * therefore has to republish too, or the launcher kept showing an episode this
 * device had just marked watched until the next playback save.
 *
 * Pinned at the source: the three paths run on a live device and cannot be
 * driven without one, but the wiring is the whole fix.
 */
class WatchNextPublishContractTest {

    @Test
    fun `every local mark that removes resume rows republishes the launcher`() {
        listOf(
            "suspend fun markWatchedLocal(",
            "suspend fun markEpisodeWatchedLocal(",
            "suspend fun markUnwatchedLocal("
        ).forEach { signature ->
            val body = functionBody(signature)
            assertTrue(
                "$signature removed resume rows but never republished Watch Next",
                body.contains("publishLauncherWatchNext()")
            )
        }
    }

    @Test
    fun `the episode mark announces the badge on the watched-state bus`() {
        val body = functionBody("suspend fun markEpisodeWatchedLocal(")
        assertTrue(
            "every other local write in this file announces its result, but this " +
                "one never told the bus anything: the marker landed on disk and the " +
                "tile the mark was made FROM - a Continue Watching card, a Search " +
                "result - kept the badge it had until the next marker preload",
            body.contains("WatchStateBus.notifyChanged(")
        )
        assertTrue(
            "announced through the resolver the preload itself uses, not a " +
                "hand-rolled pair of flags: a series resolves as override/tracker " +
                "sets, then Simkl, then MDBList, then local history, and a second " +
                "copy of that rule here is how the two drift apart",
            body.contains("resolveWithMergedRemoteSets(")
        )
        assertTrue(
            "and the answer is persisted the way the resolver's own callers do, so " +
                "a preload inside this entry's TTL reads what the bus just " +
                "announced instead of the pre-write row",
            body.contains("persistResolvedEntities(")
        )
        assertTrue(
            "the checkmark is not cleared by an episode mark: the resolved entity's " +
                "own isWatched travels, and the eye only when it is not also watched",
            body.contains("entity.isPartiallyWatched && !entity.isWatched")
        )
    }

    @Test
    fun `the republish mirrors the active profile's own resume rows`() {
        val body = functionBody("private suspend fun publishLauncherWatchNext(")
        assertTrue(
            "the sync must read the scoped (active-profile) rows",
            body.contains("WatchHistoryDatabase.withScopedDao(context)")
        )
        assertTrue(
            "and publish exactly the rows the launcher draws from",
            body.contains("dao.getResumeRowsForLauncher()")
        )
        assertTrue(
            "through the launcher publisher",
            body.contains("TvLauncherPublisher.sync(context,")
        )
        assertTrue(
            "and best-effort means a FAILED sync does not fail the mark - not " +
                "that cancellation is swallowed. The block suspends (the scoped " +
                "dao and the publisher both do), so plain runCatching would turn " +
                "\"this coroutine was canceled\" into a reported failure AND let " +
                "the coroutine run on (see runCatchingCancellable)",
            body.contains("runCatchingCancellable {") && !body.contains("runCatching {")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), WATCHED)
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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(signature: String): String {
        val src = readSource()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val WATCHED = "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
    }
}
