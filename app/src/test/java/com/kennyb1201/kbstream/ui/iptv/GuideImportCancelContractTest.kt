package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One guide import at a time, stoppable from the button, and quiet after a loud
 * failure.
 *
 * Both halves of the field report live here. The button used to cancel a handle
 * only IT owned (`importJob`) while the entry auto-refresh import ran on
 * `refreshJob`, so a stuck auto-import could not be cancelled from the button at
 * all - the manual import joined it through the repository's coalescing and sat
 * at the same 0 programs while the toast said it had started again. And the
 * wedged run never wrote the freshness marker, so every later entry started
 * another import: "the guide seems to reimport a lot".
 *
 * There is no Compose or coroutine harness in this module to drive a ViewModel
 * through that, so this pins the wiring the way the rest of the guide's contracts
 * do - and it is the wiring that is easy to undo, since the old shape was two
 * lines that looked reasonable.
 */
class GuideImportCancelContractTest {

    private val vm by lazy { read(IPTV_VM) }
    private val repository by lazy { read(IPTV_REPOSITORY) }
    private val http by lazy { read(IPTV_HTTP) }

    // ── one handle, cancel it from the button ─────────────────────────────

    @Test
    fun `there is exactly one guide import handle`() {
        assertFalse(
            "a second handle is how the button came to cancel an import the " +
                "auto-refresh was not using",
            Regex("\\bimportJob\\b").containsMatchIn(vm)
        )
        assertTrue(
            "the button, the entry refresh and the profile switch cancel the " +
                "same handle",
            vm.contains("guideImportJob")
        )
    }

    @Test
    fun `the button routes through the shared launcher`() {
        val button = body("fun importGuide() {")
        assertTrue(
            "IMPORT EPG must go through the one launcher (forcing a fresh " +
                "fetch is the button's own business - see GuideImportRecoveryTest)",
            button.contains("launchGuideImport(forceFresh = true)")
        )
        assertTrue(
            "and it must NOT start an import on a handle of its own",
            !button.contains("guideImportJob =")
        )
        assertTrue(
            "the button always forces a fresh attempt, so it must never consult " +
                "the stall cooldown",
            !button.contains("CoolingDown") && !button.contains("guideStalledRecently")
        )
    }

    @Test
    fun `the launcher stops the running pass and waits for it`() {
        val launcher = body("private fun launchGuideImport(forceFresh: Boolean = false) {")
        assertTrue(
            "whichever path started the running import, this is what stops it",
            launcher.contains("previous?.cancel()")
        )
        assertTrue(
            "the cancelled pass closes the shared progress state and drops its " +
                "repository entry in its finally, so it has to be gone before the " +
                "replacement opens its own",
            launcher.contains("previous?.join()")
        )
        assertTrue(
            "and the replacement is the one import entry point",
            launcher.contains("importGuideInternal(")
        )
    }

    // ── the entry refresh shares it, and keeps its playlist half ───────────

    @Test
    fun `the entry refresh starts the guide import on the shared handle`() {
        val entry = body("private fun refreshIfNeeded() {")
        assertTrue(
            "the entry import has to be cancellable from the button too",
            entry.contains("launchGuideImport()")
        )
        assertTrue(
            "it must not run the import inline on refreshJob, which is the " +
                "handle the button never had",
            !entry.contains("importGuideInternal(")
        )
        assertTrue(
            "the playlist half keeps its own job: cancelling the guide import " +
                "must not cancel a playlist refresh",
            entry.contains("refreshPlaylistInBackground()")
        )
    }

    @Test
    fun `a staleness tick never restarts an import already in flight`() {
        val entry = body("private fun refreshIfNeeded() {")
        assertTrue(
            "the guide is stale precisely BECAUSE the import has not finished",
            entry.contains("guideImportJob?.isActive")
        )
    }

    // ── the entry cooldown ────────────────────────────────────────────────

    @Test
    fun `entry skips the automatic import while a stall is cooling down`() {
        val entry = body("private fun refreshIfNeeded() {")
        assertTrue(
            "the cooldown has to gate the automatic import",
            entry.contains("guideStalledRecently()")
        )
        assertTrue(
            "and the gated import must be skipped, not started anyway",
            entry.contains("canImportGuide")
        )
        assertTrue(
            "skipping the import must leave the failure banner alone - it is the " +
                "one thing the viewer can act on",
            !entry.contains("_guideError")
        )
    }

    @Test
    fun `the cooldown reads the stall marker from this profile's prefs`() {
        assertTrue(
            "the marker is profile-scoped, in the same store as the freshness " +
                "marker it stands in for",
            vm.contains("const val KEY_EPG_LAST_STALL_AT = \"epg_last_stall_at\"")
        )
        assertTrue(
            "and it is read through the shared cooldown rule, not an inline sum",
            vm.contains("prefs.getLong(KEY_EPG_LAST_STALL_AT, 0L)") &&
                vm.contains("epgEntryImportCoolingDown(")
        )
    }

    // ── the watchdog in the import pass ───────────────────────────────────

    @Test
    fun `each source import is watched, with a fresh connection on the retry`() {
        val pass = body("private suspend fun importGuideInternal(")
        assertTrue(
            "a wedged source has to be noticed inside the pass, not by the " +
                "twenty-minute call timeout",
            pass.contains("runWithStallRetry(url) {")
        )
        assertTrue(
            "the watchdog must know which source it is watching, or a queued " +
                "import reads the reader's counters as a stall",
            pass.contains("runWithStallRetry(url)")
        )
        assertTrue(
            "the retry must open its own connection: joining the attempt the " +
                "watchdog just aborted would await the corpse",
            pass.replace(Regex("\\s+"), " ").contains(
                "importGuideSource( url = url, freshConnection = attempt > 0,"
            )
        )
        assertTrue(
            "a loud stall is recorded so the entry stops hammering this provider",
            pass.contains("markUpdated(KEY_EPG_LAST_STALL_AT)")
        )
        assertTrue(
            "the stall must reach the banner, not be swallowed as a cancel",
            pass.contains("reportFailure(_guideError") ||
                pass.contains("_guideError")
        )
        assertTrue(
            "and the import has to finish with the flag down whatever happened",
            pass.contains("_isImportingGuide.value = false")
        )
    }

    @Test
    fun `the retry is the only caller that does not join`() {
        val source = body("private suspend fun importGuideSource(")
        assertTrue(
            "the escaping parameter is threaded through to the repository",
            source.contains("joinActiveImport = !freshConnection")
        )
        assertTrue(
            "first attempts still coalesce with a running import (the worker's)",
            vm.contains("freshConnection: Boolean = false")
        )
    }

    @Test
    fun `the repository keeps coalescing, but can be told not to`() {
        assertTrue(
            "the default stays 'join': the worker and the screen still share one " +
                "download",
            repository.contains("joinActiveImport: Boolean = true")
        )
        assertTrue(
            "and the escape hatch is a branch, not a second import path",
            repository.contains("if (joinActiveImport) {")
        )
    }

    // ── the abort is a real one ───────────────────────────────────────────

    @Test
    fun `a cancelled fetch closes its connection`() {
        assertTrue(
            "the blocking read has to be wrapped in the cancellation guard",
            http.contains("withCancellableCall(call)")
        )
        assertTrue(
            "which is what closes the socket under a blocked read",
            http.contains("call.cancel()")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun body(signature: String): String {
        val start = vm.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val brace = vm.indexOf('{', start)
        assertTrue("no body for member: $signature", brace >= 0)
        val rest = vm.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        const val IPTV_VM = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
        const val IPTV_REPOSITORY = "com/kennyb1201/kbstream/data/iptv/IptvRepository.kt"
        const val IPTV_HTTP = "com/kennyb1201/kbstream/data/iptv/IptvHttpClient.kt"
    }
}
