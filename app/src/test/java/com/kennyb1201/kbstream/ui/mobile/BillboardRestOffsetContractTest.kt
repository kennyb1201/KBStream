package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: the billboard pager rests centred, on both surfaces.
 *
 * The bug was a strip of the NEXT title's art at the right edge of a phone
 * billboard that was sitting still. Not the sweep (dcb79b703 covers both edges
 * and the phone's art overscans) - the pager resting off-centre - and the same
 * strip showed on the TV, which shares this composable. The cause was not
 * established from reading, so the fix targets the observable: at rest the offset
 * is zero, and if it ever isn't, it is put back through the one API that snaps
 * WITHOUT a scroll session, because a scroll session would re-arm the page timer.
 *
 * These pins hold that shape: the observer, its two conditions, the snap API, the
 * after-every-move guarantee, the trace, and the list of things that must not
 * have moved.
 */
class BillboardRestOffsetContractTest {

    private val mobile by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }

    /** The observer effect, from its effect call to the settle helper that follows. */
    private fun observer(): String =
        mobile.substringAfter("    LaunchedEffect(pagerState) {")
            .substringBefore("    val settleRestOffset: suspend () -> Unit = {")

    private fun settleHelper(): String =
        mobile.substringAfter("    val settleRestOffset: suspend () -> Unit = {")
            .substringBefore("    val currentPage = pages.getOrNull(pagerState.currentPage)")

    private fun cycleEffect(): String =
        mobile.substringAfter("        if (pillsHoldCycle) return@LaunchedEffect")
            .substringBefore("    // The sweep and the entrance pause with what the hold pauses with")

    /** The pager's own call, up to its first page body. */
    private fun pagerSetup(): String =
        mobile.substringAfter("            HorizontalPager(")
            // Up to the sweep call site, whose key line changed with Home L2
            // (the move no longer takes the dwell).
            .substringBefore("val sweep = remember(index)")

    // ── F1 + F2: the observer ─────────────────────────────────────────────

    @Test
    fun `the observer watches the offset and the scroll flag together`() {
        val observer = observer()
        assertTrue(
            "both halves, from one snapshot: the offset alone cannot tell a settle from a drag",
            observer.contains("pagerState.currentPageOffsetFraction to pagerState.isScrollInProgress")
        )
        assertTrue(
            "F2: a drag or an animation in flight is never corrected",
            observer.contains("if (scrolling) return@collect")
        )
        assertTrue(
            "and the decision is the pure rule, not an inline magic number",
            observer.contains("if (!billboardRestOffCenter(offset)) return@collect")
        )
    }

    @Test
    fun `the snap uses the API that opens no scroll session`() {
        val observer = observer()
        assertTrue(
            "F2: requestScrollToPage snaps on the next remeasure and never flips " +
                "isScrollInProgress, so the cycle's (page, scrolling) flow sees nothing",
            observer.contains("pagerState.requestScrollToPage(pagerState.currentPage)")
        )
        assertFalse(
            "a scroll session here would emit (page, false) twice and restart the page timer",
            observer.contains("scrollToPage(pagerState.currentPage)")
        )
        assertFalse(
            "and it must never animate: F3 says the check runs after animations, never instead of one",
            observer.contains("animateScrollToPage(pagerState.currentPage)")
        )
    }

    @Test
    fun `the cycle effect still keys on the page, the scroll flag and the page's own settle`() {
        val cycle = cycleEffect()
        assertTrue(
            "S6: no correction state reaches the timer - the page's settle flag is the only " +
                "addition since (spec audit 2026-11-18, Home L4: the dwell must not burn " +
                "while the page is still resolving)",
            cycle.contains("val page = pagerState.settledPage") &&
                cycle.contains("pagerState.isScrollInProgress,") &&
                cycle.contains("pagesNow.value.getOrNull(page)?.settled == true")
        )
        assertFalse(
            "no correction state was threaded into the timer's key - the snap is a " +
                "separate flow, not an input to this one",
            cycle.substringAfter("snapshotFlow {").substringBefore("}")
                .let { it.contains("settleRestOffset") || it.contains("restCorrections") }
        )
        assertTrue(
            "the delay still arms from the rest emission and is untouched",
            cycle.contains("                delay(cycleMs)")
        )
        assertTrue(
            "the tick still finishes with the rest check (F3)",
            cycle.contains("pagerState.animateScrollToPage(next)\n                    settleRestOffset()")
        )
    }

    // ── F3: every move this file makes ────────────────────────────────────

    @Test
    fun `every programmatic page change is checked once its animation ends`() {
        val calls = Regex("""animateScrollToPage\(\w+\)\s*\n\s*settleRestOffset\(\)""")
            .findAll(mobile)
            .count()
        assertTrue(
            "the cycle tick, the chevron step and the segment press - three sites, " +
                "each sequenced after its own animation; found $calls",
            calls == 3
        )
        assertTrue(
            "and the helper corrects through the same no-scroll API",
            settleHelper().contains("pagerState.requestScrollToPage(pagerState.currentPage)")
        )
    }

    // ── F4: the paste can prove it ────────────────────────────────────────

    @Test
    fun `the trace records every rest, and says when one had to be corrected`() {
        val observer = observer()
        assertTrue(
            "a rest line at rest, unmarked",
            observer.contains("corrected = false")
        )
        assertTrue(
            "and a corrected one carrying the offset that was put back",
            observer.contains("corrected = true") && observer.contains("offset = offset")
        )
        assertTrue(
            "both go through the same trace the other billboard lines use",
            observer.contains("SpotlightDiagTrace.billboardRest(")
        )
        assertTrue(
            "and so does a post-move correction",
            settleHelper().contains("SpotlightDiagTrace.billboardRest(")
        )
    }

    // ── F5: scope ─────────────────────────────────────────────────────────

    @Test
    fun `the shared surface only - nothing else about the billboard moved`() {
        assertFalse(
            "the sweep's math is untouched (dcb79b703 stays as it was)",
            mobile.contains("billboardSweep(") &&
                mobile.substringAfter("fun billboardSweep(").isEmpty()
        )
        assertTrue(
            // Spec audit 2026-11-18, Home L2: the move carries no clock, so the
            // call site no longer takes (or is keyed on) the dwell.
            "the sweep call site is still the pure rule, now without a clock",
            mobile.contains("val sweep = remember(index) { billboardSweep(index) }")
        )
        val pager = pagerSetup()
        assertTrue(
            "the pager's own setup is unchanged: one neighbour, no spacing, no padding",
            pager.contains("beyondViewportPageCount = 1,") &&
                !pager.contains("pageSpacing = ") &&
                !pager.contains("contentPadding = ")
        )
        assertTrue(
            "the chevrons and segments still step through the same wrapper rule",
            mobile.contains(
                "val target = billboardWrapPage(pages.size, pagerState.currentPage, offset)"
            )
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }
}
