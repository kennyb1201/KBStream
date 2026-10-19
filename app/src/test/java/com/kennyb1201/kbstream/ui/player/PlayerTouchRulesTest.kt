package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone player's touch arithmetic, one rule at a time.
 *
 * These are the numbers a finger produces: which half a vertical drag started
 * in, how far a drag has to travel before it is a drag at all, what a
 * full-width swipe is worth in film time, and the play state a seek is allowed
 * to leave behind. They are unit tests rather than a device script because the
 * rules are pure - the gesture wiring ([PlayerTouchGestures]) does nothing but
 * feed them.
 */
class PlayerTouchRulesTest {

    @Test
    fun `a vertical drag belongs to the half it started in`() {
        assertEquals(PlayerTouchRules.Zone.LEFT, PlayerTouchRules.zoneFor(0f, 1000))
        assertEquals(PlayerTouchRules.Zone.LEFT, PlayerTouchRules.zoneFor(499f, 1000))
        assertEquals(PlayerTouchRules.Zone.RIGHT, PlayerTouchRules.zoneFor(500f, 1000))
        assertEquals(PlayerTouchRules.Zone.RIGHT, PlayerTouchRules.zoneFor(999f, 1000))
    }

    @Test
    fun `a drag inside the slop is not a drag yet`() {
        assertNull(PlayerTouchRules.axisFor(2f, 3f, slop = 12f))
        assertEquals(
            PlayerTouchRules.Axis.SEEK,
            PlayerTouchRules.axisFor(40f, 3f, slop = 12f)
        )
        assertEquals(
            PlayerTouchRules.Axis.VERTICAL,
            PlayerTouchRules.axisFor(3f, 40f, slop = 12f)
        )
    }

    @Test
    fun `a diagonal past the slop is a seek`() {
        // A thumb that wobbles while it reaches for the scrub must not turn the
        // gesture into a volume change halfway through.
        assertEquals(
            PlayerTouchRules.Axis.SEEK,
            PlayerTouchRules.axisFor(30f, 20f, slop = 12f)
        )
    }

    @Test
    fun `a full-width drag is worth between 30 and 120 seconds`() {
        assertEquals(30_000L, PlayerTouchRules.seekSpanMs(5_000L))
        assertEquals(60_000L, PlayerTouchRules.seekSpanMs(60_000L))
        assertEquals(120_000L, PlayerTouchRules.seekSpanMs(7_200_000L))
    }

    @Test
    fun `a seek lands inside the film`() {
        val position = 1_000_000L
        val duration = 3_600_000L

        assertEquals(
            position + 30_000L,
            PlayerTouchRules.seekTarget(position, duration, 0.25f)
        )
        assertEquals(
            position - 30_000L,
            PlayerTouchRules.seekTarget(position, duration, -0.25f)
        )
        assertEquals(
            "a full-width drag is the whole span, not the whole film",
            position + 120_000L,
            PlayerTouchRules.seekTarget(position, duration, 1f)
        )
        assertEquals(
            "a drag that would pass the end lands on the end",
            1_050_000L,
            PlayerTouchRules.seekTarget(1_000_000L, 1_050_000L, 1f)
        )
        assertEquals(
            "and one that would pass the start lands on zero",
            0L,
            PlayerTouchRules.seekTarget(100_000L, duration, -1f)
        )
    }

    @Test
    fun `there is nothing to seek in a live stream or a still finger`() {
        assertNull(PlayerTouchRules.seekTarget(1_000L, 0L, 0.5f))
        assertNull(PlayerTouchRules.seekTarget(1_000L, -1L, 0.5f))
        assertNull(PlayerTouchRules.seekTarget(1_000L, 60_000L, 0.001f))
    }

    @Test
    fun `the hint names the move and where it lands`() {
        assertEquals(
            "+0:30  16:40",
            PlayerTouchRules.seekHint(30_000L, 1_000_000L)
        )
        assertEquals("-0:30  0:10", PlayerTouchRules.seekHint(-30_000L, 10_000L))
    }

    @Test
    fun `up is brighter and louder`() {
        // dy is positive downwards, so a negative fraction is a finger moving up.
        assertEquals(0.7f, PlayerTouchRules.brightnessAfter(0.5f, -0.2f), 0.0001f)
        assertEquals(0.3f, PlayerTouchRules.brightnessAfter(0.5f, 0.2f), 0.0001f)
        assertEquals(0.8f, PlayerTouchRules.volumeAfter(0.6f, -0.2f), 0.0001f)
        assertEquals(0.4f, PlayerTouchRules.volumeAfter(0.6f, 0.2f), 0.0001f)
    }

    @Test
    fun `brightness stops just short of black and volume at its ends`() {
        assertEquals(
            PlayerTouchRules.MIN_BRIGHTNESS,
            PlayerTouchRules.brightnessAfter(0.5f, 1f),
            0.0001f
        )
        assertEquals(1f, PlayerTouchRules.brightnessAfter(0.5f, -5f), 0.0001f)
        assertEquals(0f, PlayerTouchRules.volumeAfter(0.2f, 5f), 0.0001f)
        assertEquals(1f, PlayerTouchRules.volumeAfter(0.2f, -5f), 0.0001f)
    }

    @Test
    fun `a level hint reads as a percentage`() {
        assertEquals("Volume 40%", PlayerTouchRules.levelHint("Volume", 0.404f))
        assertEquals("Brightness 100%", PlayerTouchRules.levelHint("Brightness", 1f))
    }

    @Test
    fun `a seek leaves the play state it found`() {
        assertTrue(PlayerTouchRules.playAfterSeek(true))
        assertFalse(PlayerTouchRules.playAfterSeek(false))
    }

    @Test
    fun `the portrait band keeps the picture at sixteen by nine`() {
        assertEquals(607, PlayerTouchRules.portraitBandHeightPx(1080, 2400))
        assertEquals(
            "a band taller than the screen is the screen",
            400,
            PlayerTouchRules.portraitBandHeightPx(1080, 400)
        )
        assertEquals(0, PlayerTouchRules.portraitBandHeightPx(0, 0))
    }

    @Test
    fun `the portrait stage is phones upright only`() {
        assertTrue(PlayerTouchRules.portraitStageApplies(isTv = false, portrait = true))
        assertFalse(PlayerTouchRules.portraitStageApplies(isTv = false, portrait = false))
        assertFalse(PlayerTouchRules.portraitStageApplies(isTv = true, portrait = true))
    }

    @Test
    fun `clock formatting`() {
        assertEquals("0:00", PlayerTouchRules.clock(0L))
        assertEquals("1:05", PlayerTouchRules.clock(65_000L))
        assertEquals("1:02:05", PlayerTouchRules.clock(3_725_000L))
    }
}
