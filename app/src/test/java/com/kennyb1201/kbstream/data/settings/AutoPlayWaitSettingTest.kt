package com.kennyb1201.kbstream.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-play wait used to be three fixed choices - 4 / 10 / 20 seconds - and
 * is now any whole number of seconds in a range.
 *
 * The range is the whole guard: the value is stored, so a downgrade, a corrupt
 * blob or a hand-edited pref can hand it any int at all, and both ends matter.
 * Too high and the press of Play waits minutes for a source; zero or negative
 * and the grace disappears, so auto-play starts on the very first answer it
 * sees - the behaviour the grace exists to improve on.
 *
 * [AppPreferences.clampAutoPlayWaitSec] is the rule, split out of the read and
 * write so it can be pinned without a Context.
 */
class AutoPlayWaitSettingTest {

    @Test
    fun `the range is one to thirty seconds`() {
        assertEquals(1, AppPreferences.AUTO_PLAY_WAIT_SEC_MIN)
        assertEquals(30, AppPreferences.AUTO_PLAY_WAIT_SEC_MAX)
    }

    @Test
    fun `the default is inside the range`() {
        val default = AppPreferences.AUTO_PLAY_WAIT_SEC_DEFAULT
        assertEquals(
            "the default has to be a legal value, or a fresh install stores something " +
                "the read would clamp behind the viewer's back",
            default,
            AppPreferences.clampAutoPlayWaitSec(default)
        )
        // Ten was the middle of the old three chips, and it stays the middle of
        // the range (1..30 has no exact middle; ten is the old behaviour kept).
        assertEquals(10, default)
    }

    @Test
    fun `any value inside the range is obeyed, not snapped to a preset`() {
        // The point of the change: 7 and 23 are choices, not rounding errors.
        listOf(1, 2, 4, 7, 10, 12, 20, 23, 29, 30).forEach { seconds ->
            assertEquals(
                "${seconds}s must be stored as itself",
                seconds,
                AppPreferences.clampAutoPlayWaitSec(seconds)
            )
        }
    }

    @Test
    fun `values outside the range are clamped to the near end`() {
        assertEquals(
            "a zero or negative must not disable the grace",
            AppPreferences.AUTO_PLAY_WAIT_SEC_MIN,
            AppPreferences.clampAutoPlayWaitSec(0)
        )
        assertEquals(
            AppPreferences.AUTO_PLAY_WAIT_SEC_MIN,
            AppPreferences.clampAutoPlayWaitSec(-30)
        )
        assertEquals(
            "a stored value from a build with a wider range must not become a minutes-long wait",
            AppPreferences.AUTO_PLAY_WAIT_SEC_MAX,
            AppPreferences.clampAutoPlayWaitSec(31)
        )
        assertEquals(
            AppPreferences.AUTO_PLAY_WAIT_SEC_MAX,
            AppPreferences.clampAutoPlayWaitSec(600)
        )
    }

    @Test
    fun `the quick-pick chips are legal values inside the range`() {
        // They are conveniences beside the stepper, not the vocabulary: each one
        // has to be a value the clamp would keep, or the chip would write
        // something the read changes again.
        val picks = AppPreferences.AUTO_PLAY_WAIT_SEC_QUICK_PICKS
        assertTrue("the chips are the three old presets", picks == listOf(4, 10, 20))
        picks.forEach { seconds ->
            assertEquals(seconds, AppPreferences.clampAutoPlayWaitSec(seconds))
        }
        assertTrue(
            "and the default is one of them, so a fresh install shows a selected chip",
            AppPreferences.AUTO_PLAY_WAIT_SEC_DEFAULT in picks
        )
    }
}
