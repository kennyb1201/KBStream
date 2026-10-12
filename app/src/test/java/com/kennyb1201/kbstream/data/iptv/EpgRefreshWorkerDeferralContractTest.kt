package com.kennyb1201.kbstream.data.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the periodic guide refresh's two pre-flight gates are wired;
 * [com.kennyb1201.kbstream.work.WorkPolicies.epgRefreshDecision] holds the
 * decisions themselves and is unit tested directly.
 *
 * Source-level on purpose, like the other worker contracts: a `CoroutineWorker`
 * needs WorkManager and a device to run at all, so nothing else can see that
 * the gate is still inside `doWork()` and still answers `retry` rather than
 * `failure`. Both losses are silent on a TV. Drop the gate and the import keeps
 * competing with the decoder underneath a film, which is the lag the gate
 * exists to remove; answer `failure` instead of `retry` and the periodic work
 * is cancelled outright, so a long film costs the install its guide refreshes
 * with nothing left to bring the guide back.
 */
class EpgRefreshWorkerDeferralContractTest {

    /**
     * `doWork`'s gate, from the decision it makes up to the import it guards.
     * Bounded on the import side so the worker's later `Result.failure()` —
     * the bounded empty-runs give-up, which has nothing to do with this gate —
     * is not read as part of it.
     */
    private val gate: String by lazy {
        worker.readText()
            .substringAfter("suspend fun doWork()")
            .substringBefore("// The shared instance")
    }

    /**
     * [gate] with its comments stripped, so prose *about* an outcome cannot be
     * read as the outcome. The gate's own comment names `Result.failure()` to
     * say why it is not used, which is exactly the false positive this avoids.
     */
    private val gateCode: String by lazy {
        gate.lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `the worker asks the shared policy instead of keeping its own copy of the rules`() {
        assertTrue(
            "doWork must decide through WorkPolicies.epgRefreshDecision, so the age " +
                "floor and the order of the two gates have one definition",
            gateCode.contains("WorkPolicies.epgRefreshDecision(")
        )
        assertTrue(
            "the freshness gate reads the marker the guide screen's own staleness check " +
                "reads and the in-app import writes, so the two agree on what fresh means",
            gateCode.contains("prefs.getLong(KEY_EPG_UPDATED_AT, 0L)")
        )
        assertTrue(
            "and the playback gate reads EpgWriteGate, which both player activities " +
                "already set, rather than tracking playback a second time",
            gateCode.contains("EpgWriteGate.isPlayerActive")
        )
    }

    @Test
    fun `a deferral retries and never fails`() {
        assertTrue("a deferred run must come back later", gateCode.contains("Result.retry()"))
        assertTrue("a fresh guide is a finished attempt", gateCode.contains("Result.success()"))
        assertFalse(
            "no statement in this gate may fail the periodic work - failure cancels it, " +
                "so a film would take the guide's refreshes with it",
            gateCode.contains("Result.failure")
        )
    }

    @Test
    fun `both outcomes name themselves in the two lines the field run greps`() {
        // The verification steps read these exact lines, so the wording is part
        // of the contract rather than an implementation detail.
        assertTrue(gateCode.contains("\"EPG refresh skipped: data fresh (age="))
        assertTrue(gateCode.contains("\"EPG refresh deferred: playback active\""))
    }

    private companion object {

        val worker: File by lazy {
            File(mainSourceRoot(), "com/kennyb1201/kbstream/data/iptv/EpgRefreshWorker.kt")
        }

        /** Same walk the other source contracts use: works from the module or the root. */
        fun mainSourceRoot(): File {
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
}
