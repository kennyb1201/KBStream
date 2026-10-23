package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.reporting.TorBoxTrace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why a Cached chip is missing, answerable from a capture.
 *
 * Both TorBox features fail soft: a rejected key, an unreachable API, or a
 * source list whose add-ons already resolved to CDN links all come out the same
 * way - no chip, no library rows, no message. The viewer reported exactly that
 * ("I never see a green cached chip on anything"), and nothing on the device
 * could tell the three cases apart: logcat is not reachable from a TV and the
 * release build carries no debug log.
 *
 * So the check now records what it saw, Settings can probe the key, and this
 * pins the seams a unit test cannot drive: the counts reaching [TorBoxTrace],
 * the trace reaching the diagnostics report, the picker reporting a list with no
 * hash in it at all, and both settings panes offering the probe.
 */
class TorBoxCachedChipDiagnosticsContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CLIENT = "com/kennyb1201/kbstream/data/debrid/TorBoxClient.kt"
        const val TRACE = "com/kennyb1201/kbstream/data/reporting/TorBoxTrace.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }

    // ── what the check records ───────────────────────────────────────────────

    @Test
    fun `a list with no infohash says so and never calls the API`() {
        val check = source(CLIENT)
            .substringAfter("suspend fun checkCached(")
            .substringBefore("private fun query(")
        val noHash = check.substringAfter("if (wanted.isEmpty()) {")

        assertTrue(
            "the chip-less answer must be named in the trace - it is the case " +
                "the viewer actually hit",
            noHash.contains("none carried an infohash")
        )
        assertTrue(
            "and it must still return before any request is made",
            noHash.indexOf("TorBoxTrace.note(") >= 0 &&
                noHash.indexOf("return@withContext emptySet()") >= 0 &&
                noHash.indexOf("TorBoxTrace.note(") < noHash.indexOf("return@withContext emptySet()")
        )
    }

    @Test
    fun `a completed check records sources, hashes and the cached count`() {
        val check = source(CLIENT)
            .substringAfter("suspend fun checkCached(")
            .substringBefore("private fun query(")

        assertTrue(
            "the line has to carry all three numbers, or a capture cannot say " +
                "whether nothing was cached or nothing was asked about",
            check.contains("with a hash · ") && check.contains("cachedNow.size")
        )
        assertTrue(
            "and it is written after the answers come back, not before",
            check.indexOf("cachedNow") > check.indexOf("chunked(BATCH_SIZE)")
        )
        assertTrue(
            "a failed batch is traced with the HTTP class, so a 401 is " +
                "distinguishable from a genuinely uncached title",
            check.contains("checkcached failed: ")
        )
    }

    @Test
    fun `a keyless account is not reported as a failed check`() {
        val check = source(CLIENT)
            .substringAfter("suspend fun checkCached(")
            .substringBefore("private fun query(")
        assertTrue(
            "no key is its own line - the check never ran, which is not the " +
                "same finding as a rejected key",
            check.contains("no key, nothing checked")
        )
    }

    // ── the probe Settings offers ────────────────────────────────────────────

    @Test
    fun `the key probe names a rejected key and never puts it in a URL`() {
        val verify = source(CLIENT).substringAfter("suspend fun verifyKey(")

        assertTrue(
            "a 401/403 is the rejected-key answer",
            verify.contains("response.code == 401 || response.code == 403")
        )
        assertTrue(
            "it answers with what the account holds, so the library half is " +
                "covered by the same press",
            verify.contains("in your TorBox cloud")
        )
        assertTrue(
            "the key travels in the Authorization header only",
            verify.contains("\"Authorization\", \"Bearer ")
        )
        assertFalse(
            "a credential must never be appended to the query string",
            verify.contains("apikey=")
        )
    }

    @Test
    fun `both settings panes offer the probe`() {
        assertTrue(
            "the TV pane needs the VERIFY action beside the key",
            source(SETTINGS).contains("TorBoxClient.verifyKey(context)")
        )
        assertTrue(
            "the phone pane draws its status through the shared card's verify slot",
            source(MOBILE_SETTINGS).contains("verify = { TorBoxClient.verifyKey(context) }")
        )
        assertTrue(
            "and a successful probe reads as a connection, not as a saved key",
            source(SETTINGS).contains("Connected — ") &&
                source(MOBILE_SETTINGS).contains("savedHint = \"Connected —\"")
        )
    }

    // ── where it surfaces ────────────────────────────────────────────────────

    @Test
    fun `the trace is printed in the diagnostics report`() {
        assertTrue(
            "a soft failure with no report line is still invisible",
            source(DIAGNOSTICS).contains("TorBoxTrace.lines().forEach")
        )
        assertTrue(
            "and it is prefixed like the other traces, so the block is greppable",
            source(DIAGNOSTICS).contains("\"torbox: \$it\"")
        )
    }

    @Test
    fun `a picker list with no hash at all still leaves a line`() {
        // The client's own trace cannot run in this case (there is nothing to
        // check), so the picker has to be the one that says it - otherwise the
        // single most likely cause of "never a chip" is the one case with
        // nothing in the report.
        // The window has to START at the guard, not after it: taking the text
        // after the `if` line and then asserting the `if` is in it can never
        // pass, and the guard is exactly what this is about.
        val viewModel = source(VIEW_MODEL)
        val noteAt = viewModel.indexOf("none carried an infohash")
        assertTrue("the no-hash line is gone from the picker", noteAt > 0)
        val guardAt = viewModel.lastIndexOf(
            "if (hasDebridServiceConfigured()) {",
            noteAt
        )
        assertTrue("the no-hash line is not inside the key guard", guardAt > 0)
        val guard = viewModel.substring(guardAt, minOf(noteAt + 120, viewModel.length))

        assertTrue(
            "the no-hash case is recorded",
            guard.contains("none carried an infohash")
        )
        assertTrue(
            "and only for an account that has a key - a keyless install has " +
                "nothing to explain",
            guard.contains("hasDebridServiceConfigured()")
        )
        assertTrue(
            "the normal case still asks",
            guard.contains("requestCachedBadges(generation, badgeHashes)")
        )
    }

    // ── the ring itself ─────────────────────────────────────────────────────

    @Test
    fun `the trace is a bounded ring that ignores blank lines`() {
        TorBoxTrace.reset()
        TorBoxTrace.note("   ")
        assertEquals(emptyList<String>(), TorBoxTrace.lines())

        repeat(20) { index -> TorBoxTrace.note("line $index") }
        val kept = TorBoxTrace.lines()
        assertEquals("the ring must not grow without bound", 8, kept.size)
        assertEquals("and it keeps the newest", "line 19", kept.last())
        TorBoxTrace.reset()
    }

    @Test
    fun `the trace lives where the report can read it`() {
        val trace = source(TRACE)
        assertTrue(trace.contains("internal object TorBoxTrace"))
        assertTrue(trace.contains("private const val MAX_LINES = "))
        assertTrue(trace.contains("fun note(") && trace.contains("fun lines()"))
        assertFalse(
            "nothing from the account may be recorded here - counts only",
            trace.contains("apiKey") || trace.contains("infoHash")
        )
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
