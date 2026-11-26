package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's sweep holds still while the viewer is on the rails
 * (spec 2026-11-25, F1 + F2).
 *
 * Two inputs say "nobody is looking at the billboard": on the D-pad surface,
 * focus has left the pills for a rail card or the top bar; on either surface,
 * the frame is scrolled fully above the viewport. Both feed the EXISTING pause -
 * the driver's stop-and-resume is untouched, so a hold keeps the fraction it
 * reached and resumes the rest of the fixed duration rather than restarting or
 * snapping. The page cycle's own pill hold is a separate input and stays that
 * way: the page does not advance under a focused pill, whether the sweep is
 * paused or running.
 *
 * A source-reading test, like its neighbours in this package: the module has no
 * Compose test rule, and what this pins is a missing input plus the shape of the
 * driver that consumes it - the frame-level check is the spec's field test.
 */
class BillboardSweepRailsHoldContractTest {

    private val mobile by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }

    /** The sweep's pause expression, from its first rung to the page marker. */
    private fun sweepList(): String =
        mobile.substringAfter("val sweepPaused = reducedMotion ||")
            .substringBefore("// The page this Animatable belongs to")

    /** The sweep effect: from its key list to the entrance effect that follows. */
    private fun sweepEffect(): String =
        mobile.substringAfter("LaunchedEffect(pagerState.settledPage, reducedMotion) {")
            .substringBefore("// One entrance per page entry")

    /** The page cycle effect, up to the sweep's comment block. */
    private fun cycleEffect(): String =
        mobile.substringAfter("LaunchedEffect(\n        pagerState,\n        cycleMs,")
            .substringBefore("// The sweep and the entrance pause")

    /** [MobileBillboard]'s parameter list - the place a new parameter would appear. */
    private fun signature(): String {
        val from = mobile.indexOf("internal fun MobileBillboard(")
        val to = mobile.indexOf("\n) {", from)
        return if (from >= 0 && to > from) mobile.substring(from, to) else ""
    }

    // ── F1: TV - the pills losing focus holds the sweep ────────────────────

    @Test
    fun `focus off the pills holds the sweep on the D-pad surface`() {
        val sweep = sweepList()
        assertTrue(
            "the input is declared, gated to the surface with a D-pad",
            mobile.contains("val sweepHoldsForFocus = onPillUpPressed != null && !pillsFocused")
        )
        assertTrue(
            "and it is one of the sweep's pause inputs",
            sweep.contains("sweepHoldsForFocus")
        )
        assertFalse(
            "never a bare focus test: the phone's pills never take focus on touch, so " +
                "`!pillsFocused` alone would freeze the phone's sweep for good",
            sweep.contains("!pillsFocused")
        )
        assertTrue(
            "and the gate says why - heroHeight is no longer a TV marker, since above " +
                "Compact the phone's hero takes a fixed height too",
            mobile.contains("`heroHeight != null` cannot be the marker any more") &&
                mobile.contains("the phone's hero takes a fixed height too")
        )
    }

    @Test
    fun `restoring pill focus resumes the same move - the driver is not re-keyed`() {
        val effect = sweepEffect()
        assertTrue("the sweep effect exists", effect.isNotEmpty())
        assertTrue(
            "the pause is observed INSIDE the effect, so a resume does not re-enter it",
            mobile.contains("val sweepPausedNow = rememberUpdatedState(sweepPaused)") &&
                effect.contains("snapshotFlow { sweepPausedNow.value }.collectLatest")
        )
        assertFalse(
            "none of the new inputs is a key of the effect - that is what restarted it before",
            mobile.contains("LaunchedEffect(pagerState.settledPage, sweepHoldsForFocus") ||
                mobile.contains("LaunchedEffect(pagerState.settledPage, billboardOnScreen") ||
                mobile.contains("sweepHoldsForFocus, reducedMotion)")
        )
    }

    // ── F2: phone - a billboard nobody can see holds too ───────────────────

    @Test
    fun `a billboard scrolled above the viewport holds the sweep on the phone`() {
        val sweep = sweepList()
        assertTrue(
            "the frame keeps its own on-screen state, starting visible so the first " +
                "frame is never frozen before layout",
            mobile.contains("var billboardOnScreen by remember { mutableStateOf(true) }")
        )
        assertTrue(
            "read from the frame's global position, once per crossing rather than per " +
                "scroll frame",
            mobile.contains(".onGloballyPositioned { coordinates ->") &&
                mobile.contains("val onScreen = coordinates.boundsInWindow().bottom > 0f") &&
                mobile.contains("if (onScreen != billboardOnScreen) billboardOnScreen = onScreen")
        )
        assertTrue(
            "paused only when the frame's bottom edge is at or above the viewport top - " +
                "a partly visible billboard still sweeps",
            sweep.contains("!billboardOnScreen")
        )
        assertTrue(
            "and it is self-contained: no parameter was added to the billboard",
            signature().isNotEmpty() && !signature().contains("billboardOnScreen")
        )
    }

    // ── the driver: a hold keeps the fraction, the resume is the rest ──────

    @Test
    fun `a hold keeps the fraction and the resume is the rest of the same duration`() {
        val effect = sweepEffect()
        assertTrue(
            "a pause STOPS the Animatable, which keeps the value it reached",
            effect.contains("if (paused) {") && effect.contains("sweepProgress.stop()")
        )
        assertEquals(
            "one hold path - no alternate stop that could snap it",
            1,
            Regex("sweepProgress\\.stop\\(\\)").findAll(effect).count()
        )
        assertTrue(
            "a resume animates the REMAINING part of the fixed ten seconds from the held " +
                "fraction",
            effect.contains("val remainingMs = ((1f - sweepProgress.value) * BILLBOARD_KEN_BURNS_MANUAL_MS)")
        )
        assertEquals(
            "only reduced motion and a page change snap: the two inputs added here never do",
            2,
            Regex("sweepProgress\\.snapTo\\(").findAll(effect).count()
        )
        assertTrue(
            "and the hold runs before the resume, so the fraction is what the tween starts from",
            effect.indexOf("sweepProgress.stop()") < effect.indexOf("(1f - sweepProgress.value)")
        )
    }

    // ── the cycle's hold is independent of all of this ────────────────────

    @Test
    fun `the page cycle's pill hold is untouched and reads none of the sweep's inputs`() {
        val cycle = cycleEffect()
        assertTrue("the cycle effect exists", cycle.isNotEmpty())
        assertTrue(
            "the page still does not advance under a focused pill on the TV",
            cycle.contains("if (pillsHoldCycle) return@LaunchedEffect")
        )
        assertTrue(
            "through its own derived value, not the sweep's",
            mobile.contains("val pillsHoldCycle = holdCycleWhilePillsFocused && pillsFocused")
        )
        assertTrue(
            "and the phone still opts out of that hold",
            mobile.contains("holdCycleWhilePillsFocused: Boolean = true") &&
                mobile.contains("holdCycleWhilePillsFocused = false")
        )
        assertFalse(
            "the timer reads none of the sweep's inputs, so a rail walk cannot re-arm or " +
                "freeze the page cycle",
            cycle.contains("sweepHoldsForFocus") ||
                cycle.contains("billboardOnScreen") ||
                cycle.contains("sweepPaused")
        )
    }

    // ── everything that was already there stays ───────────────────────────

    @Test
    fun `the existing pause inputs and the sweep's pace are unchanged`() {
        val sweep = sweepList()
        assertTrue("reduced motion still holds it", mobile.contains("val sweepPaused = reducedMotion ||"))
        listOf(
            "backgrounded",
            "scrollHeld()",
            "pagerState.isScrollInProgress",
            "playbackPageKey != null",
            "playback != null",
            "autoTrailerArmed",
            "trailerSuppressed"
        ).forEach { rung ->
            assertTrue("the sweep still pauses on $rung", sweep.contains(rung))
        }
        assertTrue(
            "the pace is still the fixed ten seconds",
            sweepEffect().contains("BILLBOARD_KEN_BURNS_MANUAL_MS")
        )
        assertTrue(
            "and the look is still the parity rule from BillboardRules",
            mobile.contains("remember(index) { billboardSweep(index) }")
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
