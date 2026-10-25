package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist Setup is never a dead end during a guide import.
 *
 * The field case (TV guest, 2026-10-25, build 0.9.64): the FAST migration wrote
 * its guide lines, the freshly landed freshness trigger started a six-source
 * import, and Playlist Setup showed the button saying IMPORTING… with a bare
 * "EPG importing" line beside it - no source number, no megabytes, no program
 * count, no clock. LOAD refused for the whole pass, and with no loaded playlist
 * there was no Guide grid either, so the screen that DOES show the live progress
 * line was unreachable from the only screen the viewer could see. Force-stop was
 * the only way out. Diagnostics taken during the incident proved the import was
 * healthy and all six sources were already stored, which is the point: a healthy
 * long import and a wedged one were indistinguishable from setup.
 *
 * Three things made that possible, and each is pinned below because each is easy
 * to reintroduce:
 *  - the setup panel HID the progress line while a pass was running
 *    (`!isImportingGuide && ...`), so the importing state had no numbers;
 *  - "an import is running" lived in the ViewModel while "how far along it is"
 *    lived in [com.kennyb1201.kbstream.data.iptv.GuideImportProgress], and the
 *    two could disagree - the setup line then fell back to the bare branch;
 *  - LOAD returned early with an error banner for the duration.
 *
 * There is no Compose or coroutine harness in this module to drive a ViewModel,
 * so this pins the wiring the way the rest of the guide contracts do.
 */
class GuideSetupImportTrapContractTest {

    private val vm by lazy { read(IPTV_VM) }
    private val tvScreen by lazy { read(IPTV_SCREEN) }
    private val phoneScreen by lazy { read(IPTV_SCREEN_MOBILE) }
    private val progress by lazy { read(PROGRESS) }
    private val rules by lazy { read(RULES) }

    // ── the live line, while the pass runs ────────────────────────────────

    @Test
    fun `the setup panel shows the numbers while a pass runs`() {
        assertFalse(
            "the gate that hid the one screen's progress line for a whole pass " +
                "(the field case: 'IMPORTING…' with no numbers anywhere)",
            tvScreen.contains("!isImportingGuide && guideImportLabel.isNotBlank()")
        )
        assertTrue(
            "setup renders the SAME line the guide does - source x/6, MB, " +
                "programs, elapsed - and says it is an import",
            tvScreen.contains("Importing guide · \$guideImportLabel")
        )
        assertTrue(
            "fed by the shared pass object, not by a second source of its own",
            tvScreen.contains("GuideImportProgress.label(") &&
                tvScreen.contains("state = guideImport")
        )
        assertTrue(
            "the phone setup renders it while importing too",
            phoneScreen.contains("Importing guide · \$guideImportLabel")
        )
    }

    @Test
    fun `the diagnostics line can never read bare`() {
        assertFalse(
            "a bare 'EPG importing' is what the field saw - it says a pass exists " +
                "and nothing about where it is",
            rules.contains("add(\"EPG importing\")")
        )
        assertTrue(
            "the fallback carries state instead",
            rules.contains("add(\"EPG importing: starting…\")")
        )
    }

    // ── one source of truth ───────────────────────────────────────────────

    @Test
    fun `running and progress are one fact`() {
        assertTrue(
            "the shared object answers 'is a pass open' itself",
            progress.contains("val isRunning: Boolean")
        )
        assertTrue(
            "and the ViewModel's flag is its mirror, seeded from it rather than " +
                "kept as a second fact",
            vm.contains("MutableStateFlow(GuideImportProgress.isRunning)")
        )
        assertTrue(
            "a pass another instance started still reaches this screen",
            vm.contains("private fun observeGuideImportPass()")
        )
        val pass = body("private suspend fun importGuideInternal(")
        assertTrue(
            "the pass opens the line and mirrors it",
            pass.contains("GuideImportProgress.begin(sourceCount = urls.size)") &&
                pass.contains("_isImportingGuide.value = GuideImportProgress.isRunning")
        )
        assertFalse(
            "and no path may set a blanket false: that is how a stopped pass " +
                "blanked a newer one",
            pass.contains("_isImportingGuide.value = false")
        )
        assertTrue(
            "the pass speaks only for itself when it closes",
            pass.contains("GuideImportProgress.finish(passId, outcome, guideEndsAtMs)")
        )
    }

    // ── LOAD during an import ─────────────────────────────────────────────

    @Test
    fun `load is never refused because an import is running`() {
        assertFalse(
            "the 'please wait before loading the playlist' refusal is the dead " +
                "end itself - it must not be raised on any load path",
            vm.contains(
                "EPG import is in progress. Please wait before loading the playlist."
            )
        )
        assertFalse(
            "load() goes straight to the pipeline",
            body("fun load() {").contains("_isImportingGuide")
        )
        assertFalse(
            "and so does the Free TV opt-in's load",
            body("private fun loadWithGuideRefresh() {").contains("_isImportingGuide")
        )
        assertFalse(
            "the pipeline itself does not consult the import either",
            body("private suspend fun loadLineup(").contains("_isImportingGuide")
        )
    }

    @Test
    fun `the load reads the live guide and the pass refreshes the lineup`() {
        val lineup = body("private suspend fun loadLineup(")
        assertTrue(
            "a load during a pass still runs the ordinary entry refresh decision",
            lineup.contains("refreshIfNeeded(forceGuide = forceGuideRefresh)")
        )
        val pass = body("private suspend fun importGuideInternal(")
        assertTrue(
            "and the pass that lands re-issues the guide window on the existing " +
                "tick - no second refresh path for 'LOAD was pressed mid-import'",
            pass.contains("_guideRefreshTick.value += 1") &&
                pass.contains("clearGuideMemory(") &&
                pass.contains("requestInitialGuideWindow()")
        )
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    @Test
    fun `setup offers a real cancel, only while a pass runs`() {
        assertTrue(
            "the TV action row grows a cancel exactly while a pass is running",
            tvScreen.contains("if (isImportingGuide) {") &&
                tvScreen.contains("\"CANCEL IMPORT\"")
        )
        assertTrue(
            "wired to the ViewModel's cancel, not to a re-press of IMPORT",
            tvScreen.contains("onCancelGuideImport = { viewModel.cancelGuideImport() }")
        )
        assertTrue(
            "and the phone form carries the same action",
            phoneScreen.contains("\"CANCEL IMPORT\"") &&
                phoneScreen.contains("onCancelGuideImport = { viewModel.cancelGuideImport() }")
        )
    }

    @Test
    fun `cancel stops the pass and never restarts it`() {
        val cancel = body("fun cancelGuideImport() {")
        assertTrue(
            "it stops the one handle every start path uses, so an entry- or " +
                "migration-triggered pass is cancellable too",
            cancel.contains("guideImportJob") && cancel.contains("job.cancel()")
        )
        assertTrue(
            "and says so on the status line, on the viewer's behalf",
            cancel.contains("GuideImportProgress.finish(passId, GuideImportOutcome.Cancelled)")
        )
        assertFalse(
            "a cancel is not a re-press: it must not start another pass",
            cancel.contains("launchGuideImport(")
        )
        assertFalse(
            "and it must not drop the handle a following press has to join",
            cancel.contains("guideImportJob = null")
        )
    }

    @Test
    fun `a cancelled pass is not a failure`() {
        assertTrue(
            "its own outcome class",
            progress.contains("data object Cancelled : GuideImportOutcome")
        )
        assertTrue(
            "with the viewer's wording",
            progress.contains("\"Import cancelled — your guide is unchanged\"")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the class member starting at [signature], up to its next member. */
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
        const val IPTV_SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val IPTV_SCREEN_MOBILE = "com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt"
        const val PROGRESS = "com/kennyb1201/kbstream/data/iptv/GuideImportProgress.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/iptv/GuideRules.kt"
    }
}
