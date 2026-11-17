package com.kennyb1201.kbstream.data.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide refresh on playback end (spec 2026-11-16).
 *
 * The background refresh never runs while something plays - the guide rebuild
 * under a starting player cost 750 ms GC pauses and a 6.9 s rebuffer - so a
 * television that plays most of the time it is on starves the 6h tick. The field
 * paste was twelve `outcome=defer (player)` attempts and a guide 1d 1h old, and
 * the only path left was opening the Guide, whose entry policy imports when the
 * data is stale.
 *
 * The fix is a third path: a title ends, the player closes, and a stale guide
 * gets a one-time refresh then. These tests pin the half a JVM can decide - the
 * staleness/cooldown rule and the verdict's own grammar - and pin the wiring from
 * the source, the way the other contract tests do it.
 */
class GuideCatchUpContractTest {

    private val now = 1_700_000_000_000L
    private val freshGuideEnd = now + EpgWindow.LEAD_FOR_TESTS_MS * 2

    // ── F2: when a player close is worth a refresh ─────────────────────

    @Test
    fun `a fresh guide enqueues nothing`() {
        // S2: an import is a multi-minute download plus a few hundred thousand
        // inserts. A close with a healthy guide must cost nothing.
        assertFalse(
            EpgCatchUpRules.shouldRun(
                urgency = GuideRefreshPolicy.urgency(
                    endsAtMs = freshGuideEnd,
                    nowMs = now,
                    ageStale = false
                ),
                nowMs = now,
                lastAttemptMs = 0L
            )
        )
    }

    @Test
    fun `a stale guide is worth one`() {
        // The window is closing (DUE) and the marker is old (DUE by age): both
        // are the entry policy's own answers, so the catch-up cannot disagree
        // with the screen about what stale means.
        val due = GuideRefreshPolicy.urgency(
            endsAtMs = now + EpgWindow.READ_FUTURE_MS,
            nowMs = now,
            ageStale = false
        )
        assertEquals(GuideRefreshPolicy.Urgency.DUE, due)
        assertTrue(EpgCatchUpRules.shouldRun(due, now, lastAttemptMs = 0L))

        val urgent = GuideRefreshPolicy.urgency(
            endsAtMs = now + 60_000L,
            nowMs = now,
            ageStale = false
        )
        assertEquals(GuideRefreshPolicy.Urgency.URGENT, urgent)
        assertTrue(EpgCatchUpRules.shouldRun(urgent, now, lastAttemptMs = 0L))
    }

    @Test
    fun `an old marker alone is enough`() {
        // The 12h age rule: a guide whose window still reaches far ahead but
        // whose marker stopped moving is the partial-pass case.
        assertTrue(GuideRefreshPolicy.ageStale(now, now - GuideRefreshPolicy.AGE_STALE_MS - 1))
        assertFalse(GuideRefreshPolicy.ageStale(now, now - 60_000L))
        assertTrue("nothing was ever imported", GuideRefreshPolicy.ageStale(now, 0L))
        assertTrue(
            "the guide on disk is not the one the marker describes",
            GuideRefreshPolicy.ageStale(now, now - 60_000L, fileMoved = true)
        )
    }

    @Test
    fun `a rapid open and close does not enqueue repeatedly`() {
        // F2's dedupe, against the attempt stamp both other paths already write.
        val stale = GuideRefreshPolicy.urgency(now + 60_000L, now, ageStale = true)
        assertFalse(
            "a pass started five minutes ago is still too soon",
            EpgCatchUpRules.shouldRun(stale, now, lastAttemptMs = now - 5 * 60_000L)
        )
        assertTrue(
            "past the cooldown it is worth another try",
            EpgCatchUpRules.shouldRun(
                stale,
                now,
                lastAttemptMs = now - EpgCatchUpRules.COOLDOWN_MS - 1
            )
        )
        assertFalse(
            "an attempt stamped in the future is a clock put back, not an overdue guide",
            EpgCatchUpRules.shouldRun(stale, now, lastAttemptMs = now + 60 * 60_000L)
        )
        // The cooldown is the app's existing one, not a new number - and the
        // value behind it is still the half hour the entry path spaces its own
        // retries by (read from the source: that file needs coroutines, which
        // this harness does not carry).
        assertEquals(EPG_ENTRY_RETRY_COOLDOWN_MS, EpgCatchUpRules.COOLDOWN_MS)
        assertTrue(
            "the shared cooldown is a half hour",
            source("com/kennyb1201/kbstream/data/iptv/EpgImportWatchdog.kt")
                .contains("const val EPG_ENTRY_RETRY_COOLDOWN_MS = 30L * 60L * 1000L")
        )
    }

    // ── F4: the diagnostics line names the catch-up ────────────────────

    @Test
    fun `a catch-up pass says so, and the old grammar is untouched`() {
        val import = EpgRefreshDiagnostics.PassVerdict(
            decision = EpgRefreshDiagnostics.Decision.IMPORT,
            sources = listOf(
                EpgRefreshDiagnostics.SourceResult(1, EpgRefreshDiagnostics.SourceOutcome.LANDED)
            ),
            imported = 1
        )
        assertEquals("import 1:landed, landed=1", EpgRefreshDiagnostics.verdictLine(import))
        assertEquals(
            "catch-up import 1:landed, landed=1",
            EpgRefreshDiagnostics.verdictLine(import.copy(catchUp = true))
        )
        assertEquals(
            "defer (player)",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(EpgRefreshDiagnostics.Decision.DEFER)
            )
        )
        assertEquals(
            "catch-up defer (player)",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.DEFER,
                    catchUp = true
                )
            )
        )
        assertEquals(
            "catch-up fresh — skipped",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.FRESH,
                    catchUp = true
                )
            )
        )
        assertEquals(
            "catch-up stopped mid-import",
            EpgRefreshDiagnostics.verdictLine(
                EpgRefreshDiagnostics.PassVerdict(
                    EpgRefreshDiagnostics.Decision.IMPORT,
                    stopped = true,
                    catchUp = true
                )
            )
        )
    }

    @Test
    fun `a catch-up pass still counts toward the failing streak like any other`() {
        // F3: the same worker, so its own accounting must not be special-cased.
        val empty = EpgRefreshDiagnostics.PassVerdict(
            decision = EpgRefreshDiagnostics.Decision.IMPORT,
            imported = 0,
            catchUp = true
        )
        assertEquals(1 to false, EpgRefreshDiagnostics.failingAfter(empty, 0))
        assertEquals(
            EpgRefreshDiagnostics.FAILING_STREAK to true,
            EpgRefreshDiagnostics.failingAfter(empty, EpgRefreshDiagnostics.FAILING_STREAK - 1)
        )
    }

    // ── the wiring ─────────────────────────────────────────────────────

    @Test
    fun `the catch-up is its own one-time work, and the periodic job is untouched`() {
        val scheduler = source("com/kennyb1201/kbstream/data/iptv/EpgRefreshScheduler.kt")
        assertTrue(
            "its own unique name - the periodic name would replace the cadence",
            scheduler.contains("CATCH_UP_WORK_NAME = \"iptv_epg_refresh_catch_up\"")
        )
        assertFalse(
            "and never the periodic name",
            scheduler.contains("CATCH_UP_WORK_NAME = WORK_NAME")
        )
        val catchUp = scheduler.substringAfter("fun enqueueCatchUp(")
            .substringBefore("\n    }\n")
        assertTrue(catchUp.contains("OneTimeWorkRequestBuilder<EpgRefreshWorker>()"))
        assertTrue("one pending catch-up is enough", catchUp.contains("ExistingWorkPolicy.KEEP"))
        assertTrue("the pass has to be able to say it was one", catchUp.contains("INPUT_CATCH_UP"))
        assertTrue("same constraints as the periodic run", catchUp.contains("guideConstraints()"))

        // S4: the 6h schedule is what it was.
        val periodic = scheduler.substringAfter("fun schedule(").substringBefore("\n    }\n")
        assertTrue(periodic.contains("PeriodicWorkRequestBuilder<EpgRefreshWorker>("))
        assertTrue(periodic.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        assertTrue(periodic.contains("WORK_NAME"))
    }

    @Test
    fun `the trigger is the fullscreen player closing, not any gate toggle`() {
        val gate = source("com/kennyb1201/kbstream/data/iptv/EpgWriteGate.kt")
        val player = gate.substringAfter("fun setPlayerActive(").substringBefore("\n    }\n")
        assertTrue("the close notifies", player.contains("notifyIdle()"))
        val inline = gate.substringAfter("fun setInlinePlaybackActive(")
            .substringBefore("\n    }\n")
        assertFalse(
            "a hero trailer stopping is not a playback end - it rotates constantly",
            inline.contains("notifyIdle")
        )
        assertTrue(
            "and the notification only happens on the edge",
            gate.contains("if (becameIdle) notifyIdle()")
        )
    }

    @Test
    fun `the catch-up reads the same rule and enqueues the same worker`() {
        val catchUp = source("com/kennyb1201/kbstream/data/iptv/EpgRefreshCatchUp.kt")
        assertTrue(
            "the entry policy's own rule, not a second idea of stale",
            catchUp.contains("GuideRefreshPolicy.urgency(")
        )
        assertTrue(catchUp.contains("GuideRefreshPolicy.ageStale("))
        assertTrue(catchUp.contains("EpgCatchUpRules.shouldRun("))
        assertTrue(
            "the same worker path, and no import of its own",
            catchUp.contains("EpgRefreshScheduler.enqueueCatchUp(")
        )
        assertTrue(
            "the same two stamps the worker and the entry check read",
            catchUp.contains("KEY_EPG_UPDATED_AT") && catchUp.contains("KEY_EPG_ATTEMPT_AT")
        )
        assertTrue(
            "armed on the gate, so both engines are covered",
            catchUp.contains("EpgWriteGate.addIdleListener")
        )

        val worker = source("com/kennyb1201/kbstream/data/iptv/EpgRefreshWorker.kt")
        assertTrue(
            "the worker reads the flag off its input data",
            worker.contains("inputData.getBoolean(EpgRefreshScheduler.INPUT_CATCH_UP, false)")
        )
        assertEquals(
            "and every recorded verdict carries it",
            5,
            Regex("catchUp = catchUp").findAll(worker).count()
        )
    }

    @Test
    fun `it is armed at startup, once per process`() {
        val app = source("com/kennyb1201/kbstream/MainApplication.kt")
        assertTrue(
            "a process-wide listener belongs in the process, not in an activity",
            app.contains("EpgRefreshCatchUp")
        )
        val catchUp = source("com/kennyb1201/kbstream/data/iptv/EpgRefreshCatchUp.kt")
        assertTrue(
            "install is idempotent, so a second call cannot double-arm it",
            catchUp.contains("compareAndSet(false, true)")
        )
        assertTrue(
            "and the decision runs off the player's teardown path",
            catchUp.contains("BackgroundWork.launch")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
}

/** The lead the window test uses, in terms of the policy's own constant. */
private val EpgWindow.LEAD_FOR_TESTS_MS: Long
    get() = GuideRefreshPolicy.LEAD_MS
