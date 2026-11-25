package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5's question, made answerable from one paste.
 *
 * The field report's `catalogPaint=59684ms` sits between the pinned paint and the
 * catalog snapshot publish, and that window has three candidates: a build waiting
 * for the predecessor's fan-out to release `railsRefreshMutex`, the snapshot read
 * and decode itself, and the upcoming filter over its items. The `homeload:` line
 * joins the LATEST value per label, so it can only separate them if each states
 * its own number - and the snapshot's volume has to be in the paste too, because
 * a slow decode of a small blob and a fast decode of a huge one want opposite
 * fixes (F5 says so explicitly).
 *
 * None of this is visible to a unit test at runtime, so it is pinned from source:
 * these are the records that decide whether the spec's one number can be split.
 */
class HomeSnapshotTimingContractTest {

    private val viewModel: String by lazy { stripComments(source(VIEW_MODEL)) }
    private val snapshot: String by lazy { stripComments(source(SNAPSHOT)) }

    @Test
    fun `the snapshot records its size and what it decoded`() {
        assertTrue(
            "the blob's size is not recorded, so the paste cannot tell whether " +
                "the 60s is I/O volume or decode cost",
            snapshot.contains("PerfTrace.recordCount(\"home.snapshotChars\"")
        )
        assertTrue(
            "the decoded rail count is not recorded",
            snapshot.contains("PerfTrace.recordCount(\"home.snapshotRails\"")
        )
        assertTrue(
            "the decoded ITEM count is not recorded - the upcoming filter walks " +
                "items, so rails alone cannot size the segment",
            snapshot.contains("PerfTrace.recordCount(\"home.snapshotItems\"")
        )
        assertTrue(
            "an absent snapshot must record zeroes too, or a cold launch is " +
                "indistinguishable from a build whose records never ran",
            snapshot.contains("(blob?.length ?: 0)") &&
                snapshot.contains("PerfTrace.recordCount(\"home.snapshotRails\", 0L)")
        )
    }

    @Test
    fun `the load is timed and off the main thread`() {
        assertTrue(
            "the snapshot load is not timed, so its share of the window is unknown",
            viewModel.contains("PerfTrace.timedSuspend(\"home.snapshotLoad\")")
        )
        val timed = viewModel.substringAfter("PerfTrace.timedSuspend(\"home.snapshotLoad\")")
            .substringBefore("cachedCatalogRails")
        assertTrue(
            "the snapshot load runs on the Main dispatcher: decoding up to 32 rails " +
                "x 40 items there is a stalled frame, then a paint nobody sees",
            timed.contains("withContext(Dispatchers.Default)")
        )
        assertTrue(
            "the upcoming filter no longer runs inside the snapshot load, so its " +
                "cost is no longer part of the segment the paste can see",
            timed.contains("filterUpcoming(rail.items)")
        )
    }

    @Test
    fun `a wait for a predecessor is its own number`() {
        assertTrue(
            "`home.mutexWait` is what separates a build that waited for the " +
                "previous build's fan-out from one that spent the time decoding " +
                "the snapshot: without it the two are the same 60 seconds",
            viewModel.contains("PerfTrace.record(\n                    \"home.mutexWait\"") ||
                viewModel.contains("PerfTrace.record(\"home.mutexWait\"")
        )
        assertTrue(
            "and the publish it all leads to is still recorded as catalogPaint",
            viewModel.contains("PerfTrace.record(\n                        \"home.catalogPaint\"") ||
                viewModel.contains("PerfTrace.record(\"home.catalogPaint\"")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/ui/home/"
        const val VIEW_MODEL = PACKAGE + "HomeViewModel.kt"
        const val SNAPSHOT = PACKAGE + "HomeCatalogSnapshot.kt"
    }
}
