package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-skip is the one thing that moves the playhead without a press, so the
 * rules are pinned here: what may be skipped, what may never be, and where a
 * skip lands.
 */
class AutoSkipRulesTest {

    private val bothOff = AutoSkipRules.Settings(skipIntros = false, skipCredits = false)
    private val introsOn = AutoSkipRules.Settings(skipIntros = true, skipCredits = false)
    private val creditsOn = AutoSkipRules.Settings(skipIntros = false, skipCredits = true)

    private fun stamp(
        type: IntroDbMarkerType,
        startMs: Long,
        endMs: Long,
        confidence: Double? = null
    ) = IntroDbStamp(startMs, endMs, type, confidence)

    // ── What may be skipped ───────────────────────────────────────────────

    @Test
    fun `nothing is skipped while both prefs are off`() {
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 0, 60_000), bothOff))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Outro, 60_000, 90_000), bothOff))
    }

    @Test
    fun `intros and recaps need the intro pref`() {
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 5_000, 45_000), introsOn))
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Recap, 0, 30_000), introsOn))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 5_000, 45_000), creditsOn))
    }

    @Test
    fun `credits need the credits pref`() {
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Outro, 2_700_000, 2_800_000), creditsOn))
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Credits, 2_700_000, 2_800_000), creditsOn))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Outro, 2_700_000, 2_800_000), introsOn))
    }

    @Test
    fun `post-credits scenes and previews are never skipped`() {
        // The scene after the credits is the content; skipping it with no press
        // would be the app deciding the viewer does not want to see it.
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.PostCredits, 7_540_000, 7_580_000), creditsOn))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.PostCredits, 7_540_000, 7_580_000), introsOn))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Preview, 2_800_000, 2_850_000), creditsOn))
    }

    @Test
    fun `an implausibly long segment is never auto-skipped`() {
        // A mis-tagged row covering half the runtime: the button may still offer
        // it, the app must not jump there on its own.
        val halfTheMovie = stamp(IntroDbMarkerType.Intro, 0, 45 * 60 * 1000L)
        assertFalse(AutoSkipRules.isSkippableSegment(halfTheMovie))
        assertFalse(AutoSkipRules.shouldAutoSkip(halfTheMovie, introsOn))
        assertTrue(AutoSkipRules.isSkippableSegment(stamp(IntroDbMarkerType.Intro, 0, 90_000)))
    }

    @Test
    fun `low confidence needs a press`() {
        val guess = stamp(IntroDbMarkerType.Intro, 0, 60_000, confidence = 0.2)
        val trusted = stamp(IntroDbMarkerType.Intro, 0, 60_000, confidence = 1.0)
        assertFalse(AutoSkipRules.shouldAutoSkip(guess, introsOn))
        assertTrue(AutoSkipRules.shouldAutoSkip(trusted, introsOn))
        // Not stated is treated as fine: most rows carry no confidence field.
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 0, 60_000), introsOn))
    }

    @Test
    fun `an empty or backwards window is rejected`() {
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 60_000, 60_000), introsOn))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 60_000, 30_000), introsOn))
    }

    // ── Where a skip lands ───────────────────────────────────────────────

    @Test
    fun `a plain intro skips to its own end`() {
        val intro = stamp(IntroDbMarkerType.Intro, 30_000, 105_000)
        assertEquals(105_000L, AutoSkipRules.targetMs(intro, listOf(intro), 45 * 60 * 1000L))
    }

    @Test
    fun `the target never exceeds the known runtime`() {
        val stamps = listOf(stamp(IntroDbMarkerType.Outro, 2_700_000, 2_900_000))
        assertEquals(2_800_000L, AutoSkipRules.targetMs(stamps.first(), stamps, 2_800_000L))
    }

    @Test
    fun `skipping credits stops at the post-credits scene`() {
        val outro = stamp(IntroDbMarkerType.Outro, 7_038_000, 7_540_000)
        val scene = stamp(IntroDbMarkerType.PostCredits, 7_540_000, 7_580_000)
        // Without a scene: credits end, i.e. the file ends.
        assertEquals(7_540_000L, AutoSkipRules.targetMs(outro, listOf(outro), 7_560_000L))
        // With one: land on it rather than past it.
        assertEquals(7_540_000L, AutoSkipRules.targetMs(outro, listOf(outro, scene), 7_600_000L))
    }

    @Test
    fun `a scene before the credits is ignored`() {
        val outro = stamp(IntroDbMarkerType.Outro, 7_000_000, 7_200_000)
        val earlierScene = stamp(IntroDbMarkerType.PostCredits, 6_500_000, 6_520_000)
        assertEquals(7_200_000L, AutoSkipRules.targetMs(outro, listOf(outro, earlierScene), 7_400_000L))
    }

    // ── Identity ─────────────────────────────────────────────────────────

    // ── The external engine's hand-off skip ──────────────────────────────

    @Test
    fun `an intro at the top of an episode is skipped before the hand-off`() {
        val intro = stamp(IntroDbMarkerType.Intro, 8_000, 70_000)
        val target = AutoSkipRules.handoffSkipTarget(listOf(intro), 0L, introsOn)
        assertEquals(intro, target)
    }

    @Test
    fun `a resume inside a recap skips to the end of it`() {
        val recap = stamp(IntroDbMarkerType.Recap, 0, 90_000)
        val target = AutoSkipRules.handoffSkipTarget(listOf(recap), 30_000L, introsOn)
        assertEquals(recap, target)
        assertEquals(90_000L, AutoSkipRules.targetMs(recap, listOf(recap), 0L))
    }

    @Test
    fun `the hand-off skips nothing while both prefs are off`() {
        val stamps = listOf(stamp(IntroDbMarkerType.Intro, 0, 60_000))
        assertNull(AutoSkipRules.handoffSkipTarget(stamps, 0L, bothOff))
    }

    @Test
    fun `the hand-off leaves a segment it is nowhere near`() {
        // The credits are an hour away and the resume point is the start of the
        // title: seeking there would skip the whole episode.
        val credits = stamp(IntroDbMarkerType.Credits, 4_400_000, 4_460_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(credits), 0L, creditsOn))
        // ...while resuming inside them is exactly when they are skipped.
        assertEquals(credits, AutoSkipRules.handoffSkipTarget(listOf(credits), 4_410_000L, creditsOn))
    }

    @Test
    fun `a cold open longer than the window is not skipped`() {
        // An intro five minutes in sits behind a cold open that is part of the
        // episode, so the hand-off must play it rather than jump over it.
        val intro = stamp(IntroDbMarkerType.Intro, 300_000, 360_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(intro), 0L, introsOn))
    }

    @Test
    fun `a segment already behind the resume point is left alone`() {
        val intro = stamp(IntroDbMarkerType.Intro, 8_000, 70_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(intro), 120_000L, introsOn))
    }

    @Test
    fun `the earliest segment wins so a recap does not shadow the intro`() {
        val recap = stamp(IntroDbMarkerType.Recap, 0, 30_000)
        val intro = stamp(IntroDbMarkerType.Intro, 30_000, 95_000)
        assertEquals(recap, AutoSkipRules.handoffSkipTarget(listOf(intro, recap), 0L, introsOn))
    }

    @Test
    fun `the hand-off never skips a post-credits scene or a preview`() {
        val scene = stamp(IntroDbMarkerType.PostCredits, 0, 40_000)
        val preview = stamp(IntroDbMarkerType.Preview, 0, 40_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(scene, preview), 0L, creditsOn))
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(scene, preview), 0L, introsOn))
    }

    @Test
    fun `a segment starting further ahead than the window is left for the button`() {
        // 20s in: skipped, because handing off just before an intro is exactly
        // what auto-skip is for. 45s in: further than the window, so whatever
        // comes before it is played rather than jumped over.
        val nearIntro = stamp(IntroDbMarkerType.Intro, 20_000, 80_000)
        val farIntro = stamp(IntroDbMarkerType.Intro, 45_000, 105_000)
        assertEquals(nearIntro, AutoSkipRules.handoffSkipTarget(listOf(nearIntro), 0L, introsOn))
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(farIntro), 0L, introsOn))
    }

    @Test
    fun `a segment keeps one key for the whole session`() {
        val a = stamp(IntroDbMarkerType.Intro, 30_000, 105_000)
        val sameShape = stamp(IntroDbMarkerType.Intro, 30_000, 105_000)
        val other = stamp(IntroDbMarkerType.Recap, 30_000, 105_000)
        assertEquals(AutoSkipRules.key(a), AutoSkipRules.key(sameShape))
        assertNotEquals(AutoSkipRules.key(a), AutoSkipRules.key(other))
    }
}
