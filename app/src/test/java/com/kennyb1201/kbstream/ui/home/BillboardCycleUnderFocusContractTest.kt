package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: the phone billboard keeps cycling while a pill holds focus.
 *
 * Kenny picked this from the fork — "Let it cycle under focus too" — and the
 * requirement that comes with it is F2: removing the hold re-opens the fight F5
 * was written to avoid, so the press has to resolve against the page in front and
 * focus has to travel to the new page's equivalent pill.
 *
 * What is pinned here is that wiring, and that nothing else moved: the TV hero
 * still holds (it passes a non-null entry requester and takes the default), the
 * other pauses are still in the cycle effect, and the sweep's own focus input
 * (added 2026-11-25) is the D-pad surface's - never the cycle's hold. The
 * interactive half — S1–S3, a page change forced under a focused pill —
 * needs the device; the press rule's arithmetic is covered in
 * BillboardPressPageTest, and this class claims no frame.
 */
class BillboardCycleUnderFocusContractTest {

    private val mobile by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }
    private val spotlight by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt") }

    private fun cycleEffect(): String =
        mobile.substringAfter("LaunchedEffect(\n        pagerState,\n        cycleMs,")
            .substringBefore("// The sweep and the entrance pause")

    // ── F1: the pause is gone on the phone ────────────────────────────────

    @Test
    fun `the cycle no longer pauses while a pill holds focus on the phone`() {
        val cycle = cycleEffect()
        assertTrue("the cycle effect exists", cycle.isNotEmpty())
        assertFalse(
            "no early return on the raw focus flag",
            cycle.contains("if (pillsFocused) return@LaunchedEffect")
        )
        assertFalse(
            "and the raw flag is not one of its keys: focusing a pill must not " +
                "re-enter or re-arm the timer",
            cycle.contains("        pillsFocused\n    ) {")
        )
        assertTrue(
            "the phone's billboard asks for no pill hold",
            mobile.contains("holdCycleWhilePillsFocused = false")
        )
        assertTrue(
            "and the hold is the derived value a caller can switch off",
            mobile.contains("val pillsHoldCycle = holdCycleWhilePillsFocused && pillsFocused") &&
                cycle.contains("if (pillsHoldCycle) return@LaunchedEffect")
        )
    }

    // ── F2: the old fight must not come back ──────────────────────────────

    @Test
    fun `a pill press resolves the page at press time`() {
        val row = mobile.substringAfter("val pageActions: @Composable () -> Unit = {")
            .substringBefore("Column(\n        // The frame (spec 2.7)")
        assertTrue("the pills exist", row.isNotEmpty())
        assertTrue(
            "Play resolves through the press-time rule",
            row.substringAfter("label = \"Play\"")
                .substringBefore("label = \"Details\"")
                .let { play ->
                    play.contains("billboardPressPage(") &&
                        play.contains("currentIndex = pagerState.currentPage")
                }
        )
        assertTrue(
            "and so does Details",
            row.substringAfter("label = \"Details\"")
                .let { details -> details.contains("billboardPressPage(") }
        )
        assertFalse(
            "no pill acts on a page captured when it took focus",
            row.contains("onClick = { currentPage?.let(onPlay) }") ||
                row.contains("onClick = { currentPage?.let(onDetails) }")
        )
        // The trailer pill's press already read the pager at press time.
        assertTrue(
            "the trailer press keeps its press-time index",
            row.contains("startTrailer(pagerState.currentPage)")
        )
    }

    @Test
    fun `focus lands on the equivalent pill of the new page`() {
        val effect = mobile.substringAfter("LaunchedEffect(pagerState.currentPage) {")
            .substringBefore("fun stopTrailer()")
        assertTrue(
            "the page change hands focus over",
            effect.contains("pillFocusFollowsPage && pill != null") &&
                effect.contains("landFocusOn(requester)")
        )
        assertTrue(
            "to the equivalent pill, by identity",
            effect.contains("BillboardPill.PLAY -> playPillRequester") &&
                effect.contains("BillboardPill.DETAILS -> detailsPillRequester") &&
                effect.contains("BillboardPill.TRAILER -> trailerPillRequester")
        )
        assertTrue(
            "with Play as the fallback when that page draws no such pill",
            effect.contains("if (!restored && pill != BillboardPill.PLAY) landFocusOn(playPillRequester)")
        )
        // Which pill the hand is on comes from the pill itself, not from a guess
        // at the row level.
        assertTrue(
            "each pill reports its own focus",
            mobile.contains("onFocused = { focusedPill = BillboardPill.PLAY }") &&
                mobile.contains("onFocused = { focusedPill = BillboardPill.DETAILS }") &&
                mobile.contains("onFocused = { focusedPill = BillboardPill.TRAILER }")
        )
        assertTrue(
            "and the row waits a frame before forgetting it, so the page change can " +
                "still hand it over",
            mobile.contains("if (!pillsFocused && focusedPill != null) {") &&
                mobile.contains("awaitFrame()")
        )
        assertFalse(
            "the hand-off is the phone's; the TV surface is untouched",
            spotlight.contains("pillFocusFollowsPage")
        )
    }

    // ── F3: every other hold stays ────────────────────────────────────────

    @Test
    fun `the other pauses are unchanged`() {
        val cycle = cycleEffect()
        listOf(
            "if (backgrounded || playbackPageKey != null || playback != null)",
            "if (autoTrailerArmed) return@LaunchedEffect",
            "if (trailerSuppressed) return@LaunchedEffect"
        ).forEach { rung ->
            assertTrue("the cycle still pauses on $rung", cycle.contains(rung))
        }
        // 2026-11-25: the sweep gained a focus input of its own, gated to the
        // D-pad surface. That is not this hold coming back through the side door:
        // the timer still reads `pillsHoldCycle`, and none of the sweep's inputs
        // is a key of this effect, so a rail walk holds the still without
        // re-arming or freezing the page cycle.
        val sweep = mobile.substringAfter("val sweepPaused = reducedMotion ||")
            .substringBefore("// The page this Animatable belongs to")
        assertTrue(
            "the sweep's focus input is the D-pad surface's, through its own gate",
            mobile.contains("val sweepHoldsForFocus = onPillUpPressed != null && !pillsFocused") &&
                sweep.contains("sweepHoldsForFocus")
        )
        assertFalse(
            "and the cycle's hold stays its own: it reads none of the sweep's inputs",
            cycle.contains("sweepHoldsForFocus") ||
                cycle.contains("billboardOnScreen") ||
                sweep.contains("pillsHoldCycle")
        )
    }

    // ── F4: manual navigation still re-arms ───────────────────────────────

    @Test
    fun `the timer re-arms from the page that becomes current`() {
        val cycle = cycleEffect()
        assertTrue(
            "the delay starts from the settled page",
            // Spec audit 2026-11-18, Home L4: the page's settle flag is now part
            // of the pair, so a resolve restarts the dwell.
            cycle.contains("val page = pagerState.settledPage") &&
                cycle.contains("pagerState.isScrollInProgress,")
        )
        assertTrue(
            "and a page change cancels and re-arms it",
            cycle.contains("delay(cycleMs)") &&
                cycle.contains("pagerState.animateScrollToPage(next)")
        )
        assertTrue(
            "the effect is keyed on the pager, so a swipe or a segment press re-arms too",
            mobile.contains("LaunchedEffect(\n        pagerState,\n        cycleMs,")
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
