package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's Ken Burns motion, as numbers (spec 2.6).
 *
 * The screen only interpolates a 0..1 value through whatever [billboardSweep]
 * returns, so every decision the spec makes about the move - how far it zooms,
 * how far it pans, how long it takes, and that two consecutive pages never
 * repeat the same move - is decided here and can be checked without a
 * composition or a frame clock.
 *
 * [billboardSegmentFill] is here for the same reason: "completed segments stay
 * filled, upcoming ones stay dimmed, the current one fills over the dwell - and
 * in Manual only it just shows position" is arithmetic, not layout.
 */
class BillboardSweepRulesTest {

    private fun sweep(index: Int, cycleMs: Long = 6_000L) = billboardSweep(index, cycleMs)

    // ── duration: the move lands as the page would advance ───────────────

    @Test
    fun `the sweep takes exactly the page's dwell`() {
        listOf(3_000L, 6_000L, 10_000L, 15_000L).forEach { cycle ->
            assertEquals(
                "a ${cycle}ms page must finish its move in ${cycle}ms",
                cycle,
                sweep(0, cycle).durationMs
            )
            assertEquals(
                "and so must an odd page, whose move is the reverse",
                cycle,
                sweep(1, cycle).durationMs
            )
        }
    }

    @Test
    fun `manual only has no dwell, so it sweeps for a fixed ten seconds`() {
        // 0 means "Manual only" (see BILLBOARD_CYCLE_OPTIONS_MS): the page never
        // advances, so the move still has to go somewhere and stop.
        assertEquals(10_000L, sweep(0, 0L).durationMs)
        assertEquals(10_000L, sweep(1, 0L).durationMs)
        assertEquals(BILLBOARD_KEN_BURNS_MANUAL_MS, sweep(4, 0L).durationMs)
    }

    @Test
    fun `a negative hold is treated as manual rather than as a negative time`() {
        assertEquals(BILLBOARD_KEN_BURNS_MANUAL_MS, sweep(0, -5L).durationMs)
    }

    // ── direction: parity alternates, and the move stays inside the caps ──

    @Test
    fun `even pages zoom in and odd pages zoom out`() {
        val even = sweep(0)
        val odd = sweep(1)

        assertEquals(1f, even.startScale, 0.0001f)
        assertEquals(BILLBOARD_KEN_BURNS_MAX_SCALE, even.endScale, 0.0001f)

        assertEquals(BILLBOARD_KEN_BURNS_MAX_SCALE, odd.startScale, 0.0001f)
        assertEquals(1f, odd.endScale, 0.0001f)
    }

    @Test
    fun `consecutive pages never repeat the same move`() {
        val first = sweep(0)
        val second = sweep(1)
        val third = sweep(2)

        // The pan is the tell: same zoom direction would still be a different
        // move, but the spec names both, so both have to alternate.
        assertTrue(
            "the pan must reverse between page 0 and page 1",
            first.endPanFraction * second.endPanFraction < 0f
        )
        assertTrue(
            "page 2 must repeat page 0's move, not page 1's",
            first == third && second != third
        )
    }

    @Test
    fun `the pan stays at or under two percent of the width`() {
        (0..9).forEach { index ->
            val sweep = sweep(index)
            assertTrue(
                "page $index pans ${sweep.startPanFraction} -> ${sweep.endPanFraction}, " +
                    "outside the 2% the spec allows",
                kotlin.math.abs(sweep.startPanFraction) <= BILLBOARD_KEN_BURNS_PAN_FRACTION &&
                    kotlin.math.abs(sweep.endPanFraction) <= BILLBOARD_KEN_BURNS_PAN_FRACTION
            )
        }
    }

    @Test
    fun `the zoom stays at or under the ten percent cap`() {
        (0..9).forEach { index ->
            val sweep = sweep(index)
            assertTrue(
                "page $index scales to ${sweep.startScale}/${sweep.endScale}, over the cap",
                sweep.startScale in 1f..BILLBOARD_KEN_BURNS_MAX_SCALE &&
                    sweep.endScale in 1f..BILLBOARD_KEN_BURNS_MAX_SCALE
            )
        }
    }

    @Test
    fun `the two parities are exact mirror images`() {
        (0..9 step 2).forEach { evenIndex ->
            val even = sweep(evenIndex)
            val odd = sweep(evenIndex + 1)

            // An odd page starts where the even page before it ended, and ends
            // where that page started: the move is the SAME sweep run backwards,
            // so no page repeats another's direction and no page jumps as it
            // comes in.
            assertEquals(even.endScale, odd.startScale, 0.0001f)
            assertEquals(even.startScale, odd.endScale, 0.0001f)
            assertEquals(even.endPanFraction, -odd.endPanFraction, 0.0001f)
            // Both begin centred, so the pan grows out of the frame's middle.
            assertEquals(0f, even.startPanFraction, 0.0001f)
            assertEquals(0f, odd.startPanFraction, 0.0001f)
        }
    }

    // ── the progress bar's segments ─────────────────────────────────────

    @Test
    fun `completed segments are full and upcoming ones are empty`() {
        assertEquals(1f, billboardSegmentFill(segment = 0, current = 2, progress = 0.4f, animated = true))
        assertEquals(0f, billboardSegmentFill(segment = 4, current = 2, progress = 0.4f, animated = true))
    }

    @Test
    fun `the current segment fills with the same value the sweep reads`() {
        listOf(0f, 0.25f, 0.5f, 1f).forEach { progress ->
            assertEquals(
                progress,
                billboardSegmentFill(segment = 3, current = 3, progress = progress, animated = true),
                0.0001f
            )
        }
    }

    @Test
    fun `the current segment is clamped to its track`() {
        assertEquals(
            1f,
            billboardSegmentFill(segment = 1, current = 1, progress = 1.4f, animated = true),
            0.0001f
        )
        assertEquals(
            0f,
            billboardSegmentFill(segment = 1, current = 1, progress = -0.2f, animated = true),
            0.0001f
        )
    }

    @Test
    fun `manual only and animations off show position instead of filling`() {
        // No animation to show: the current segment reads as "you are here",
        // which is a full segment, not an empty one.
        assertEquals(
            1f,
            billboardSegmentFill(segment = 2, current = 2, progress = 0f, animated = false)
        )
        assertEquals(
            1f,
            billboardSegmentFill(segment = 2, current = 2, progress = 0.7f, animated = false)
        )
    }
}
