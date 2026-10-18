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
 *
 * Every decision takes the runtime, because a segment is allowed to say only
 * where it starts (TheIntroDB v4 serves credits as
 * `{"start_ms":8300000,"end_ms":null}`) and "where it ends" then means "where
 * the file ends". [runtime] stands in for a known duration below; the
 * open-ended tests at the bottom are the ones that care which value it is.
 */
class AutoSkipRulesTest {

    private val bothOff = AutoSkipRules.Settings(skipIntros = false, skipCredits = false)
    private val introsOn = AutoSkipRules.Settings(skipIntros = true, skipCredits = false)
    private val creditsOn = AutoSkipRules.Settings(skipIntros = false, skipCredits = true)

    /** A long episode: every closed segment below is well inside this. */
    private val runtime = 3 * 60 * 60 * 1000L

    private fun stamp(
        type: IntroDbMarkerType,
        startMs: Long,
        endMs: Long?,
        confidence: Double? = null
    ) = IntroDbStamp(startMs, endMs, type, confidence)

    // ── What may be skipped ───────────────────────────────────────────────

    @Test
    fun `nothing is skipped while both prefs are off`() {
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 0, 60_000), bothOff, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Outro, 60_000, 90_000), bothOff, runtime))
    }

    @Test
    fun `intros and recaps need the intro pref`() {
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 5_000, 45_000), introsOn, runtime))
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Recap, 0, 30_000), introsOn, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 5_000, 45_000), creditsOn, runtime))
    }

    @Test
    fun `credits need the credits pref`() {
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Outro, 2_700_000, 2_800_000), creditsOn, runtime))
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Credits, 2_700_000, 2_800_000), creditsOn, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Outro, 2_700_000, 2_800_000), introsOn, runtime))
    }

    @Test
    fun `post-credits scenes and previews are never skipped`() {
        // The scene after the credits is the content; skipping it with no press
        // would be the app deciding the viewer does not want to see it.
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.PostCredits, 7_540_000, 7_580_000), creditsOn, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.PostCredits, 7_540_000, 7_580_000), introsOn, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Preview, 2_800_000, 2_850_000), creditsOn, runtime))
    }

    @Test
    fun `an implausibly long segment is never auto-skipped`() {
        // A mis-tagged row covering half the runtime: the button may still offer
        // it, the app must not jump there on its own.
        val halfTheMovie = stamp(IntroDbMarkerType.Intro, 0, 45 * 60 * 1000L)
        assertFalse(AutoSkipRules.isSkippableSegment(halfTheMovie, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(halfTheMovie, introsOn, runtime))
        assertTrue(AutoSkipRules.isSkippableSegment(stamp(IntroDbMarkerType.Intro, 0, 90_000), runtime))
    }

    @Test
    fun `low confidence needs a press`() {
        val guess = stamp(IntroDbMarkerType.Intro, 0, 60_000, confidence = 0.2)
        val trusted = stamp(IntroDbMarkerType.Intro, 0, 60_000, confidence = 1.0)
        assertFalse(AutoSkipRules.shouldAutoSkip(guess, introsOn, runtime))
        assertTrue(AutoSkipRules.shouldAutoSkip(trusted, introsOn, runtime))
        // Not stated is treated as fine: most rows carry no confidence field.
        assertTrue(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 0, 60_000), introsOn, runtime))
    }

    @Test
    fun `an empty or backwards window is rejected`() {
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 60_000, 60_000), introsOn, runtime))
        assertFalse(AutoSkipRules.shouldAutoSkip(stamp(IntroDbMarkerType.Intro, 60_000, 30_000), introsOn, runtime))
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
        val target = AutoSkipRules.handoffSkipTarget(listOf(intro), 0L, introsOn, runtime)
        assertEquals(intro, target)
    }

    @Test
    fun `a resume inside a recap skips to the end of it`() {
        val recap = stamp(IntroDbMarkerType.Recap, 0, 90_000)
        val target = AutoSkipRules.handoffSkipTarget(listOf(recap), 30_000L, introsOn, runtime)
        assertEquals(recap, target)
        assertEquals(90_000L, AutoSkipRules.targetMs(recap, listOf(recap), 0L))
    }

    @Test
    fun `the hand-off skips nothing while both prefs are off`() {
        val stamps = listOf(stamp(IntroDbMarkerType.Intro, 0, 60_000))
        assertNull(AutoSkipRules.handoffSkipTarget(stamps, 0L, bothOff, runtime))
    }

    @Test
    fun `the hand-off leaves a segment it is nowhere near`() {
        // The credits are an hour away and the resume point is the start of the
        // title: seeking there would skip the whole episode.
        val credits = stamp(IntroDbMarkerType.Credits, 4_400_000, 4_460_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(credits), 0L, creditsOn, runtime))
        // ...while resuming inside them is exactly when they are skipped.
        assertEquals(credits, AutoSkipRules.handoffSkipTarget(listOf(credits), 4_410_000L, creditsOn, runtime))
    }

    @Test
    fun `a cold open longer than the window is not skipped`() {
        // An intro five minutes in sits behind a cold open that is part of the
        // episode, so the hand-off must play it rather than jump over it.
        val intro = stamp(IntroDbMarkerType.Intro, 300_000, 360_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(intro), 0L, introsOn, runtime))
    }

    @Test
    fun `a segment already behind the resume point is left alone`() {
        val intro = stamp(IntroDbMarkerType.Intro, 8_000, 70_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(intro), 120_000L, introsOn, runtime))
    }

    @Test
    fun `the earliest segment wins so a recap does not shadow the intro`() {
        val recap = stamp(IntroDbMarkerType.Recap, 0, 30_000)
        val intro = stamp(IntroDbMarkerType.Intro, 30_000, 95_000)
        assertEquals(recap, AutoSkipRules.handoffSkipTarget(listOf(intro, recap), 0L, introsOn, runtime))
    }

    @Test
    fun `the hand-off never skips a post-credits scene or a preview`() {
        val scene = stamp(IntroDbMarkerType.PostCredits, 0, 40_000)
        val preview = stamp(IntroDbMarkerType.Preview, 0, 40_000)
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(scene, preview), 0L, creditsOn, runtime))
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(scene, preview), 0L, introsOn, runtime))
    }

    @Test
    fun `a segment starting further ahead than the window is left for the button`() {
        // 20s in: skipped, because handing off just before an intro is exactly
        // what auto-skip is for. 45s in: further than the window, so whatever
        // comes before it is played rather than jumped over.
        val nearIntro = stamp(IntroDbMarkerType.Intro, 20_000, 80_000)
        val farIntro = stamp(IntroDbMarkerType.Intro, 45_000, 105_000)
        assertEquals(nearIntro, AutoSkipRules.handoffSkipTarget(listOf(nearIntro), 0L, introsOn, runtime))
        assertNull(AutoSkipRules.handoffSkipTarget(listOf(farIntro), 0L, introsOn, runtime))
    }

    @Test
    fun `a segment keeps one key for the whole session`() {
        val a = stamp(IntroDbMarkerType.Intro, 30_000, 105_000)
        val sameShape = stamp(IntroDbMarkerType.Intro, 30_000, 105_000)
        val other = stamp(IntroDbMarkerType.Recap, 30_000, 105_000)
        assertEquals(AutoSkipRules.key(a), AutoSkipRules.key(sameShape))
        assertNotEquals(AutoSkipRules.key(a), AutoSkipRules.key(other))
    }

    // ── Open-ended segments: a row that says where it starts only ─────────

    /** The runtime of a film whose credits row is `{"start_ms":8300000,"end_ms":null}`. */
    private val filmRuntime = 8_535_000L

    @Test
    fun `an open-ended credits row ends where the file does`() {
        val credits = stamp(IntroDbMarkerType.Credits, 8_300_000, null)

        assertEquals(filmRuntime, AutoSkipRules.segmentEndMs(credits, filmRuntime))
        assertTrue(AutoSkipRules.isSkippableSegment(credits, filmRuntime))
        assertTrue(AutoSkipRules.shouldAutoSkip(credits, creditsOn, filmRuntime))
        assertEquals(filmRuntime, AutoSkipRules.targetMs(credits, listOf(credits), filmRuntime))
    }

    @Test
    fun `an open-ended segment is offered once the playhead reaches it`() {
        val credits = stamp(IntroDbMarkerType.Credits, 8_300_000, null)

        assertFalse(AutoSkipRules.isInside(credits, 8_200_000))
        assertTrue(AutoSkipRules.isInside(credits, 8_300_000))
        // It never stops being "inside": there is no end to leave.
        assertTrue(AutoSkipRules.isInside(credits, 99_999_999))
    }

    @Test
    fun `an open end is not offered before the runtime is known`() {
        val credits = stamp(IntroDbMarkerType.Credits, 8_300_000, null)

        // A player still opening the file reports 0 (or C.TIME_UNSET): there is
        // no end to name, so nothing is offered and nothing is skipped.
        assertNull(AutoSkipRules.segmentEndMs(credits, 0L))
        assertFalse(AutoSkipRules.isSkippableSegment(credits, 0L))
        assertFalse(AutoSkipRules.shouldAutoSkip(credits, creditsOn, 0L))
        // And the target is a no-op rather than a wild seek.
        assertEquals(8_300_000L, AutoSkipRules.targetMs(credits, listOf(credits), 0L))
    }

    @Test
    fun `an open end still obeys the length ceiling`() {
        // A credits row starting at 0 with no end covers the entire file: that
        // is a bad row, not something to jump over.
        val wholeFile = stamp(IntroDbMarkerType.Credits, 0, null)

        assertFalse(AutoSkipRules.isSkippableSegment(wholeFile, 45 * 60 * 1000L))
        assertFalse(AutoSkipRules.shouldAutoSkip(wholeFile, creditsOn, 45 * 60 * 1000L))
    }

    @Test
    fun `an open-ended skip still stops at a post-credits scene`() {
        val credits = stamp(IntroDbMarkerType.Credits, 8_300_000, null)
        val scene = stamp(IntroDbMarkerType.PostCredits, 8_400_000, 8_420_000)

        assertEquals(
            8_400_000L,
            AutoSkipRules.targetMs(credits, listOf(credits, scene), filmRuntime)
        )
    }

    @Test
    fun `an open-ended segment keeps its identity`() {
        val a = stamp(IntroDbMarkerType.Credits, 8_300_000, null)
        val sameShape = stamp(IntroDbMarkerType.Credits, 8_300_000, null)
        val closed = stamp(IntroDbMarkerType.Credits, 8_300_000, 8_400_000)

        assertEquals(AutoSkipRules.key(a), AutoSkipRules.key(sameShape))
        assertNotEquals(AutoSkipRules.key(a), AutoSkipRules.key(closed))
    }

    @Test
    fun `the hand-off skips open-ended credits it is resuming into`() {
        val credits = stamp(IntroDbMarkerType.Credits, 8_300_000, null)
        val stamps = listOf(credits)

        assertEquals(credits, AutoSkipRules.handoffSkipTarget(stamps, 8_400_000L, creditsOn, filmRuntime))
        // Resuming at the start of the film is nowhere near them.
        assertNull(AutoSkipRules.handoffSkipTarget(stamps, 0L, creditsOn, filmRuntime))
        // ...and with no runtime there is no end to hand off past.
        assertNull(AutoSkipRules.handoffSkipTarget(stamps, 8_400_000L, creditsOn, 0L))
    }
}
