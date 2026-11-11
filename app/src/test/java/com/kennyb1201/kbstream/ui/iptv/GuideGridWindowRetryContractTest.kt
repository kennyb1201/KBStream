package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed grid-window read is retried, not remembered as read.
 *
 * Reported 2026-11-11: a channel row stayed empty in both grids while its list
 * row showed now/next - the on-device tell of this bug. `requestGuideGridWindow`
 * computed the missing (channel, window) keys and then added all of them to
 * `gridWindowReads` BEFORE the read launched, and the read swallowed a thrown
 * failure into an empty answer. `mergeWindowAnswer` merges that empty answer
 * over the existing programs, so a row that already had programs never blanked
 * - but a channel with nothing yet for that window stayed all gaps, and every
 * later request for the window found `missing` empty and returned without
 * reading. The only exits were the 4,096-key eviction or an import.
 *
 * The fix moves the marking onto the SUCCESS path (a window that legitimately
 * holds no programs is still read once) and gives the failure its own log and
 * trace line, because the success line - `answered=0 rows=0` - reads the same
 * for a failed read and an empty window.
 *
 * Source-reading style: this module has no ViewModel/Room harness, and the
 * behaviour under test is the order of three statements around a coroutine.
 * Every test below fails against the 0.9.223 source.
 */
class GuideGridWindowRetryContractTest {

    private val viewModel by lazy {
        source("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
    }

    private val rules by lazy {
        source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")
    }

    /** The window read, from its declaration to the key helper below it. */
    private val read by lazy {
        viewModel.slice("fun requestGuideGridWindow(", "private fun gridWindowKey(")
    }

    /** What a successful read does. */
    private val success by lazy {
        read.slice("result.onSuccess { loaded ->", "result.onFailure { error ->")
    }

    /** What a failed read does. */
    private val failure by lazy {
        read.slice(
            "result.onFailure { error ->",
            "_gridLoadingChannelIds.value = _gridLoadingChannelIds.value - missingIds"
        )
    }

    // ── F1: marking is the success path, not the request path ──────────────

    @Test
    fun `the keys are marked after the read answers, never before it launches`() {
        assertEquals(
            "one marking site, and only one",
            1,
            read.count("gridWindowReads +=")
        )
        assertTrue(
            "F1: the marking sits inside the launched coroutine, after the read's result",
            read.indexOf("gridWindowReads +=") > read.indexOf("viewModelScope.launch {") &&
                read.indexOf("gridWindowReads +=") > read.indexOf("val result = runCatching {") &&
                success.contains("gridWindowReads += gridWindowKey(it.id, windowStart, windowEnd)")
        )
        assertFalse(
            "F1: and the old request-path marking (the whole bug) is gone: it added every " +
                "missing key before the read had a chance to fail",
            read.contains(
                "if (missing.isEmpty()) return\n" +
                    "        missing.forEach { gridWindowReads += gridWindowKey(it.id, windowStart, windowEnd) }"
            )
        )
        assertTrue(
            "F1: the read no longer swallows its failure into an empty answer - a thrown " +
                "read has to reach the failure branch",
            !read.contains(".getOrDefault(emptyMap())") &&
                read.contains("result.onFailure { error ->")
        )
    }

    @Test
    fun `a failed read leaves the window unmarked, so the next request reads it`() {
        assertFalse(
            "F1: the failure branch marks nothing - that is what makes the retry possible",
            failure.contains("gridWindowReads +=")
        )
        assertTrue(
            "F1/F2: the next request still asks which keys are unread, so the failed " +
                "window is found again and read",
            read.contains("gridWindowKey(channel.id, windowStart, windowEnd) !in gridWindowReads") &&
                read.contains("if (missing.isEmpty()) return")
        )
        assertTrue(
            "and the filter still runs before the launch, against the same set the success " +
                "path writes",
            read.indexOf("!in gridWindowReads") < read.indexOf("viewModelScope.launch {")
        )
        assertFalse(
            "no fence added: two overlapping requests may both see the key missing - the " +
                "merge is idempotent, so the duplicate read is accepted",
            read.contains("synchronized(")
        )
    }

    @Test
    fun `a successful empty answer still counts as read`() {
        assertFalse(
            "F1: the criterion is success, not a non-empty answer - marking only non-empty " +
                "windows would re-read a legitimately empty one on every scroll",
            success.contains("loaded.isEmpty()") ||
                success.contains("loaded.isNotEmpty()") ||
                success.contains("if (loaded")
        )
        assertTrue(
            "F1: the marking is the first thing a successful answer does, before the merge",
            success.indexOf("gridWindowReads +=") in 0 until success.indexOf("mergeWindowAnswer(")
        )
        assertTrue(
            "the eviction cap is unchanged, and it too only runs on a successful read",
            success.contains("while (gridWindowReads.size > GRID_WINDOW_READ_MEMORY)") &&
                success.contains("eldest.remove()")
        )
    }

    // ── F2: the failure is distinguishable in a paste ──────────────────────

    @Test
    fun `a failed read logs and notes its own line`() {
        assertTrue(
            "F2: the line names the window and the failure, and cannot be confused with the " +
                "success line's answered=0",
            failure.contains("\"GUIDE GRID window=\$windowStart..\$windowEnd read failed: \$reason\"")
        )
        assertTrue(
            "F2: both sinks - logcat and the trace COPY DIAGNOSTICS prints (a release APK " +
                "has no logcat and a TV cannot read one)",
            failure.contains("Log.w(TAG, failedLine)") &&
                failure.contains("GuideTrace.note(failedLine)")
        )
        assertTrue(
            "F2: the reason is redacted like every other logged throwable message, and falls " +
                "back to the exception's name rather than printing an empty tail",
            failure.contains("Redaction.text(error.message)") &&
                failure.contains("ifBlank { error.javaClass.simpleName }")
        )
        assertTrue(
            "and the failure line is written INSIDE the failure branch only",
            failure.indexOf("read failed") in 0 until failure.indexOf("Log.w(TAG, failedLine)") &&
                !success.contains("read failed")
        )
    }

    @Test
    fun `the success line is unchanged`() {
        assertTrue(
            "F2: same tag, same words, same counters - a successful read logs exactly what " +
                "it logged before",
            success.contains(
                "\"GUIDE GRID window=\$windowStart..\$windowEnd channels=\${missing.size} \" +"
            ) &&
                success.contains("\"answered=\${loaded.size} rows=\${loaded.values.sumOf { it.size }}\"") &&
                success.contains("Log.d(\n                    TAG,")
        )
        assertFalse(
            "F2: and the success path never writes the failure line",
            success.contains("GuideTrace.note(") || failure.contains("answered=")
        )
    }

    // ── Invariants: the merge, the loading ids, the import path ────────────

    @Test
    fun `the merge and the loading ids behave exactly as before`() {
        assertTrue(
            "a successful read merges its answer over the rows",
            success.contains("mergeWindowAnswer(") &&
                success.contains("loaded = loaded,")
        )
        assertTrue(
            "a failed read still merges an empty answer (so it can never blank a row) " +
                "instead of skipping the merge",
            failure.contains("mergeWindowAnswer(") &&
                failure.contains("loaded = emptyMap(),")
        )
        assertEquals(
            "the loading ids are cleared ONCE, after both paths",
            1,
            read.count("_gridLoadingChannelIds.value - missingIds")
        )
        assertTrue(
            "and set once, before the launch",
            read.contains("_gridLoadingChannelIds.value = _gridLoadingChannelIds.value + missingIds") &&
                read.indexOf("+ missingIds") < read.indexOf("viewModelScope.launch {")
        )
        assertTrue(
            "mergeWindowPrograms still keeps existing rows when an answer is empty",
            rules.contains("internal fun mergeWindowPrograms(") &&
                rules.contains("if (incoming.isEmpty()) return existing")
        )
        assertTrue(
            "and the import path still clears everything, which stays the wholesale reset",
            viewModel.contains("gridWindowReads.clear()")
        )
    }

    // ── source access ───────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker is missing after $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
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
}
