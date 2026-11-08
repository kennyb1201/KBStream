package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone guide's chrome (spec T1-T3).
 *
 * Two things were taking rows from the grid: the persistent "Guide last
 * imported from …" / failing line under the chips, and a detail strip that
 * printed a tap hint when nothing was tapped.
 *
 * The lines are not deleted - they say a guide is going stale before it reads
 * blank - so they move beside the button that fixes them, in the guide's own
 * setup panel. The strip collapses instead: it is the TAPPED program's detail,
 * and no tap means no detail.
 *
 * Source-reading style, like the guide's other contract tests: this module has
 * no Compose test harness, so the shape is asserted and the field settles it
 * (S1-S5).
 */
class MobileGuideChromeContractTest {

    private val screen = source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
    private val grid = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")

    private val mainScreen = screen
        .substringAfter("fun GuideScreenMobile(")
        .substringBefore("private fun MobileGuideSetup(")

    private val setupPanel = screen.substringAfter("private fun MobileGuideSetup(")

    // The strip is the file's last function, so its region runs to the end.
    private val detailStrip = grid.substringAfter("private fun MobileGridDetailStrip(")

    // ── T1: the persistent lines moved, they did not disappear ────────────

    @Test
    fun `the guide's main screen carries neither persistent status line`() {
        assertFalse(
            "F1a: the last-imported line is off the screen the viewer browses",
            mainScreen.contains("lastGoodLabel")
        )
        assertFalse(
            "and so is the background refresh's failure line",
            mainScreen.contains("FAILING_LABEL")
        )
    }

    @Test
    fun `the setup panel carries both, beside the import button`() {
        val lastGood = setupPanel.indexOf("guideStatus.lastGoodLabel()")
        val failing = setupPanel.indexOf("GuideRefreshStatus.FAILING_LABEL")
        val importButton = setupPanel.indexOf("\"IMPORT GUIDE\"")

        assertTrue("F1a: the last-imported line lands in the setup panel", lastGood >= 0)
        assertTrue("F1a: and the failing line with it", failing >= 0)
        assertTrue("the Import guide button is the thing they sit beside", importButton >= 0)
        assertTrue(
            "both read after the button that fixes them, not before it",
            lastGood > importButton && failing > importButton
        )
    }

    // ── T2: the strip exists only for a tapped program ────────────────────

    @Test
    fun `the strip is rendered only when a program is selected`() {
        assertTrue(
            "F2a: the call sits inside the non-null selection, so nothing tapped renders nothing",
            grid.contains(
                "selected?.let { picked -> MobileGridDetailStrip(selected = picked, nowMs = nowMs) }"
            )
        )
        assertFalse(
            "F2c: the tap hint goes with the empty case",
            grid.contains("Tap a program to watch")
        )
    }

    @Test
    fun `a tapped program still draws its title, channel headline and description`() {
        assertTrue(
            "the program's own title",
            detailStrip.contains("cell.title.orEmpty()")
        )
        assertTrue(
            "the channel and when it is on (F2b)",
            detailStrip.contains("\${item.channel.displayName} · \${gridCellHeadline(cell, nowMs)}")
        )
        assertTrue("and its description", detailStrip.contains("cell.description"))
    }

    @Test
    fun `a tapped gap still says No program data`() {
        assertTrue(
            "F2b: a gap cell keeps its own answer",
            detailStrip.contains("\"No program data\"")
        )
        assertTrue(
            "and the headline/description block is the program-only half",
            detailStrip.contains("if (!cell.isGap) {")
        )
    }

    // ── T3: what belongs to the moment stays on the main screen ───────────

    @Test
    fun `the transient import label and an active error stay where the viewer is`() {
        assertTrue(
            "F1b: the import's own progress/result label is unchanged",
            mainScreen.contains("\"Importing guide · \$guideImportLabel\"")
        )
        assertTrue(
            "and an error is never one tap away",
            mainScreen.contains("MobileGuideErrorLine(message)")
        )
    }

    @Test
    fun `the setup panel's import button still calls the same action`() {
        assertTrue(
            "T3: one Import guide button, wired to the ViewModel's import",
            screen.contains("onImportGuide = { viewModel.importGuide() }") &&
                setupPanel.contains("\"IMPORT GUIDE\"") &&
                setupPanel.contains("{ onImportGuide() }")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    /** Whitespace-normalized, so an assertion can name a whole statement. */
    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
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
