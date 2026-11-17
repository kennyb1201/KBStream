package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's first entry (spec 2026-11-17).
 *
 * Field report, phone 16:36: "on first enter of app the billboard flashes
 * rapidly and its jarring". The 17:50 paste shows the burst it rides on - a
 * deal, then twenty settles - and the hero's art is chosen from the live page
 * list, so each settle could move it. The fix is three properties:
 *
 *  - F1: the art a page shows is HELD once drawn (own art still takes over,
 *    once);
 *  - F2: the handover draws no empty frame - the previous frame is the
 *    placeholder, and there is no crossfade to fade in from nothing;
 *  - F3: the image request is keyed on the ART URL, so the settle burst (which
 *    rewrites the page meta several times) cannot rebuild it.
 *
 * The composable cannot be unit-tested here (no Compose UI test rule on this
 * project), so the wiring is pinned at the source, and the pure rule and the
 * trace shape are covered by BillboardHeroArtHoldTest and
 * SpotlightDiagContractTest.
 */
class BillboardFirstEntryFlashContractTest {

    private val home by lazy { source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt") }
    private val hold by lazy { source("com/kennyb1201/kbstream/ui/mobile/BillboardHeroArtHold.kt") }
    private val trace by lazy { source("com/kennyb1201/kbstream/data/reporting/SpotlightDiagTrace.kt") }

    // ── F1: the art is sticky once drawn ─────────────────────────────────

    @Test
    fun `the billboard holds the first art each page showed, per deal`() {
        assertTrue(
            "one hold, keyed by the deal's own pages - a new deal is a new page-key list",
            home.contains(
                "remember(pages.map { page -> page.key }) { mutableStateMapOf<String, String>() }"
            )
        )
        assertTrue(
            "the page asks the hold before the live fallback",
            home.contains("heldArt = heldHeroArt[page.key]") &&
                home.contains("liveFallback = billboardHeroArt(pages, index)")
        )
        assertTrue(
            "and only a page with NO art of its own records one: own art is never held over",
            home.contains("if (billboardPageArt(page) == null &&") &&
                home.contains("heldHeroArt[page.key] = heroArt")
        )
        assertTrue(
            "the hero draws the held answer, not a live call in the pager",
            home.contains("backdropUrl = heroArt,") &&
                !home.contains("backdropUrl = billboardHeroArt(pages, index)")
        )
    }

    @Test
    fun `the hold is written after composition, not during it`() {
        val item = home
            .substringAfter("val heroArt = heroArtFor(page, index)")
            .substringBefore("val sweep = remember(index, cycleMs)")
        assertTrue(
            "the record is a SideEffect - a state write in composition would be a " +
                "backward write, and the frame it drew is already the value it records",
            item.contains("SideEffect {") && item.contains("heldHeroArt[page.key] = heroArt")
        )
    }

    // ── F2: no fade through an empty frame ───────────────────────────────

    @Test
    fun `the swap holds the previous frame and fades from nothing`() {
        val hero = home
            .substringAfter("private fun MobileHero(")
            .substringBefore("private fun BillboardPageChevron(")
        assertTrue(
            "the last painted frame is the placeholder, so a swap is never an empty frame",
            hero.contains("placeholder = heldArt") &&
                hero.contains("onSuccess = { state -> heldArt = state.painter }")
        )
        assertFalse(
            "Coil's crossfade blends from the PLACEHOLDER, so with no fade the new art " +
                "replaces the old one directly",
            hero.contains(".crossfade(true)")
        )
    }

    // ── F3: the model is keyed on the art URL ────────────────────────────

    @Test
    fun `the settle burst cannot rebuild the hero model`() {
        assertTrue(
            "keyed on the art URL alone",
            home.contains("model = remember(backdropUrl) {")
        )
        assertFalse(
            "not on the page's meta, which the settle burst rewrites for an unchanged image",
            home.contains("model = remember(backdropUrl, meta) {")
        )
    }

    // ── F4: the swap count reaches the paste ─────────────────────────────

    @Test
    fun `the art swap count is written through the billboard trace`() {
        assertTrue(
            "the billboard writes the line, for the page in front",
            home.contains("SpotlightDiagTrace.billboardHeroArt(") &&
                home.contains("source = artSource") &&
                home.contains("url = currentHeroArt")
        )
        assertTrue(
            "the trace owns the per-page and session counts, and prints the page table",
            trace.contains("fun billboardHeroArt(page: Int, source: String, url: String?)") &&
                trace.contains("art swap page=") &&
                trace.contains("perPage=[")
        )
        assertTrue(
            "and it is a summary slot right after the render line, so COPY DIAGNOSTICS " +
                "carries it without a swap having to happen",
            trace.contains("listOf(\"gate\", \"render\", \"art\", \"rest\"")
        )
        assertTrue(
            "the rule itself is the shared pure function",
            hold.contains("internal fun billboardStickyHeroArt(")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
