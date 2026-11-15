package com.kennyb1201.kbstream.ui.player

import kotlin.math.pow

/**
 * How the season chip's ring is picked (spec 2026-11-15, second attempt, F3).
 *
 * The ring used to be chosen by STATE: `kb_text_hi` on the selected chip, the
 * accent on an unselected one. That rule has no way to be right on every fill -
 * near-white on an accent is a 2:1 outline, which is why the first fix's ring
 * could not be seen even where it applied - so the choice is made from the FILL
 * instead: the ring that reads on the colour it sits on.
 *
 * The accent stays the ring wherever it already reads (the app's whole focus
 * language is a 2dp accent outline, and the chip should not be the one control
 * that speaks a different one). Where the accent IS the fill - the selected chip
 * - the ring falls back to whichever of the two extremes contrasts harder, which
 * on this palette is the dark void: 8.4:1 on Emerald, against 2.0:1 for the
 * near-white it used to draw.
 *
 * Pure integer/luminance math, deliberately: the rule is the thing that went
 * wrong twice, so it is stated here where a JVM test can check it on the real
 * palette without a device, a window or a remote.
 */
internal object SeasonChipContrast {

    /** WCAG's minimum for a non-text graphic to be distinguishable (1.4.11). */
    const val MIN_RING_CONTRAST = 3.0

    /**
     * WCAG relative luminance of [argb]: 0 for black, 1 for white.
     *
     * Alpha is ignored - the chip's fills and rings are opaque, and a ring's
     * readability is a question about its hue against the fill's, not about how
     * transparent the two are.
     */
    fun relativeLuminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val red = channel((argb shr 16) and 0xFF)
        val green = channel((argb shr 8) and 0xFF)
        val blue = channel(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /** WCAG contrast ratio between two opaque colours, 1.0 to 21.0. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * The ring colour for a chip filled with [fill].
     *
     * [accent] first, because it is the app's focus colour and it reads on every
     * dark fill this palette has; then the two extremes, [dark] (the void) or
     * [light] (near-white), whichever contrasts harder against the fill.
     */
    fun ringColor(fill: Int, accent: Int, dark: Int, light: Int): Int = when {
        contrastRatio(fill, accent) >= MIN_RING_CONTRAST -> accent
        contrastRatio(fill, dark) >= contrastRatio(fill, light) -> dark
        else -> light
    }

    /** True when [fill] is too light for a near-white outline to show. */
    fun isLight(fill: Int): Boolean = relativeLuminance(fill) > 0.5
}
