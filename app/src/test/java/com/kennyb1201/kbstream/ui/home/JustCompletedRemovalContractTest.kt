package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local completion is what takes a just-finished card off Continue
 * Watching - not the tracker feed's schedule.
 *
 * Reported: after watching something, its card stayed on the rail for around
 * forty-five seconds. The local row leaves the rail's own query the instant the
 * player writes it, but a card sourced from a tracker feed is re-read on a
 * coarse retry schedule, so the finished title sat there until the tracker
 * agreed. These assertions cover the wiring a behavioural test cannot reach
 * without a running player, a database and a networked tracker:
 *
 *  - the read that proves the completion is local, recent and unresumed;
 *  - the drop happens at the one publish choke point, so EVERY branch (the
 *    early local publish, a FAILED tracker fetch that restores the previous
 *    cards, and the merged publish) is covered;
 *  - the completion's own signal drops the card immediately, before the
 *    re-merge that may read a still-lagging feed.
 */
class JustCompletedRemovalContractTest {

    private companion object {
        const val DAO = "com/kennyb1201/kbstream/data/history/WatchHistoryDao.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val READ = "getRecentlyCompletedWithoutResume"
        const val DROP = "dropJustCompletedTrackerCards"
        const val CHOKE_POINT = "applyContinueWatchingDismissals"
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The publish choke point's body, up to the next declared member. */
    private fun chokePointBody(src: String): String {
        val start = src.indexOf("private suspend fun $CHOKE_POINT(")
        assertTrue("HomeViewModel must still have a publish choke point", start >= 0)
        val end = src.indexOf("\n    /**", start)
        assertTrue("the choke point body is not bounded by the next member", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `the completed-row read is recent and excludes resumed titles`() {
        val dao = source(DAO)
        val start = dao.indexOf("suspend fun $READ(")
        assertTrue("the read must exist", start >= 0)

        // The KDoc + query above the declaration.
        val block = dao.substring((start - 1400).coerceAtLeast(0), start)
        assertTrue(
            "the read must be bounded to RECENT completions, or it would hide " +
                "tracker cards for titles finished long ago",
            block.contains("updatedAt >= :since")
        )
        assertTrue(
            "the read must exclude parents that still have an in-progress row, or " +
                "a title the viewer has started again would be hidden",
            block.contains("isCompleted = 1") &&
                block.contains("parentId NOT IN") &&
                block.contains("isCompleted = 0")
        )
    }

    @Test
    fun `the drop is applied at the publish choke point`() {
        val body = chokePointBody(source(HOME_VIEW_MODEL))
        assertTrue(
            "every publish path must run the just-completed drop, or a FAILED " +
                "tracker fetch puts the finished card back",
            body.contains(DROP)
        )
        assertTrue(
            "the drop must cover the no-dismissals early return too",
            body.contains("return $DROP(") &&
                body.windowed(DROP.length).count { it == DROP } >= 2
        )
    }

    @Test
    fun `the completion drops the card before the re-merge`() {
        val src = source(HOME_VIEW_MODEL)
        val start = src.indexOf("ContinueWatchingRefreshBus.requests.collect")
        assertTrue(
            "HomeViewModel must subscribe to the completion refresh bus",
            start >= 0
        )
        val end = src.indexOf("observeProfileSwitches()", start)
        assertTrue("the collector body is not bounded", end > start)
        val body = src.substring(start, end)

        val dropAt = body.indexOf(DROP)
        val reMergeAt = body.indexOf("refreshUpNext()")
        assertTrue("the completion must drop the finished card immediately", dropAt >= 0)
        assertTrue("the completion must still re-merge", reMergeAt > dropAt)
    }

    @Test
    fun `the suppression window is bounded`() {
        val src = source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
        val start = src.indexOf("internal const val JUST_COMPLETED_CARD_SUPPRESSION_MS")
        assertTrue("the window must exist", start >= 0)
        val declaration = src.substring(start, (start + 160).coerceAtMost(src.length))
        assertTrue(
            "the window must be a minute-scale bound, not an open one: $declaration",
            Regex("(\\d+)L? ?\\* ?60L? ?\\* ?1000L").containsMatchIn(declaration)
        )
    }
}
