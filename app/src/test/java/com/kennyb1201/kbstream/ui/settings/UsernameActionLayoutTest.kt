package com.kennyb1201.kbstream.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The username row's fit arithmetic (spec 2026-11-14, amendment 3b).
 *
 * Both previous shapes are replayed here as numbers, because they were the same
 * failure twice: a row too narrow for two complete labels, handed the problem
 * with weights instead of with room.
 */
class UsernameActionLayoutTest {

    @Test
    fun `a roomy row keeps the two buttons side by side`() {
        // A TV panel: a fixed 560dp, with the padding TV ships.
        assertTrue(
            UsernameActionLayout.fitsSideBySide(
                availableDp = 520f,
                saveLabelDp = 150f,
                clearLabelDp = 60f,
                paddingDp = UsernameActionLayout.TV_PADDING_DP
            )
        )
    }

    @Test
    fun `the row that clipped SAVE USERNAMI stacks instead`() {
        // Kenny's phone at his font scale: the two labels alone are 230dp, and
        // the row has 280dp for labels, four paddings, the gap and the headroom.
        // With the 18.dp the row shipped with, the labels never had a chance -
        // which is why the E went missing rather than the row growing.
        val available = 280f
        val save = 160f
        val clear = 70f
        assertFalse(
            "18.dp padding: the row is 36dp short",
            UsernameActionLayout.fitsSideBySide(available, save, clear, UsernameActionLayout.TV_PADDING_DP)
        )
        assertFalse(
            "12.dp padding is still 12dp short here, so this row must stack - " +
                "shipping it side by side is the clip Kenny photographed",
            UsernameActionLayout.fitsSideBySide(available, save, clear, UsernameActionLayout.PHONE_PADDING_DP)
        )
    }

    @Test
    fun `the phone's tighter padding buys exactly the room between the two constants`() {
        // Same labels, a row where 18.dp fails and 12.dp fits: the four paddings
        // are the only slack the row has left, so one step of padding is 24dp.
        val save = 120f
        val clear = 60f
        val available = 120f + 60f + UsernameActionLayout.PHONE_PADDING_DP * 4f +
            UsernameActionLayout.SPACING_DP + UsernameActionLayout.HEADROOM_DP
        assertTrue(UsernameActionLayout.fitsSideBySide(available, save, clear, UsernameActionLayout.PHONE_PADDING_DP))
        assertFalse(UsernameActionLayout.fitsSideBySide(available, save, clear, UsernameActionLayout.TV_PADDING_DP))
    }

    @Test
    fun `one dp under the requirement is not a fit`() {
        val save = 100f
        val clear = 50f
        val needed = save + clear + UsernameActionLayout.PHONE_PADDING_DP * 4f +
            UsernameActionLayout.SPACING_DP + UsernameActionLayout.HEADROOM_DP
        assertTrue(UsernameActionLayout.fitsSideBySide(needed, save, clear, UsernameActionLayout.PHONE_PADDING_DP))
        assertFalse(
            UsernameActionLayout.fitsSideBySide(needed - 1f, save, clear, UsernameActionLayout.PHONE_PADDING_DP)
        )
    }

    @Test
    fun `a row that has not measured, or has no width, stacks`() {
        // Not knowing the width is not permission to draw past the edge.
        assertFalse(UsernameActionLayout.fitsSideBySide(Float.NaN, 100f, 50f, 12f))
        assertFalse(UsernameActionLayout.fitsSideBySide(300f, Float.NaN, 50f, 12f))
        assertFalse(UsernameActionLayout.fitsSideBySide(300f, 100f, Float.NaN, 12f))
        assertFalse(UsernameActionLayout.fitsSideBySide(0f, 100f, 50f, 12f))
        assertFalse(UsernameActionLayout.fitsSideBySide(-5f, 1f, 1f, 12f))
    }

    @Test
    fun `an unconstrained row cannot starve either label`() {
        assertTrue(UsernameActionLayout.fitsSideBySide(Float.POSITIVE_INFINITY, 400f, 300f, 12f))
    }

    @Test
    fun `the phone row is the narrower-padded one and TV keeps what it shipped`() {
        assertTrue(
            "the phone is where the row ran out of width",
            UsernameActionLayout.PHONE_PADDING_DP < UsernameActionLayout.TV_PADDING_DP
        )
        assertTrue(UsernameActionLayout.paddingDp(phone = true) == UsernameActionLayout.PHONE_PADDING_DP)
        assertTrue(UsernameActionLayout.paddingDp(phone = false) == UsernameActionLayout.TV_PADDING_DP)
        assertTrue(
            "S3: a TV row is verified unchanged, so its padding must not move",
            UsernameActionLayout.paddingDp(phone = false) == 18f
        )
    }
}
