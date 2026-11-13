package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide REFRESH outcome in the diagnostics (spec 2026-11-12, "Guide keeps
 * going blank").
 *
 * Field evidence, Fire TV Stick, build 0.9.253: `guide: 1 source(s)
 * channels=15425 programsInWindow=0 newestProgramEnd=none validators=yes`, a
 * 23 MB EPG database against 194 MB on the TCL, and a guide that went blank
 * repeatedly until a manual reimport. The state line proves the programs are
 * gone; it cannot say WHY, and three mechanisms produce exactly that paste:
 *
 *  - the periodic refresh never runs on that box (Fire OS / Doze / constraints),
 *  - it runs and fails (download, parse, write),
 *  - the stored window expired because refreshes were deferred - and the entry
 *    check that should catch the last one was answering "nothing to do".
 *
 * This pins the line that separates them, and the two rules the fix rests on:
 * an empty guide is never "fresh", on either path, and a landed in-app import
 * clears the failing state the worker records.
 */
class GuideRefreshDiagnosticsContractTest {

    private val diagnostics by lazy {
        read("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
    }
    private val worker by lazy {
        read("com/kennyb1201/kbstream/data/iptv/EpgRefreshWorker.kt")
    }
    private val viewModel by lazy {
        read("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
    }
    private val policy by lazy {
        read("com/kennyb1201/kbstream/data/iptv/GuideRefreshPolicy.kt")
    }
    private val workPolicies by lazy {
        read("com/kennyb1201/kbstream/work/WorkPolicies.kt")
    }
    private val scheduler by lazy {
        read("com/kennyb1201/kbstream/data/iptv/EpgRefreshScheduler.kt")
    }

    // ── F1: the line, and the fields that name the cause ──────────────────

    @Test
    fun `the report prints the refresh outcome right after the guide data`() {
        val data = diagnostics.indexOf("guideLine(app)?.let { report.appendLine(it) }")
        val refresh = diagnostics.indexOf("guideRefreshLine(app)?.let { report.appendLine(it) }")
        val match = diagnostics.indexOf("guideMatchLines(app).forEach { report.appendLine(it) }")
        assertTrue("the guide data line is gone", data >= 0)
        assertTrue("F1: the refresh line must be emitted", refresh >= 0)
        assertTrue("it belongs with the guide data, not elsewhere", data < refresh)
        assertTrue("and before the match section, so the guide block reads together", refresh < match)
    }

    @Test
    fun `the line carries every field the three causes need`() {
        val line = body(diagnostics, "private suspend fun guideRefreshLine(")
        // (a) worker not running: the job's own state; (b) failing: the flag and
        // the empty-run count; (c) window/entry: where the window ends, the age,
        // and the decision the NEXT open will take.
        listOf(
            "\"guide: refresh lastImport=\"",
            "\" failing=\"",
            "\" emptyRuns=\"",
            "\" attempt=\"",
            "\" stall=\"",
            "\" sources=\"",
            "\" stamped=\"",
            "\" endsAt=\"",
            "\" ageStale=\"",
            "\" fileMoved=\"",
            "\" entry=\"",
            "\" worker=\"",
            "\" next=\"",
            "\" attempts=\""
        ).forEach { field ->
            assertTrue("the refresh line must report $field", line.contains(field))
        }
    }

    @Test
    fun `every field reads state that already exists`() {
        val line = body(diagnostics, "private suspend fun guideRefreshLine(")
        // The same keys the worker and the in-app import write, in the same
        // profile-scoped store: a diagnostic that kept its own record would
        // report on itself rather than on the refresh.
        listOf(
            "epg_updated_at",
            "epg_attempt_at",
            "epg_last_stall_at",
            "epg_db_name",
            "epg_refresh_failing",
            "epg_empty_runs"
        ).forEach { key ->
            assertTrue("the refresh line reads $key", diagnostics.contains("\"$key\""))
        }
        assertTrue(
            "from the profile's iptv_prefs, the store both import paths write",
            line.contains("ProfileStorage.prefsName(context, EPG_PREFS_NAME)") &&
                diagnostics.contains("private const val EPG_PREFS_NAME = \"iptv_prefs\"")
        )
        assertTrue(
            "and the worker-state half comes from WorkManager's own view of the job",
            line.contains("EpgRefreshScheduler.scheduledWork(context)")
        )
    }

    @Test
    fun `the entry field is the Guide screen's own decision, not a second copy of it`() {
        val line = body(diagnostics, "private suspend fun guideRefreshLine(")
        assertTrue(
            "the paste must say what the next open WILL do, so it asks the same policy",
            line.contains("GuideRefreshPolicy.urgency(endsAt, now, ageStale, attempt)")
        )
        assertTrue(
            "and the age rule's value is shared, so the two cannot drift",
            viewModel.contains("const val EPG_REFRESH_MS = GuideRefreshPolicy.AGE_STALE_MS") &&
                policy.contains("const val AGE_STALE_MS = 12L * 60L * 60L * 1000L")
        )
    }

    // ── F2(c): an empty guide heals on the next entry ─────────────────────

    @Test
    fun `an empty guide is urgent on entry, spaced at the refresh cadence`() {
        assertTrue(
            "the empty case must not fall back to the age rule alone - that is the field bug",
            policy.contains("if (ageStale || attemptOverdue(nowMs, lastAttemptMs)) Urgency.URGENT")
        )
        assertTrue(
            "and the retry is spaced off the last pass that started, at the interval " +
                "the app already uses to avoid hammering a provider",
            policy.contains("const val EMPTY_GUIDE_RETRY_MS = EPG_ENTRY_RETRY_COOLDOWN_MS") &&
                policy.contains("nowMs - lastAttemptMs >= EMPTY_GUIDE_RETRY_MS")
        )
        assertTrue(
            "the entry check hands the stamp in",
            viewModel.contains("lastAttemptMs = prefs.getLong(KEY_EPG_ATTEMPT_AT, 0L)")
        )
    }

    @Test
    fun `both import paths record that a pass started`() {
        assertTrue(
            "the in-app pass stamps before it fetches anything",
            body(viewModel, "private suspend fun importGuideInternal(")
                .contains("markUpdated(KEY_EPG_ATTEMPT_AT)")
        )
        assertTrue(
            "and so does the worker, so the spacing covers a box whose guide is only " +
                "ever refreshed in the background",
            body(worker, "suspend fun doWork()").contains("putLong(KEY_EPG_ATTEMPT_AT, now)") &&
                worker.contains("const val KEY_EPG_ATTEMPT_AT = \"epg_attempt_at\"")
        )
        assertFalse(
            "an attempt stamp is NOT a success: it must not touch the freshness marker",
            body(worker, "suspend fun doWork()")
                .substringAfter("putLong(KEY_EPG_ATTEMPT_AT, now)")
                .substringBefore("// The shared instance")
                .contains("KEY_EPG_UPDATED_AT\", ")
        )
    }

    // ── F2(a)/(b): the worker, and the failing state ──────────────────────

    @Test
    fun `the worker asks the guide whether it has anything to draw`() {
        val gate = body(worker, "suspend fun doWork()")
        assertTrue(
            "it reads the guide's own newest program end before deciding",
            gate.contains("newestGuideProgramEndMs()")
        )
        assertTrue(
            "and hands the answer to the shared policy",
            gate.contains("guideHasPrograms")
        )
        assertTrue(
            "which treats an empty guide as needing an import whatever the marker says",
            workPolicies.contains("guideHasPrograms == false ->") &&
                workPolicies.contains("EpgRefreshDecision.IMPORT")
        )
    }

    @Test
    fun `a landed in-app import clears the worker's failing state`() {
        val pass = body(viewModel, "private suspend fun importGuideInternal(")
        assertTrue(
            "the landed branch clears it",
            pass.contains("markUpdated(KEY_EPG_UPDATED_AT)") &&
                pass.contains("clearRefreshFailure()")
        )
        val clear = body(viewModel, "private fun clearRefreshFailure()")
        assertTrue(
            "both keys the worker writes, or the flag stays 'failing' over a guide " +
                "the viewer already fixed",
            clear.contains("putBoolean(KEY_EPG_REFRESH_FAILING, false)") &&
                clear.contains("putInt(KEY_EPG_EMPTY_RUNS, 0)")
        )
        assertFalse(
            "and it is only reached by a pass that stored something - the freshness " +
                "marker's own guard",
            pass.substringBefore("if (lastError == null) {").contains("clearRefreshFailure()")
        )
    }

    @Test
    fun `the scheduled job can be named without guessing`() {
        assertTrue(
            "diagnostics report the job by the same name the scheduler enqueues",
            scheduler.contains("const val WORK_NAME = \"iptv_epg_refresh\"") &&
                scheduler.contains("fun scheduledWork(context: Context): ScheduledWork") &&
                diagnostics.contains("EpgRefreshScheduler.scheduledWork(context)")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** [source] from [marker] up to the next documented member, so a pin cannot read a neighbour. */
    private fun body(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("source marker missing: $marker", start >= 0)
        val end = source.indexOf("\n    /**", start + marker.length).takeIf { it > 0 }
            ?: source.length
        return source.substring(start, end)
    }

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
