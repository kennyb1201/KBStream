package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's sweep pace, pinned (spec 2026-11-13).
 *
 * The field report was "the billboard is stuttery compared to the screensaver".
 * The animation SHAPE was never the difference - both run one `Animatable(0f)`
 * through a `tween(LinearEasing)` and read it in the draw phase - so a build
 * cannot see this defect and a source-reading test can. What has to hold:
 *
 *  - the sweep takes the fixed slow duration, never the cycle interval (F1), so
 *    a 3 s and a 15 s cycle move at the same visible pace;
 *  - a pause HOLDS the fraction it reached and resumes the remaining portion of
 *    that same fixed duration, and only a page change snaps (F2) - the pause
 *    flips twice per page turn (`isScrollInProgress`), and re-keying the effect
 *    on them is what restarted the tween and produced the judder;
 *  - nothing but the sweep effect writes the sweep's Animatable (F3): the
 *    content fade-in and the chrome have their own clocks and must never
 *    cancel, snap or restart it.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../BillboardSweepPaceContractTest.kt, then JUnitCore
 * with -Duser.dir at the repository root so the source reader finds src/main.
 */
class BillboardSweepPaceContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    /** Every index [token] appears at, so a write can be placed in the file. */
    private fun positionsOf(text: String, token: String): List<Int> {
        val out = ArrayList<Int>()
        var at = text.indexOf(token)
        while (at >= 0) {
            out.add(at)
            at = text.indexOf(token, at + 1)
        }
        return out
    }

    private fun mobile(): String = source(MOBILE)

    /** The sweep effect: from its key list to the entrance effect that follows. */
    private fun sweepEffect(): String = between(
        mobile(),
        SWEEP_EFFECT_HEADER,
        ENTRANCE_EFFECT_HEADER
    )

    // ── F1: the pace is fixed, never the cycle ─────────────────────────────

    @Test
    fun `the sweep runs for the fixed slow duration, whatever the cycle is`() {
        val effect = sweepEffect()
        assertTrue("the sweep effect must exist", effect.isNotEmpty())
        assertTrue(
            "the duration is the fixed ten seconds - the same value for a 3 s and a 15 s cycle",
            effect.contains("BILLBOARD_KEN_BURNS_MANUAL_MS")
        )
        assertFalse(
            "and it is not derived from the cycle interval, which decides only when the next title arrives",
            effect.contains("cycleMs")
        )
        assertTrue(
            "the rule is stated where the value lives, so the name cannot be read as 'manual mode only'",
            source(RULES).contains("How long the sweep runs - ALWAYS, whatever the cycle interval is")
        )
    }

    @Test
    fun `a page change is the only thing that snaps the sweep`() {
        val effect = sweepEffect()
        val pageBranch = between(effect, "if (sweptPage != page) {", "}")
        assertTrue(
            "the new page starts its own sweep from the start",
            pageBranch.contains("sweepProgress.snapTo(0f)")
        )
        assertEquals(
            "the only other snap is reduced motion pinning the move at its end - a pause never snaps",
            2,
            positionsOf(effect, "sweepProgress.snapTo(").size
        )
    }

    // ── F2: a pause holds the fraction; the effect is not re-keyed ─────────

    @Test
    fun `a pause holds the fraction and resumes the remaining part of it`() {
        val effect = sweepEffect()
        assertTrue(
            "the effect is keyed on the PAGE and the motion setting only - a pause flip must not re-enter it",
            mobile().contains(SWEEP_EFFECT_HEADER)
        )
        assertFalse(
            "which is what the old key list did, and what restarted the tween twice per turn",
            mobile().contains("LaunchedEffect(pagerState.settledPage, sweepPaused, cycleMs, reducedMotion)")
        )
        assertTrue(
            "the pause is observed INSIDE the effect instead",
            effect.contains("val sweepPausedNow = rememberUpdatedState(sweepPaused)") ||
                effect.contains("sweepPausedNow.value")
        )
        assertTrue(
            "and a new value cancels the running tween rather than queueing behind it",
            effect.contains("snapshotFlow { sweepPausedNow.value }.collectLatest")
        )
        assertTrue(
            "a pause STOPS the Animatable, which keeps the fraction it reached",
            effect.contains("if (paused) {") && effect.contains("sweepProgress.stop()")
        )
        assertTrue(
            "the resume is the REMAINING portion of the same fixed duration, from the held fraction",
            effect.contains("(1f - sweepProgress.value) * BILLBOARD_KEN_BURNS_MANUAL_MS")
        )
        assertEquals(
            "one hold, named once - no alternate stop path that could snap it",
            1,
            positionsOf(effect, "sweepProgress.stop()").size
        )
    }

    // ── F3: nothing else writes the sweep ──────────────────────────────────

    @Test
    fun `the reveal and the chrome never write the sweep`() {
        val whole = mobile()
        val start = whole.indexOf(SWEEP_EFFECT_HEADER)
        val end = whole.indexOf(ENTRANCE_EFFECT_HEADER)
        assertTrue("the sweep effect must be locatable", start >= 0 && end > start)

        listOf(
            "sweepProgress.snapTo(",
            "sweepProgress.stop()",
            "sweepProgress.animateTo("
        ).forEach { token ->
            positionsOf(whole, token).forEach { at ->
                assertTrue(
                    "$token is written outside the sweep effect (offset $at) - the " +
                        "content fade-in or the chrome must never cancel, snap or restart the sweep",
                    at > start && at < end
                )
            }
        }

        val entrance = between(whole, ENTRANCE_EFFECT_HEADER, "// The pills, handed to")
        assertTrue("the entrance effect must exist", entrance.isNotEmpty())
        assertFalse(
            "the content reveal has its own Animatable and must never touch the sweep's",
            entrance.contains("sweepProgress")
        )
        assertTrue(
            "the sweep is still read in the DRAW phase only, so the pacing change costs no " +
                "recomposition per frame",
            whole.contains("val fraction = backdropProgress().coerceIn(0f, 1f)") &&
                whole.contains("backdropProgress = {") &&
                whole.contains("if (pagerState.currentPage == index) sweepProgress.value else 0f")
        )
    }

    private companion object {
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/home/BillboardRules.kt"
        const val SWEEP_EFFECT_HEADER = "LaunchedEffect(pagerState.settledPage, reducedMotion) {"
        const val ENTRANCE_EFFECT_HEADER = "// One entrance per page entry"
    }
}
