package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker marks the Continue Watching rail consults to condemn a superseded
 * resume row must survive the refresh that clears the watched-state caches.
 *
 * dropSupersededResumeRows runs at the TOP of each up-next cycle, before the
 * enrichment pass refills the live tracker map (trackerWatchedEpisodesByShow).
 * A refresh cleared that map and only THEN triggered the cycle, so the read
 * found it empty on exactly the refresh that most needed the signal, and the
 * tracker's condemn-a-stale-resume-row path never fired. clearWatchedStateCaches
 * now snapshots the live map first and dropSupersededResumeRows reads the
 * snapshot, while a profile switch drops it (the marks belong to the profile
 * being left). Reading the live map here again would silently restore the empty
 * read, which no behavioural test can see without a TV, so the wiring is
 * asserted from the source.
 */
class ResumeSuppressionTrackerSnapshotContractTest {

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

    private val viewModel: String
        get() = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

    @Test
    fun `the caches snapshot the tracker marks before clearing them`() {
        val vm = viewModel
        assertTrue(
            "the snapshot field must exist",
            vm.contains("private var resumeSuppressionTrackerMarks")
        )
        assertTrue(
            "an ordinary refresh must carry the live tracker map into the snapshot",
            vm.contains("trackerWatchedEpisodesByShow.toMap()")
        )
        assertTrue(
            "a profile switch must drop the snapshot, not the profile being left",
            vm.contains("resumeSuppressionTrackerMarks =\n                if (hard)")
        )
        assertTrue(
            "clearWatchedStateCaches must take the profile-switch flavor",
            vm.contains("private suspend fun clearWatchedStateCaches(hard: Boolean = false)")
        )
    }

    @Test
    fun `profile switches hard-clear the caches`() {
        assertTrue(
            "the switch path must pass hard = true",
            viewModel.contains("clearWatchedStateCaches(hard = true)")
        )
    }

    @Test
    fun `resume suppression reads the snapshot, not the cleared live map`() {
        val vm = viewModel
        // The read happens inside dropSupersededResumeRows; isolate its body.
        val start = vm.indexOf("private suspend fun dropSupersededResumeRows(")
        assertTrue("dropSupersededResumeRows must exist", start >= 0)
        val end = vm.indexOf("private suspend fun nextUpNextRequestVersion", start)
        val body = vm.substring(start, if (end > start) end else vm.length)
        assertTrue(
            "the suppression read must use the snapshot",
            body.contains("resumeSuppressionTrackerMarks")
        )
        assertFalse(
            "reading the live map here is the empty-read bug returning",
            body.contains("trackerWatchedEpisodesByShow.toMap()")
        )
    }
}
