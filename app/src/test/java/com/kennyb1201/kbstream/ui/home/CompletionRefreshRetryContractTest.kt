package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A completion keeps asking Continue Watching to re-bind while Simkl's feed
 * catches up.
 *
 * Reported: after finishing an episode and going Home, the finished card sat
 * there for a couple of minutes - the Continue Watching feed's cache window -
 * before the next episode showed. The completion push invalidates the feed on
 * our side, and Home drops the cached copy and re-merges once (see
 * ContinueWatchingRefreshBus); but Simkl's feed can lag that push server-side,
 * so a single re-merge can read the pre-completion list and re-cache it for the
 * whole TTL. The follow-up re-merges are the piece that makes a lagging feed
 * self-heal without the viewer bouncing out of Home and back.
 *
 * It reads the source instead of calling it for the same reason
 * HomeBrowseShortcutRemovalContractTest and
 * DigitalFilterSurfacesContractTest do: a dropped re-merge does not fail to
 * compile and cannot be reached by a behavioural test without a running player
 * and a networked tracker.
 */
class CompletionRefreshRetryContractTest {

    private companion object {
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SCHEDULE = "COMPLETION_REFRESH_RETRY_MS"
        const val CLEAR_FEED = "simklRepository.clearContinueWatchingCache()"
        const val RE_MERGE = "refreshUpNext()"
    }

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

    private fun homeViewModel(): String {
        val file = File(sourceRoot, HOME_VIEW_MODEL)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The completion collector's body, up to the next section of `init`. */
    private fun completionCollectorBody(src: String): String {
        val start = src.indexOf("ContinueWatchingRefreshBus.requests.collect")
        assertTrue(
            "HomeViewModel must subscribe to the completion refresh bus",
            start >= 0
        )
        val end = src.indexOf("observeProfileSwitches()", start)
        assertTrue(
            "the completion collector body is not bounded by the next init call",
            end > start
        )
        return src.substring(start, end)
    }

    @Test
    fun `the completion drops the cached feed and re-merges`() {
        val body = completionCollectorBody(homeViewModel())
        assertTrue(
            "the completion must drop Simkl's cached feed before re-merging, or " +
                "the merge reads the pre-completion list from its TTL cache",
            body.contains(CLEAR_FEED)
        )
        assertTrue(
            "the completion must actually ask Home to re-merge",
            body.contains(RE_MERGE)
        )
    }

    @Test
    fun `the completion keeps re-merging so a lagging feed self-heals`() {
        val body = completionCollectorBody(homeViewModel())
        assertTrue(
            "a single re-merge is not enough: Simkl's feed can lag the push, so " +
                "the read above can re-cache the pre-completion list for the " +
                "whole TTL. The completion must schedule bounded follow-up " +
                "re-merges from $SCHEDULE",
            body.contains(SCHEDULE) && body.contains("for (retryAtMs in")
        )
        assertTrue(
            "each follow-up must drop the cached feed too, or it re-reads the " +
                "stale copy it is trying to refresh",
            body.windowed(CLEAR_FEED.length).count { it == CLEAR_FEED } >= 2
        )
        assertTrue(
            "the follow-ups must re-merge, not just clear the cache",
            body.windowed(RE_MERGE.length).count { it == RE_MERGE } >= 2
        )
    }

    @Test
    fun `a second completion restarts the window instead of stacking one`() {
        val body = completionCollectorBody(homeViewModel())
        assertTrue(
            "the retry job must be replaced (cancel-then-assign) so two " +
                "completions in a row cannot leave two windows re-merging at once",
            body.contains("completionRefreshRetryJob?.cancel()") &&
                body.contains("completionRefreshRetryJob =")
        )
    }

    @Test
    fun `the retry schedule is bounded and monotonic`() {
        val src = homeViewModel()
        val start = src.indexOf("private val $SCHEDULE")
        assertTrue("the retry schedule must exist", start >= 0)
        val end = src.indexOf(")", start)
        val block = src.substring(start, end)
        val delays =
            Regex("(\\d+)_\\d{3}L")
                .findAll(block)
                .map { it.groupValues[1].toLong() * 1_000L }
                .toList()
        assertTrue("the schedule must have several retries, not one", delays.size >= 3)
        assertTrue(
            "the delays must increase, so a feed that is slow to catch up is " +
                "polled more cheaply than a fast one: $delays",
            delays.zipWithNext().all { (a, b) -> b > a }
        )
        assertTrue(
            "the window must stay bounded so a completion cannot leave an " +
                "endless re-merge loop: $delays",
            delays.last() <= 5 * 60_000L
        )
    }
}
