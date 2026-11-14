package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic push's diagnostics, wired (spec 2026-11-14, F1/F2/F3).
 *
 * [SyncPushDiagnosticsTest] proves the verdict strings say the right things.
 * This pins where they are written, because the field failure was an ABSENCE:
 * three builds of `lastPush=never pending=0` and no line saying whether the
 * backfill found nothing, the queue arm never fired, or the flush loop was not
 * running. Every exit of the backfill, the loop's start/heartbeat/exit and the
 * flush's own row counts have to be recorded, and the two panel lines have to be
 * durable so a force-stop + reopen still answers.
 *
 * Pinned by reading the sources: the sync layer needs Android and Supabase to
 * instantiate, which this module's JVM tests do not have.
 */
class SyncPushDiagnosticsContractTest {

    private val sync by lazy { source("src/main/java/com/kennyb1201/kbstream/data/sync/SupabaseSync.kt") }
    private val backfillSource by lazy { source("src/main/java/com/kennyb1201/kbstream/data/sync/TrackerAuthBackfill.kt") }
    private val diagnostics by lazy { source("src/main/java/com/kennyb1201/kbstream/data/reporting/Diagnostics.kt") }
    private val tvPanel by lazy { source("src/main/java/com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt") }
    private val phonePanel by lazy { source("src/main/java/com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt") }

    private val backfillFun by lazy { block("private suspend fun backfillUnpublishedTrackerAuth(") }
    private val pullFun by lazy { block("private suspend fun pullAllNow(") }
    private val loopFun by lazy { block("private fun startPeriodicFlush(") }
    private val flushFun by lazy { block("private suspend fun flushOutbox(") }
    private val scheduleFun by lazy { block("private fun scheduleFlush(") }
    private val recordVerdictFun by lazy { block("private fun recordBackfillVerdict(") }
    private val loopStartedFun by lazy { block("private fun recordFlushLoopStarted(") }
    private val backfillAccessor by lazy { block("fun backfillDiagnostics(") }
    private val loopAccessor by lazy { block("fun flushLoopDiagnostics(") }

    @Test
    fun `every backfill exit records a verdict`() {
        assertEquals(
            "signed out, profile changed, and the real run: three verdicts, so a " +
                "quiet backfill can never be silent",
            3,
            countOf(backfillFun, "recordBackfillVerdict(")
        )
        assertTrue(
            "a real run names every enrolled key, not just the ones this device holds",
            backfillFun.contains("TrackerAuthBackfill.enrolledKeys()")
        )
        assertTrue(
            "and says whether the session is local at all - a pulled session is a " +
                "correct nothing-due, not a broken backfill",
            backfillFun.contains("localSession = entry != null") &&
                backfillFun.contains("gateOpen = entry?.hasSomethingToPublish == true")
        )
    }

    @Test
    fun `a pull that ran nothing records why the backfill never asked`() {
        assertTrue(
            "the pull gate sits before the backfill; skipping it silently is one of " +
                "the three breaks this had to rule on",
            pullFun.contains("pullRan = false")
        )
        assertTrue(
            "and a swallowed throw is recorded as its class",
            pullFun.contains(".onFailure { error -> recordBackfillFailure(context, error) }")
        )
    }

    @Test
    fun `the flush loop records start, heartbeat and both of its exits`() {
        assertTrue(loopFun.contains("recordFlushLoopStarted(it)"))
        assertTrue("a heartbeat per wake", loopFun.contains("recordFlushLoopAlive(it)"))
        assertTrue(
            "the signed-out exit",
            loopFun.contains("recordFlushLoopExited(it, SyncPushDiagnostics.LOOP_SIGNED_OUT)")
        )
        assertTrue(
            "and the cancelled one, rethrown so cancellation still behaves",
            loopFun.contains("recordFlushLoopExited(it, SyncPushDiagnostics.LOOP_CANCELLED)") &&
                loopFun.contains("throw cancelled")
        )
    }

    @Test
    fun `a loop that died with its process is named at the next start`() {
        assertTrue(
            "the stored state still saying alive is the only evidence a kill leaves",
            loopStartedFun.contains("== SyncPushDiagnostics.LOOP_ALIVE") &&
                loopStartedFun.contains("SyncPushDiagnostics.LOOP_PROCESS_END")
        )
        assertTrue(
            "and a start is recorded as one",
            loopStartedFun.contains("\"flush loop: started\"")
        )
    }

    @Test
    fun `the flush and the enqueue arm are recorded with their numbers`() {
        assertTrue(
            "rows attempted and rows uploaded, durably",
            flushFun.contains("SyncPushDiagnostics.PREFS_FLUSH_ATTEMPTED") &&
                flushFun.contains("SyncPushDiagnostics.PREFS_FLUSH_UPLOADED")
        )
        assertTrue(
            "a flush that uploaded nothing is the interesting one",
            flushFun.contains("SyncPushDiagnostics.flushOutcomeLine(batch.size, 0)")
        )
        val scheduled = scheduleFun.indexOf("recordFlushScheduled(it)")
        val earlyReturn = scheduleFun.indexOf("if (flushJob?.isActive == true) return")
        assertTrue(
            "the arm that starts a flush is counted, after the guard: a swallowed arm " +
                "is not an arm, and this path must not write prefs on every write",
            scheduled > earlyReturn && earlyReturn >= 0
        )
    }

    @Test
    fun `the pull-end verdict names a dead loop`() {
        assertTrue(
            "the single most useful line in the spec",
            recordVerdictFun.contains("SyncPushDiagnostics.pullEndLoopLine(false)")
        )
    }

    @Test
    fun `the panel lines are durable, not in-memory`() {
        assertTrue(
            "the backfill summary is read from the sync layer's own prefs",
            backfillAccessor.contains("syncPrefs(context)") &&
                backfillAccessor.contains("SyncPushDiagnostics.PREFS_BACKFILL")
        )
        assertTrue(
            "the loop's state comes from the live Job, its history from prefs",
            loopAccessor.contains("flushLoopRunning()") &&
                loopAccessor.contains("SyncPushDiagnostics.PREFS_LOOP_STATE") &&
                loopAccessor.contains("SyncPushDiagnostics.PREFS_LOOP_ALIVE_AT")
        )
    }

    @Test
    fun `both panels and the paste carry the two lines`() {
        assertTrue(tvPanel.contains("SyncStatRow(\"BACKFILL\""))
        assertTrue(tvPanel.contains("SyncStatRow(\"FLUSH LOOP\""))
        assertTrue(phonePanel.contains("SyncStatRowMobile(\"BACKFILL\""))
        assertTrue(phonePanel.contains("SyncStatRowMobile(\"FLUSH LOOP\""))
        assertTrue(diagnostics.contains("append(\"sync backfill: \")"))
        assertTrue(diagnostics.contains("append(\"sync flush loop: \")"))
    }

    @Test
    fun `no recorded line carries a payload, a token or a fingerprint`() {
        val marker = "CrashReporter.recordEvent(\"sync\", "
        var from = sync.indexOf(marker)
        assertTrue("at least the four verdict lines are recorded", from >= 0)
        var checked = 0
        val recorded = mutableListOf<String>()
        while (from >= 0) {
            val tail = sync.substring(from + marker.length).trimStart()
            val argument = tail.takeWhile { it != '\n' }
            recorded += argument
            assertTrue(
                "a recorded sync line must be a builder or a fixed phrase, not an " +
                    "interpolated payload: ${argument.take(60)}",
                tail.startsWith("SyncPushDiagnostics.") || tail.startsWith("\"flush loop: ")
            )
            checked++
            from = sync.indexOf(marker, from + marker.length)
        }
        assertTrue("checked $checked", checked >= 4)
        // None of these words belongs in a line that is pasted publicly - the
        // builders cannot produce them (SyncPushDiagnosticsTest pins the
        // vocabulary), and no call site may pass one through.
        listOf("payload", "accessToken", "refreshToken", "verifier", "clientId", "secret")
            .forEach { forbidden ->
                recorded.forEach { argument ->
                    assertFalse("$forbidden reached a recorded sync line: $argument", argument.contains(forbidden))
                }
            }
    }

    @Test
    fun `the backfill's membership is readable, not duplicated`() {
        assertTrue(
            "the diagnostics report absent keys from the enrollment's own list",
            backfillSource.contains("fun enrolledKeys(): List<String> = ENROLLED_KEYS")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** One function's source: from its declaration to the next declaration at the same indent. */
    private fun block(declaration: String): String {
        val at = sync.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = sync.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        var i = bodyStart
        while (i < sync.length) {
            when (sync[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return sync.substring(at, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "$prefix$relativePath")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relativePath")
    }

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }
}
