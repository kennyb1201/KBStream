package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.work.WorkPolicies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a guide fetch is allowed to take.
 *
 * The bug this replaced was an unbounded one: the IPTV client shipped
 * `callTimeout(0)`, so a call had no cap at all, and the read window beside it
 * does not supply one — that only fails a read that stalls, so a server
 * trickling a byte every couple of minutes reset it forever while the import
 * never finished, the socket was never given up, and a background refresh spent
 * the whole ten-minute window WorkManager allows it on a guide that was never
 * arriving.
 *
 * Two budgets are pinned here because they belong to different layers and only
 * one of them is about the network. The transport's is a whole-call cap and has
 * to be generous: the response body is consumed by the parser, so the call is
 * open for the download AND the inserts that follow it, and cutting a healthy
 * import off mid-write would be a worse bug than the one being fixed. The
 * worker's is a policy budget on one SOURCE inside a pass, and it is the one
 * that has to fit the window the pass actually gets.
 *
 * Both relationships are asserted rather than restated: the pair only works if
 * the transport is the looser of the two and the policy budget is the tighter
 * one, and nothing about either number says so on its own.
 */
class GuideFetchBudgetContractTest {

    private val client by lazy { IptvHttpClient.create() }

    // ── the transport's whole-call cap ───────────────────────────────────

    @Test
    fun `the guide client bounds a whole call`() {
        assertEquals(
            "the client must carry the pinned cap, not its own number",
            IptvHttpClient.CALL_TIMEOUT_MS,
            client.callTimeoutMillis.toLong()
        )
        assertTrue(
            "a call timeout of zero is 'no bound at all', which is what this replaced",
            client.callTimeoutMillis > 0
        )
    }

    @Test
    fun `the stall detector keeps its own, shorter window`() {
        // The whole-call cap is not a substitute for the read timeout: one
        // bounds the call, the other notices a socket that has gone quiet
        // three minutes in. Both have to be there.
        assertEquals(
            "the read window a stalled socket trips",
            180_000,
            client.readTimeoutMillis
        )
        assertTrue(
            "the whole-call cap has to be the looser of the two, or an import " +
                "is cut off by a number meant for a socket that died",
            client.callTimeoutMillis > client.readTimeoutMillis
        )
    }

    @Test
    fun `the whole-call cap is long enough for an import, not just a download`() {
        // A healthy import on the field device is documented as a multi-minute
        // download plus a few hundred thousand inserts, against a refresh pass
        // WorkManager stops at about ten minutes. A cap below the pass window
        // would abort imports the app expects to finish.
        assertTrue(
            "an import-sized cap, not a download-sized one",
            IptvHttpClient.CALL_TIMEOUT_MS >= 10L * 60L * 1000L
        )
    }

    // ── the refresh pass's per-source budget ─────────────────────────────

    @Test
    fun `a source is abandoned by policy before the transport gives up`() {
        assertTrue(
            "the per-source budget is what has to fire first",
            WorkPolicies.GUIDE_SOURCE_BUDGET_MS < IptvHttpClient.CALL_TIMEOUT_MS
        )
        assertTrue(
            "and it has to be a real budget",
            WorkPolicies.GUIDE_SOURCE_BUDGET_MS > 0L
        )
    }

    @Test
    fun `the per-source budget fits inside the window the pass gets`() {
        // WorkManager stops a CoroutineWorker's job at around ten minutes. A
        // budget at or above that would leave the run nothing to finish with,
        // and the stop it is meant to avoid would land mid-import anyway.
        val passWindowMs = 10L * 60L * 1000L
        assertTrue(
            "one source must not be allowed to spend the whole pass",
            WorkPolicies.GUIDE_SOURCE_BUDGET_MS < passWindowMs
        )
    }

    // ── the wiring ──────────────────────────────────────────────────────

    @Test
    fun `the refresh worker arms the budget and disowns a source it abandons`() {
        // A budget nobody applies fixes nothing, and an abandoned source that
        // counted as an import would write the freshness marker for a guide the
        // device never stored — leaving the next six hours with no guide at all.
        val worker = readSource(WORKER)
        assertTrue(
            "the pass must run each source under the budget",
            worker.contains("withTimeout(WorkPolicies.GUIDE_SOURCE_BUDGET_MS)")
        )
        assertTrue(
            "only the source's own timeout may be absorbed here",
            worker.contains("catch (timeout: TimeoutCancellationException)")
        )
        assertTrue(
            "an abandoned source must not count towards a successful pass - and neither " +
                "may a source that completed without landing a guide (a refused empty " +
                "feed, a 304 over an empty one): see GuideImportOutcome.landed",
            worker.contains(".onSuccess { outcome ->") &&
                worker.contains("if (outcome != null && outcome.landed) {") &&
                worker.contains("imported++")
        )
        // And a source that DID import is marked fresh at once, not once after
        // the loop: two slow sources outlast the ~10-minute job window, and a
        // kill mid-pass used to lose the marker for a guide that WAS stored -
        // so the next run re-downloaded it.
        // The write itself lives in stampImportedSource (it also re-derives the
        // global marker from the per-source stamps), so what proves it is
        // per-source is that the LOOP calls that helper, between the loop's own
        // opening and the tail that runs once the loop is over.
        val loopAt = worker.indexOf("epgUrls.forEach { url ->")
        val stampAt = worker.indexOf("stampImportedSource(prefs, url, epgUrls)")
        val afterLoopAt = worker.indexOf("// One dead source must not fail")
        assertTrue("the per-source loop must still be findable", loopAt >= 0)
        assertTrue("the loop's tail must still be findable", afterLoopAt > loopAt)
        assertTrue(
            "a source that imported must be stamped inside the per-source loop",
            stampAt > loopAt && stampAt < afterLoopAt
        )
        assertTrue(
            "and the marker write must still be what the stamp performs",
            worker.contains("putLong(KEY_EPG_UPDATED_AT, oldest)")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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

    private companion object {
        private const val WORKER =
            "com/kennyb1201/kbstream/data/iptv/EpgRefreshWorker.kt"
    }
}
