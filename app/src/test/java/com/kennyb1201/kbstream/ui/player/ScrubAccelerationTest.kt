package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold-to-scrub ramp: a held LEFT/RIGHT seeks faster the longer it is held,
 * and every engine reads the same numbers.
 *
 * What the user asked for is a rate, so the things worth pinning are that the
 * ramp actually accelerates, that it is bounded (a scrub must not be able to
 * fling the playhead past the file's end faster than the release can land), and
 * that reaching the top does not take so long that "make it go faster" is only
 * true after the viewer has stopped caring.
 */
class ScrubAccelerationTest {

    @Test
    fun `a hold starts above the quick-press step and accelerates from there`() {
        val first = ScrubAcceleration.stepFor(0L)
        assertEquals(ScrubAcceleration.START_STEP_MS, first)
        assertTrue(
            "the scrub must begin larger than the ten-second quick press",
            first > 10_000L
        )
        assertTrue(
            "the second tick must be larger than the first",
            ScrubAcceleration.stepFor(ScrubAcceleration.TICK_MS) > first
        )
    }

    @Test
    fun `the ramp never shrinks as the hold goes on`() {
        var previous = 0L
        var elapsed = 0L
        while (elapsed <= ScrubAcceleration.RAMP_MS * 3) {
            val step = ScrubAcceleration.stepFor(elapsed)
            assertTrue(
                "step dropped at ${elapsed}ms: $previous -> $step",
                step >= previous
            )
            previous = step
            elapsed += ScrubAcceleration.TICK_MS
        }
    }

    @Test
    fun `the step tops out at the cap and stays there`() {
        assertEquals(ScrubAcceleration.MAX_STEP_MS, ScrubAcceleration.stepFor(ScrubAcceleration.RAMP_MS))
        assertEquals(
            ScrubAcceleration.MAX_STEP_MS,
            ScrubAcceleration.stepFor(ScrubAcceleration.RAMP_MS * 10)
        )
        assertEquals(
            ScrubAcceleration.MAX_STEP_MS,
            ScrubAcceleration.stepFor(Long.MAX_VALUE)
        )
        // Never above the cap on the way there either.
        var elapsed = 0L
        while (elapsed <= ScrubAcceleration.RAMP_MS) {
            assertTrue(ScrubAcceleration.stepFor(elapsed) <= ScrubAcceleration.MAX_STEP_MS)
            elapsed += 1L
        }
    }

    @Test
    fun `the top of the ramp is reached within a second of holding`() {
        assertEquals(
            ScrubAcceleration.MAX_STEP_MS,
            ScrubAcceleration.stepFor(1_000L)
        )
        assertTrue(ScrubAcceleration.RAMP_MS <= 1_000L)
    }

    @Test
    fun `the ramp covers a feature in a hold worth taking`() {
        // Two hours, advanced a tick at a time, from the start of the hold.
        val featureMs = 2L * 60L * 60L * 1_000L
        var covered = 0L
        var elapsed = ScrubAcceleration.HOLD_START_MS
        val giveUpAfterMs = 30_000L
        while (covered < featureMs && elapsed < giveUpAfterMs) {
            covered += ScrubAcceleration.stepFor(elapsed - ScrubAcceleration.HOLD_START_MS)
            elapsed += ScrubAcceleration.TICK_MS
        }
        assertTrue(
            "a two-hour film should be scrubbable end to end in under 30s of holding " +
                "(covered ${covered}ms in ${elapsed}ms)",
            covered >= featureMs
        )
    }

    @Test
    fun `the quick-press window is short enough that the scrub is not a surprise`() {
        assertTrue(ScrubAcceleration.HOLD_START_MS <= 400L)
        assertTrue(ScrubAcceleration.TICK_MS <= 100L)
    }
}
