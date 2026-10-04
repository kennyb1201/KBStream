package com.kennyb1201.kbstream.data.watched

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Mark as Unwatched" must actually take, and stay taken.
 *
 * The manual override is only ONE of the watched signals the resolver reads:
 * isMovieLocallyWatched() answers from the history row's progress, so a film
 * stopped a couple of minutes shy of the end re-resolved as watched the moment
 * anything re-read the history - the checkmark was back within the minute. And
 * Simkl re-promotes from an OPEN playback session, so deleting just the history
 * record left the session to re-add the watched status on the next feed
 * refresh. A dropped cleanup compiles cleanly and is invisible without a live
 * tracker and a played title, so both halves are read from the source.
 */
class UnmarkClearsPlaybackContractTest {

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

    private fun repository(): String {
        val file = File(
            sourceRoot,
            "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
        )
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of markUnwatchedLocal, up to the next function declaration. */
    private fun unmarkBody(): String {
        val src = repository()
        val start = src.indexOf("suspend fun markUnwatchedLocal(")
        assertTrue("markUnwatchedLocal must exist", start >= 0)
        val end = src.indexOf("suspend fun clearWatchedOverride(", start)
        assertTrue("the body must be bounded by the next function", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `the unmark drops the local history rows that keep the badge on`() {
        val body = unmarkBody()
        assertTrue(
            "the local resume/completed rows are a separate watched signal " +
                "(isMovieLocallyWatched) and must be deleted too",
            body.contains("historyDao.deleteById(") &&
                body.contains("historyDao.deleteResumeRowsForParents(") &&
                body.contains("historyDao.deleteCompletedForParents(")
        )
        assertTrue(
            "the deleted rows must be removed from the cloud as well, or the " +
                "next pull re-inserts them and the state returns",
            body.contains("SupabaseSync") && body.contains(".deleteHistoryRows(")
        )
    }

    @Test
    fun `the unmark closes the Simkl playback session`() {
        val body = unmarkBody()
        assertTrue(
            "removeWatched* rewrites history but leaves the paused playback " +
                "session, which Simkl re-promotes straight back to watched",
            body.contains("deletePlaybackSessionsForParent(")
        )
    }
}
