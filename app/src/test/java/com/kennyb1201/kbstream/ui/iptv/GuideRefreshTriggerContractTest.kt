package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why a guide refresh now starts without anybody asking.
 *
 * The field case: Profile 1's guide went completely blank and a manual reimport
 * fixed it, on a device where nothing was broken — the refresh just never ran
 * while it mattered. So the three gates this pins are the three ways a profile
 * can be seen: on entry (Trigger A), after a profile switch (Trigger B), and
 * the moment Free TV writes its guide lines (Trigger C). The decision itself is
 * [com.kennyb1201.kbstream.data.iptv.GuideRefreshPolicy], unit tested with the
 * window boundaries; what cannot be driven on the JVM is the wiring, which is
 * what this reads.
 *
 * The worker half is here for the same reason: a pass that imports nothing used
 * to return `Result.failure()`, and that CANCELS periodic work — a dead week
 * ended the schedule silently. Both halves of that (retry, and a visible
 * failing state) are load-bearing and neither is visible on a TV.
 */
class GuideRefreshTriggerContractTest {

    private val vm by lazy { read(IPTV_VM) }
    private val worker by lazy { read(WORKER) }
    private val tvGuide by lazy { read(GUIDE_TV) }
    private val mobileGuide by lazy { read(GUIDE_MOBILE) }

    /** The worker with its comments stripped, so prose cannot be read as code. */
    private val workerCode: String by lazy {
        worker.lines().joinToString("\n") { it.substringBefore("//") }
    }

    // ── Trigger A: entry, decided on the window ───────────────────────────

    @Test
    fun `entry decides on where the window ends, not only on age`() {
        val entry = body("private suspend fun refreshIfNeeded(forceGuide: Boolean = false) {")

        assertTrue(
            "the window end is the primary signal",
            entry.contains("guideDataEndsAt()") && entry.contains("GuideRefreshPolicy.urgency(")
        )
        assertTrue(
            "age stays as the secondary signal, so a partial pass still retries the source it lost",
            entry.contains("isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS)")
        )
        assertTrue(
            "and the file-moved marker is still part of that age half",
            entry.contains("guideFileMoved()")
        )
        assertTrue(
            "an entry refresh uses the shared import launcher, so the button can still stop it",
            entry.contains("launchGuideImport()")
        )
    }

    @Test
    fun `an entry import is never deferred by playback`() {
        // The worker defers its whole run while something plays - right for a
        // background pass, wrong here: on a TV the box plays for hours, and a
        // grid that is thinning has to refill while the viewer is watching it.
        // Only the SAVE phase is paced (EpgWriteGate inside the importer).
        val entry = body("private suspend fun refreshIfNeeded(forceGuide: Boolean = false) {")

        assertFalse(
            "the entry trigger must not consult the playback gate",
            entry.contains("EpgWriteGate") || entry.contains("isPlayerActive")
        )
    }

    @Test
    fun `an import already running is never duplicated`() {
        val entry = body("private suspend fun refreshIfNeeded(forceGuide: Boolean = false) {")

        assertTrue(
            "the screen's own pass",
            entry.contains("guideImportJob?.isActive")
        )
        assertTrue(
            "and a pass the repository is running (the worker's), which has no handle here",
            entry.contains("_isImportingGuide.value")
        )
        assertTrue(entry.contains("canImportGuide"))
    }

    @Test
    fun `the status line is republished whenever the answer can change`() {
        assertTrue(
            "on the entry check",
            vm.contains("publishGuideStatus(guideEndsAtMs)")
        )
        assertTrue(
            "and when a pass ends, with the window it left behind",
            vm.substringAfter("private suspend fun importGuideInternal(")
                .substringBefore("private suspend fun guideDataEndsAt")
                .contains("publishGuideStatus(guideEndsAtMs)")
        )
    }

    // ── Trigger B: the profile you come back to ───────────────────────────

    @Test
    fun `a profile switch refreshes the profile it switched to`() {
        val switch = vm
            .substringAfter("private fun observeProfileSwitches()")
            .substringBefore("private fun startGuideClockRefresh()")
        assertTrue(
            "the switch re-reads the incoming profile's sources and restores its cache",
            switch.contains("restoreCachedPlaylist()")
        )
        assertTrue(
            "which is the cache restore the entry trigger already runs from",
            body("private fun restoreCachedPlaylist() {")
                .contains("refreshIfNeeded(forceGuide = guideSourceAdded)")
        )
        assertTrue(
            "and that check reads the ACTIVE profile's store, resolved per call - the " +
                "previously active profile's guide is never the one read",
            vm.contains("private val prefs") && vm.contains("ProfileStorage.prefsName(")
        )
    }

    // ── Trigger C: a new guide line is an import ──────────────────────────

    @Test
    fun `a newly written source line starts its own import`() {
        val starter = body("private fun startGuideImportForNewSource() {")

        assertTrue(
            "the free source lines fill now, not on the next IMPORT GUIDE",
            starter.contains("launchGuideImport()")
        )
        assertTrue(
            "an in-flight pass IS the import, so no second one starts",
            starter.contains("_isImportingGuide.value || guideImportJob?.isActive == true")
        )
        assertTrue(
            "and it never fires without a source to import",
            starter.contains("if (allEpgUrls().isEmpty()) return")
        )
    }

    @Test
    fun `a load that wrote no line keeps the ordinary entry rules`() {
        val entry = body("private suspend fun refreshIfNeeded(forceGuide: Boolean = false) {")

        assertTrue(
            "forcing is opt-in: without it the window and age rules decide, so an " +
                "already-installed lineup's reload does not re-import a fresh guide",
            entry.contains("(forceGuide || GuideRefreshPolicy.needsImport(guideUrgency))")
        )
    }

    @Test
    fun `the migrations report the line they wrote`() {
        assertTrue(
            "a migration answers whether it wrote anything, or the caller cannot know " +
                "to import",
            vm.contains("private fun migrateFreeLineupLocalsEpg(): Boolean") &&
                vm.contains("private fun migrateFreeLineupFastEpg(): Boolean")
        )
        assertTrue(
            "the restore combines both answers and forces the import",
            vm.contains("val guideSourceAdded = localsLineAdded || fastLinesAdded")
        )
        assertTrue(
            "including on the cache-MISS path, which loads before it refreshes",
            vm.contains("if (guideSourceAdded) loadWithGuideRefresh() else load()")
        )
    }

    @Test
    fun `the free tv opt-in imports what it wrote`() {
        val install = vm
            .substringAfter("fun installFreeLineup(")
            .substringBefore("fun removeFreeLineup(")

        assertTrue(
            "the tap knows whether the install added a guide line at all",
            install.contains("guideLineWritten = guides != guidesBefore")
        )
        assertTrue(
            "and the load that follows forces the import for it",
            install.contains("loadLineup(forceGuideRefresh = guideLineWritten)")
        )
    }

    // ── The worker: never cancel the schedule ─────────────────────────────

    @Test
    fun `a dead week never cancels the periodic work`() {
        assertFalse(
            "Result.failure cancels periodic work outright: a week of empty passes " +
                "used to end the install's guide refreshes, and only the next app " +
                "launch brought them back",
            workerCode.contains("Result.failure")
        )
        assertTrue(
            "the bounded empty run keeps retrying on WorkManager's backoff",
            workerCode.contains("Result.retry()")
        )
        assertTrue(
            "and records the failing state instead of stopping",
            workerCode.contains("KEY_EPG_REFRESH_FAILING")
        )
        assertTrue(
            "a source landing resets both the counter and the failing state",
            workerCode.contains(".putInt(KEY_EPG_EMPTY_RUNS, 0)") &&
                workerCode.contains(".putBoolean(KEY_EPG_REFRESH_FAILING, false)")
        )
    }

    @Test
    fun `the worker and the in-app import stamp the same per-source store`() {
        assertTrue(
            "the worker records through the shared store, not its own copy of the format",
            workerCode.contains("GuideSourceStamps.recordSuccess(prefs, url)")
        )
        assertTrue(
            "and so does the in-app pass, which is what makes the setup screen's " +
                "'last imported from' line true after a manual or entry import",
            vm.contains("GuideSourceStamps.recordSuccess(prefs, url)")
        )
    }

    @Test
    fun `the failing state is shown where the button is`() {
        listOf("the TV form" to tvGuide, "the phone form" to mobileGuide).forEach { (form, src) ->
            assertTrue(
                "$form must show which source last imported",
                src.contains("guideStatus.lastGoodLabel()")
            )
            assertTrue(
                "$form must show the failing state beside IMPORT GUIDE",
                src.contains("GuideRefreshStatus.FAILING_LABEL")
            )
        }
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
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val IPTV_VM = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
        const val WORKER = "com/kennyb1201/kbstream/data/iptv/EpgRefreshWorker.kt"
        const val GUIDE_TV = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val GUIDE_MOBILE = "com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt"
    }
}
