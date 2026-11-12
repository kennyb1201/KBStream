package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The billboard / Spotlight block in COPY DIAGNOSTICS (spec 2026-11-12).
 *
 * Two open TV reports - a billboard that vanishes, cards that never expand on a
 * two-second dwell - were unanswerable from a paste that carried no billboard
 * lines at all. The trace itself has no Android dependency, so the shape the
 * paste depends on is tested directly; the write points and the read point are
 * pinned by source.
 */
class SpotlightDiagContractTest {

    @Before
    fun resetTrace() = SpotlightDiagTrace.reset()

    // ── S3/S4: an untouched trace writes nothing ──────────────────────────

    @Test
    fun `a device that never opened Spotlight adds no lines`() {
        assertEquals(emptyList<String>(), SpotlightDiagTrace.summaries())
        assertEquals(emptyList<String>(), SpotlightDiagTrace.lines())
    }

    // ── S1: empty feed vs never settled ──────────────────────────────────

    @Test
    fun `the billboard summary separates an empty feed from pages that never settled`() {
        SpotlightDiagTrace.billboardFeed(emptyList(), trendingDaySize = 0, merged = 0)
        SpotlightDiagTrace.billboardDealt(pages = 0, pool = 0, epoch = 3)
        SpotlightDiagTrace.billboardCleared("empty feed", epoch = 3)

        val summary = SpotlightDiagTrace.summaries()
        assertTrue(summary.all { it.startsWith("billboard: ") })
        val feed = summary.first { it.contains("feed ") }
        assertTrue("the selected feeds are named: $feed", feed.contains("selected=(none)"))
        assertTrue("an empty trending feed is named as empty: $feed", feed.contains("trendingDay=empty"))
        assertTrue("the merged pool is counted: $feed", feed.contains("merged=0"))
        val pages = summary.first { it.contains("pages ") }
        assertTrue("dealt and settled are both there: $pages", pages.contains("dealt=0 settled=0"))
        val clear = summary.first { it.contains("clear ") }
        assertTrue("the reason and epoch travel: $clear", clear.contains("reason=empty feed"))
        assertTrue(clear.contains("epoch=3"))

        // The other half of the question: a feed that DID deal pages but had
        // some of them resolve to null - the pages that never settle.
        SpotlightDiagTrace.reset()
        SpotlightDiagTrace.billboardFeed(listOf("trending_day"), trendingDaySize = 20, merged = 20)
        SpotlightDiagTrace.billboardDealt(pages = 3, pool = 20, epoch = 4)
        SpotlightDiagTrace.billboardPageResolved("movie:1", settledNow = 1, total = 3)
        SpotlightDiagTrace.billboardPageNull("show:2")

        val second = SpotlightDiagTrace.summaries().joinToString("\n")
        assertTrue(second.contains("trendingDay=size=20"))
        assertTrue(second.contains("dealt=3 settled=1"))
        assertTrue("the null count is the diagnosis: $second", second.contains("resolved=1 null=1 of 3"))
        assertTrue(
            "and the unsettled page is named in the transitions",
            SpotlightDiagTrace.lines().any { it.contains("resolve null show:2") }
        )
    }

    @Test
    fun `a failed resolve job carries the exception message, not a stack`() {
        SpotlightDiagTrace.billboardDealt(pages = 2, pool = 5, epoch = 1)
        SpotlightDiagTrace.billboardJobFailed("HTTP 429 from tmdb")
        val line = SpotlightDiagTrace.summaries().first { it.contains("resolve ") }
        assertTrue(line, line.contains("failed=\"HTTP 429 from tmdb\""))
        assertFalse("no stack frames in the paste", line.contains("\tat "))
    }

    // ── S2: the last snapshot survives the blur ───────────────────────────

    @Test
    fun `the spotlight summary still names the held card after it blurred`() {
        SpotlightDiagTrace.spotlightDwell(expandDelayMs = 2_000, menuOpen = false, claimEntryFocus = true)
        SpotlightDiagTrace.spotlightFocused(token = 7, key = "movie:550", title = "Fight Club")
        SpotlightDiagTrace.spotlightClaim(false)
        SpotlightDiagTrace.spotlightBlurred()

        val summary = SpotlightDiagTrace.summaries()
        assertTrue(summary.all { it.startsWith("spotlight: ") })
        val tracker = summary.first { it.contains("tracker ") }
        assertTrue("focus has left: $tracker", tracker.contains("held=no"))
        assertTrue("but the last card is kept: $tracker", tracker.contains("Fight Club"))
        assertTrue("with its key: $tracker", tracker.contains("movie:550"))
        assertTrue("and its token: $tracker", tracker.contains("focusToken=7"))
        assertTrue("never expanded for it: $tracker", tracker.contains("expanded=no"))

        val dwell = summary.first { it.contains("dwell ") }
        assertTrue("the live delay: $dwell", dwell.contains("delay=2000ms"))
        assertTrue("the menu state: $dwell", dwell.contains("menuOpen=no"))
        assertTrue("the entry claim flag: $dwell", dwell.contains("claimEntryFocus=yes"))
        assertTrue("and the claim's outcome: $dwell", dwell.contains("lastClaim=not-taken"))
    }

    @Test
    fun `an expand flips the last snapshot's record and names the guard that collapses it`() {
        SpotlightDiagTrace.spotlightFocused(token = 1, key = "movie:1", title = "Heat")
        SpotlightDiagTrace.spotlightExpanded("Heat")
        val tracker = SpotlightDiagTrace.summaries().first { it.contains("tracker ") }
        assertTrue(tracker, tracker.contains("expanded=yes"))

        SpotlightDiagTrace.spotlightCollapsed("menuOpen")
        val panel = SpotlightDiagTrace.summaries().first { it.contains("panel ") }
        assertTrue(panel, panel.contains("expanded=no"))
        assertTrue(panel, panel.contains("guard=menuOpen"))

        SpotlightDiagTrace.spotlightCollapsed("!hasSnapshot")
        val again = SpotlightDiagTrace.summaries().first { it.contains("panel ") }
        assertTrue("the latest guard wins: $again", again.contains("guard=!hasSnapshot"))
    }

    // ── S3: the block stays readable ─────────────────────────────────────

    @Test
    fun `the ring is capped and keeps the newest transitions`() {
        SpotlightDiagTrace.billboardDealt(pages = 1, pool = 1, epoch = 1)
        repeat(40) { i -> SpotlightDiagTrace.spotlightFocused(i, "movie:$i", "Title $i") }
        val lines = SpotlightDiagTrace.lines()
        assertEquals(32, lines.size)
        assertTrue("the newest is last: ${lines.last()}", lines.last().contains("token=39"))
        assertTrue("transitions only, one per event", lines.count { it.contains("focus card=") } == 32)
        assertTrue(
            "every line is prefixed, so the paste reads as one block",
            lines.all { it.startsWith("billboard: ") || it.startsWith("spotlight: ") }
        )
        assertTrue(
            "the summary kinds are fixed, so two pastes read alike",
            SpotlightDiagTrace.summaries().size <= 7
        )
    }

    @Test
    fun `reset clears every store`() {
        SpotlightDiagTrace.billboardDealt(pages = 2, pool = 2, epoch = 1)
        SpotlightDiagTrace.spotlightFocused(1, "movie:1", "Heat")
        SpotlightDiagTrace.reset()
        assertEquals(emptyList<String>(), SpotlightDiagTrace.summaries())
        assertEquals(emptyList<String>(), SpotlightDiagTrace.lines())
    }

    // ── F6: the read point ───────────────────────────────────────────────

    @Test
    fun `the paste prints the block right after upcoming, and nothing else changes`() {
        val diag = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
        val upcoming = diag.indexOf("UpcomingDiagTrace.lines().forEach")
        val block = diag.indexOf("SpotlightDiagTrace.summaries().forEach")
        assertTrue("the block exists", block > 0)
        assertTrue("and sits with the Home family, after upcoming", block > upcoming)
        assertTrue(
            diag.contains("SpotlightDiagTrace.lines().forEach { report.appendLine(it) }")
        )
    }

    // ── F4/F5: the write points ──────────────────────────────────────────

    @Test
    fun `the billboard records its feed, pages, nulls, failure and clear reason`() {
        val vm = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        listOf(
            "SpotlightDiagTrace.billboardFeed(",
            "SpotlightDiagTrace.billboardDealt(",
            "SpotlightDiagTrace.billboardPageResolved(",
            "SpotlightDiagTrace.billboardPageNull(",
            "SpotlightDiagTrace.billboardJobFailed(",
            "SpotlightDiagTrace.billboardCleared(reason, billboardResolveEpoch)"
        ).forEach { call -> assertTrue("missing $call", vm.contains(call)) }
        assertTrue(
            "the clear names its reason at the signature, so no caller can clear silently",
            vm.contains("fun clearBillboard(reason: String)")
        )
        assertTrue(
            "the empty deal is cleared with its reason",
            vm.contains("billboardCleared(\"empty feed\", dealEpoch)")
        )
        assertTrue(
            "the reason travels from both screens",
            source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt")
                .contains("clearBillboard(\"billboard disabled\")") &&
                source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
                    .contains("clearBillboard(\"billboard disabled\")")
        )
    }

    @Test
    fun `the tracker and the host record focus, claim and the dwell guard`() {
        val preview = source("com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt")
        assertTrue(preview.contains("SpotlightDiagTrace.spotlightFocused("))
        assertTrue(preview.contains("SpotlightDiagTrace.spotlightBlurred()"))
        val bounds = preview.substringAfter("fun updateBounds(bounds: Rect)").substringBefore("fun onCardFocus")
        assertFalse(
            "bounds copies are per-scroll and must never reach the ring",
            bounds.contains("SpotlightDiagTrace")
        )

        val host = source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt")
        assertTrue(host.contains("SpotlightDiagTrace.spotlightDwell("))
        assertTrue(host.contains("SpotlightDiagTrace.spotlightClaim(taken)"))
        assertTrue(host.contains("SpotlightDiagTrace.spotlightExpanded("))
        listOf("\"menuOpen\"", "\"!hasSnapshot\"", "\"!expands\"").forEach { guard ->
            assertTrue("the guard is named: $guard", host.contains(guard))
        }
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
