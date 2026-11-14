package com.kennyb1201.kbstream.ui.settings

/**
 * When a connect dialog's SAVE USERNAME / CLEAR row can hold both labels, whole
 * (spec 2026-11-14, amendment 3b).
 *
 * The row shipped twice wrong. Unweighted, SAVE USERNAME's label and padding ate
 * the width and CLEAR drew as "CLEA / R"; weighted, SAVE USERNAME was the one
 * clipped, mid-glyph, because the row was simply narrower than both labels and
 * the padding it was handing out. Adding weight cannot make a too-narrow row
 * wide, and the button paddings are the only slack left in it - so the labels
 * are measured at the font scale the viewer actually runs, and the row only
 * draws side by side when both of them fit with room to spare. Otherwise it
 * stacks, which cannot clip at any scale.
 *
 * Kept free of Compose so the arithmetic is testable off-device: the layout in
 * `AnimeUsernameActions` reads these numbers and passes its own measurements in.
 */
object UsernameActionLayout {

    /**
     * Horizontal padding inside a username-row button on the phone, where the
     * 18.dp these rows shipped with is what starved the E of SAVE USERNAME.
     */
    const val PHONE_PADDING_DP = 12f

    /**
     * The TV keeps 18.dp: its panel is a fixed 560dp, both labels fit there with
     * the old padding, and S3 asks that its rows look exactly as they do now.
     */
    const val TV_PADDING_DP = 18f

    /** The gap between the two buttons, unchanged. */
    const val SPACING_DP = 10f

    /**
     * Room left over for the fractional pixels a text measurement rounds away,
     * and for the glyph edges that anti-aliasing draws a hair wide. A row that
     * needs this much less than it has is one that fits.
     */
    const val HEADROOM_DP = 4f

    fun paddingDp(phone: Boolean): Float = if (phone) PHONE_PADDING_DP else TV_PADDING_DP

    /**
     * Whether both labels fit, complete, side by side in a row [availableDp]
     * wide - given the labels' own measured widths (their text, not their
     * buttons) and the padding each button spends per side.
     *
     * The row gives SAVE USERNAME the slack and CLEAR its own width, so CLEAR's
     * button is `clearLabel + 2 * padding` and SAVE's is what the row has left.
     * SAVE's text area is that minus its own padding, which is what the sum below
     * works out to: both labels, four paddings, the gap, and the headroom.
     */
    fun fitsSideBySide(
        availableDp: Float,
        saveLabelDp: Float,
        clearLabelDp: Float,
        paddingDp: Float = PHONE_PADDING_DP
    ): Boolean {
        // A NaN width means the measurement has not resolved; stacking is the
        // safe answer, because a stack cannot clip.
        if (availableDp.isNaN() || saveLabelDp.isNaN() || clearLabelDp.isNaN()) return false
        // An unconstrained row (a width that is not finite) cannot starve either
        // label.
        if (!availableDp.isFinite()) return true
        if (availableDp <= 0f) return false
        val needed = saveLabelDp + clearLabelDp + paddingDp * 4f + SPACING_DP + HEADROOM_DP
        return needed <= availableDp
    }
}
