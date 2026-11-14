package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide refresh landing its verdicts (spec 2026-11-14, F1c/F1b/F2/F3).
 *
 * The field paste carried `failing=no stall=none emptyRuns=0` and a 22-hour-old
 * import: every branch that does not reach the worker's own empty-run code left
 * no trace at all. This pins the opposite - the freshness decline, the playback
 * deferral, the per-source timeout, the thrown import, and WorkManager stopping
 * the job each record a word, the streak that turns them into `failing` has one
 * writer, and the paste can finally name the branch.
 *
 * [com.kennyb1201.kbstream.data.iptv.EpgRefreshDiagnosticsTest] proves the words
 * are right; this proves they are written.
 */
class GuideRefreshLandingContractTest {

    private val worker by lazy { source("data/iptv/EpgRefreshWorker.kt") }
    private val scheduler by lazy { source("data/iptv/EpgRefreshScheduler.kt") }
    private val app by lazy { source("MainApplication.kt") }
    private val diagnostics by lazy { source("data/reporting/Diagnostics.kt") }
    private val dao by lazy { source("data/iptv/IptvDao.kt") }
    private val repository by lazy { source("data/iptv/IptvRepository.kt") }

    @Test
    fun `every pass exit records a verdict`() {
        assertEquals(
            "fresh, defer, no-sources and the import pass: four exits, four records",
            3,
            countOf(worker, "recordPass(EpgRefreshDiagnostics.PassVerdict(")
        )
        assertTrue(worker.contains("EpgRefreshDiagnostics.Decision.FRESH"))
        assertTrue(worker.contains("EpgRefreshDiagnostics.Decision.DEFER"))
        assertTrue(worker.contains("EpgRefreshDiagnostics.Decision.NO_SOURCES"))
        assertTrue(
            "and the import pass itself, with what each source did",
            worker.contains("decision = EpgRefreshDiagnostics.Decision.IMPORT") &&
                worker.contains("sources = results.toList()")
        )
    }

    @Test
    fun `a job WorkManager stops is recorded with how far it got`() {
        assertTrue("the override exists", worker.contains("override fun onStopped()"))
        val stopped = worker.substringAfter("override fun onStopped()").substringBefore("super.onStopped()")
        assertTrue(
            "it records the stop, not silence - the branch that produced the 22-hour gap",
            stopped.contains("stopped = true") && stopped.contains("recordPass(")
        )
        assertTrue(
            "and the loop publishes its progress for it to read",
            worker.contains("stoppedSources = results.toList()")
        )
    }

    @Test
    fun `a timeout is recorded as a timeout, not as a clean run`() {
        val timeout = worker.substringAfter("} else {").substringBefore("}")
        assertTrue(
            "the null outcome (per-source budget expired) has its own token",
            worker.contains("EpgRefreshDiagnostics.SourceOutcome.TIMEOUT")
        )
        assertTrue(
            "and it is reached from the null-outcome branch, which used to be log-only",
            worker.substringAfter("importWithinBudget(repository, url)").contains("SourceOutcome.TIMEOUT")
        )
        assertTrue(timeout.isNotEmpty())
    }

    @Test
    fun `a thrown import records its class, never its message`() {
        assertTrue(
            worker.contains("EpgRefreshDiagnostics.SourceOutcome.FAILED") &&
                worker.contains("error::class.java.simpleName")
        )
        val recorder = worker.substringAfter("private fun recordPass(").substringBefore("override suspend fun doWork()")
        assertFalse(
            "nothing that is recorded may carry a URL or a message",
            recorder.contains("Redaction.url(") || recorder.contains("error.message")
        )
    }

    @Test
    fun `the failing flag has one writer and it counts every no-import pass`() {
        assertEquals(
            "one writer only: the old empty-source branch must not fight it",
            1,
            countOf(worker, "putBoolean(KEY_EPG_REFRESH_FAILING")
        )
        val recorder = worker.substringAfter("private fun recordPass(").substringBefore("override suspend fun doWork()")
        assertTrue(recorder.contains("EpgRefreshDiagnostics.failingAfter("))
        assertTrue(recorder.contains("EpgRefreshDiagnostics.KEY_ABORT_STREAK"))
    }

    @Test
    fun `a periodic request always exists - it is scheduled at launch and replaced, not kept`() {
        assertTrue(
            "MainApplication schedules it every start, so a cancelled request comes back",
            app.contains("EpgRefreshScheduler") && app.contains(".schedule(applicationContext)")
        )
        assertTrue(
            "UPDATE rather than KEEP: KEEP would preserve a dead request's old interval forever",
            scheduler.contains("ExistingPeriodicWorkPolicy.UPDATE")
        )
    }

    @Test
    fun `the paste carries the verdict, the streak and the countdown`() {
        assertTrue(diagnostics.contains("append(\" outcome=\").append(outcome ?: \"none\")"))
        assertTrue(diagnostics.contains("append(\" outcomeAge=\")"))
        assertTrue(diagnostics.contains("EpgRefreshDiagnostics.KEY_ABORT_STREAK"))
        assertTrue(
            "next= goes through the tested countdown, so Long.MAX_VALUE prints none",
            diagnostics.contains("EpgRefreshDiagnostics.countdown(nowMs, atMs)")
        )
    }

    @Test
    fun `the grid's row yield is counted and printed`() {
        assertTrue(
            "distinct channels with a program in the window: the yield, not the program total",
            dao.contains("COUNT(DISTINCT channelId) FROM epg_programs") &&
                dao.contains("countChannelsWithProgramsInWindow")
        )
        assertTrue(repository.contains("channelsWithPrograms = dao.countChannelsWithProgramsInWindow("))
        assertTrue(diagnostics.contains("append(\"rowsWithPrograms=\${row.channelsWithPrograms}/\${row.channels} \")"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "$prefix/src/main/java/com/kennyb1201/kbstream/$relativePath")
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
