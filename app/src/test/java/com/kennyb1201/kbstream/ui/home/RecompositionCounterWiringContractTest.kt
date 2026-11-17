package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurements dig four said were still missing (2026-11-16).
 *
 * Two gaps, both of them "there is nothing in the app to take the number with":
 *
 *  - the recomposition count F4 is gated on (S1) - a counter on the Home rail
 *    items, printed in the diagnostics report;
 *  - the hero-decode attribution §1c describes - `heroDecodes` mixes the hero's
 *    own draw with Detail's, Actor's and the never-displayed lookahead warm,
 *    because the app-wide loader funnel classifies by pixel width alone.
 *
 * The counters are only worth having if the call sites that feed them exist and
 * sit where the work happens, so this pins the wiring, not the arithmetic (that
 * is [com.kennyb1201.kbstream.data.reporting.RecompositionTraceTest]).
 */
class RecompositionCounterWiringContractTest {

    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt") }
    private val trace by lazy {
        source("com/kennyb1201/kbstream/data/reporting/RecompositionTrace.kt")
    }
    private val imageTrace by lazy {
        source("com/kennyb1201/kbstream/data/reporting/ImageLoadTrace.kt")
    }
    private val diagnostics by lazy {
        source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
    }

    // ── S1: the rail items count themselves ──────────────────────────────

    @Test
    fun `the Home rail items tick a recomposition counter`() {
        val ticks = Regex("RecompositionTrace\\.noteRailItem\\(").findAll(home).count()
        assertTrue(
            "one tick per rail item scope, in both Home rails (catalog + Top Picks): $ticks",
            ticks >= 2
        )
        assertTrue(
            "and it runs after the composition is applied, so a skipped item " +
                "does not tick (a `remember`-side or composition-side count would)",
            home.contains("SideEffect {\n                        RecompositionTrace.noteRailItem(")
        )
        assertTrue(
            "the clock is passed in: the trace stays free of Android so its " +
                "arithmetic is testable",
            home.contains("android.os.SystemClock.elapsedRealtime()") &&
                !trace.contains("import android.") &&
                !trace.contains("SystemClock")
        )
    }

    @Test
    fun `the count is printed in the diagnostics report`() {
        assertTrue(
            "beside the image lines, where a walk's paste is read",
            diagnostics.contains("ImageLoadTrace.lines().forEach { report.appendLine(\"image: \$it\") }") &&
                // The line names itself ("recomp: railItems=..."), so the report
                // appends it as it comes.
                diagnostics.contains("RecompositionTrace.lines().forEach { report.appendLine(it) }")
        )
        assertTrue(
            "the line names the peak per 16 ms window, which is the number F4 reads",
            trace.contains("peak=\${c.peakPerWindow} per") && trace.contains("WINDOW_MS")
        )
        assertTrue(
            "and a surface says nothing before one of its items has recomposed " +
                "(round four F8b made this per surface)",
            trace.contains("if (c.total <= 0L) return@mapNotNull null")
        )
    }

    // ── §1c: the hero's draw and the warms are counted where they happen ──

    @Test
    fun `the hero draw is counted where it is drawn`() {
        val art = home
            .substringAfter("private fun HeroBackdropArt(")
            .substringBefore("/**\n * A clear logo")
        assertTrue(
            "the draw, not the decode: this fires for a memory hit too, which is " +
                "exactly what `heroDecodes` cannot show",
            art.contains("onSuccess = { ImageLoadTrace.noteHeroDraw() }")
        )
    }

    @Test
    fun `the warms are counted where they are warmed, and only when they decode`() {
        val heroWarm = viewModel
            .substringAfter("private suspend fun warmHeroArtBitmap(")
            .substringBefore("private fun warmHeroLookahead(")
        assertTrue(
            "the hero-size warm",
            heroWarm.contains("ImageLoadTrace.noteHeroWarm()")
        )
        val tileWarm = viewModel
            .substringAfter("private suspend fun warmRailTile(")
            .substringBefore("/**")
        assertTrue(
            "the rail-tile warm",
            tileWarm.contains("ImageLoadTrace.noteRailTileWarm()")
        )
        listOf(heroWarm, tileWarm).forEach { warm ->
            assertTrue(
                "a warm that hit the memory cache decoded nothing and must not " +
                    "be counted as a decode",
                warm.contains("result is coil3.request.SuccessResult") &&
                    warm.contains("coil3.decode.DataSource.MEMORY_CACHE")
            )
        }
    }

    @Test
    fun `the report prints the split beside the counts it splits`() {
        assertTrue(
            "heroDraws / heroWarms / tileWarms, in the same block as heroDecodes " +
                "and cardDiskDecodes",
            imageTrace.contains("\"art: heroDraws=\$heroDraws heroWarms=\$heroWarms \"") &&
                imageTrace.contains("tileWarms=\$tileWarms")
        )
        assertEquals(
            "and the counters are cleared with the rest (tests depend on it)",
            true,
            imageTrace.substringAfter("fun reset()").contains("heroDraws = 0") &&
                imageTrace.substringAfter("fun reset()").contains("tileWarms = 0")
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: ${file.absolutePath}")
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
