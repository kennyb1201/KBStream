package com.kennyb1201.kbstream.data.mdblist

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two halves of the same story: the key's daily spend was invisible, and the
 * profile switch threw away the one thing that could have made a switch-back
 * cheap.
 *
 *  - The spend lived in `SharedPreferences` and only the interceptor's guard
 *    read it, so "we ran out again" could not be answered from a capture. It is
 *    one line now, printed by [mdbListBudgetLine].
 *  - A switch deleted the outgoing profile's disk rows, so switching back
 *    inside the six-hour disk window re-downloaded the whole watched history.
 *    The isolation never needed the delete - the memory cache is key-stamped
 *    and the disk rows are per-key - so the switch drops memory only.
 */
class MdbListBudgetLineTest {

    private val now = 1_767_000_000_000L

    // ── spec T4: the line is the interceptor's own count ───────────────

    @Test
    fun `the line reads the day's count against the API's limit`() {
        val line = mdbListBudgetLine(count = 37, limitedUntilMs = 0L, nowMs = now)
        assertTrue(line, line.startsWith("mdblist: budget=37/1000"))
        assertTrue(line, line.contains("(reads stop 850, writes stop 980)"))
        assertFalse("nothing to park when the day is not spent", line.contains("limited until"))
    }

    @Test
    fun `a spent day names when the allowance returns`() {
        val line = mdbListBudgetLine(count = 421, limitedUntilMs = now + 3_600_000L, nowMs = now)
        assertTrue(line, line.contains("budget=421/1000"))
        assertTrue(line, line.contains(", limited until "))
    }

    @Test
    fun `a park that already lifted is not reported`() {
        val line = mdbListBudgetLine(count = 980, limitedUntilMs = now - 1L, nowMs = now)
        assertFalse(line, line.contains("limited until"))
    }

    @Test
    fun `the line carries no key material`() {
        val line = mdbListBudgetLine(count = 12, limitedUntilMs = 0L, nowMs = now)
        assertFalse("a report is pasted into issue threads", line.contains("apikey"))
        assertFalse(line.contains("key="))
        assertTrue(
            "only digits and the fixed vocabulary",
            line.removePrefix("mdblist: ").matches(Regex("[a-z=/,()\\s0-9]+"))
        )
    }

    @Test
    fun `the ceilings come from the same constants the interceptor enforces`() {
        val client = source(CLIENT)
        assertTrue(client.contains("private const val READ_BUDGET = MDBLIST_READ_BUDGET"))
        assertTrue(client.contains("private const val TOTAL_CEILING = MDBLIST_TOTAL_CEILING"))
        assertTrue(
            "the line reads the bucket, rolled over against the persisted counter",
            client.contains("fun localBudgetLine(context: Context): String")
        )
        val line = client.substringAfter("fun localBudgetLine(context: Context): String")
            .substringBefore("Shared GET returning a parsed body string")
        assertTrue(line.contains("ensureBudget(budget, tag)"))
        assertTrue(line.contains("count = budget.count.get()"))
        assertTrue(line.contains("limitedUntilMs = budget.limitedUntilMs"))
        assertFalse(
            "the raw key must never be printed",
            line.contains("apikey") || line.contains("\$key")
        )
    }

    @Test
    fun `diagnostics prints it right after the sync line`() {
        val diagnostics = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
        val sync = diagnostics.indexOf("report.appendLine(syncLine())")
        val budget = diagnostics.indexOf("report.appendLine(MdbListClient.localBudgetLine(app))")
        assertTrue("the sync line is missing", sync > 0)
        assertTrue("the budget line is missing", budget > 0)
        assertTrue(
            "the line belongs immediately after sync, where a capture reads it",
            budget > sync && budget - sync < 400
        )
    }

    // ── spec T3: a switch keeps the disk rows ──────────────────────────

    @Test
    fun `the profile switch drops memory only`() {
        val profile = source("com/kennyb1201/kbstream/data/sync/ProfileManager.kt")
        assertTrue(
            "the switch must not delete the outgoing profile's disk rows",
            profile.contains("MdbListClient.clearTransientMemory()")
        )
        assertFalse(
            profile.contains("MdbListClient.clearTransientCaches()")
        )
    }

    @Test
    fun `the memory-only drop deletes nothing from disk`() {
        val write = body("fun clearTransientMemory() {", "Profile-switch isolation, the FULL drop")
        assertTrue(write.contains("cachedSnapshot = null"))
        assertTrue(write.contains("cachedPlayback = null"))
        assertTrue(write.contains("cachedSnapshotKey = \"\""))
        assertTrue(write.contains("cachedPlaybackKey = \"\""))
        assertFalse(
            "a delete here is exactly what made a switch-back re-download",
            write.contains("deleteByKeys")
        )
        assertFalse(write.contains("snapshotDiskKey"))
    }

    @Test
    fun `isolation is the key stamp, not the delete`() {
        val client = source(CLIENT)
        assertTrue(client.contains("private fun snapshotDiskKey(apiKey: String) = \"mdblist:snapshot:\${keyTag(apiKey)}\""))
        assertTrue(client.contains("private fun playbackDiskKey(apiKey: String) = \"mdblist:playback:\${keyTag(apiKey)}\""))
        val read = client.substringAfter("suspend fun getWatchedSnapshot(")
            .substringBefore("private fun getString")
        assertTrue(
            "an incoming profile can never be served the outgoing one's cache",
            read.contains("cachedSnapshotKey == apiKey")
        )
    }

    // ── source helpers ────────────────────────────────────────────────

    private fun body(after: String, before: String): String {
        val client = source(CLIENT)
        assertTrue("missing marker: $after", client.contains(after))
        assertTrue("missing marker: $before", client.contains(before))
        return client.substringAfter(after).substringBefore(before)
    }

    private val sourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val CLIENT = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
}
