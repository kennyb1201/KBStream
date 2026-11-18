package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.theme.themeAccentColor

/**
 * A season chip's look: the fill, the label, and the D-pad's focus ring.
 *
 * Split out of [SeasonStripAdapter] so the ring can be built and inspected
 * without a RecyclerView, a window or a remote - the ring is the thing that went
 * missing in the field (spec 2026-11-15), so it is the thing that has to be
 * testable.
 *
 * THE RING WAS HIDDEN BY THE PLATFORM, NOT BY THIS CODE (spec 2026-11-15,
 * second attempt). The first fix moved the ring from the background stroke to
 * the foreground, and the field verdict was that no outline shows on any chip -
 * and that the pill is WHITE, a fill this file cannot draw: the palette has no
 * white, `themeAccentColor` falls back to brass, and the theme walk already skips
 * the chip. What draws it is the PLATFORM: a focusable view whose focus indicator
 * the framework does not recognise gets `drawDefaultFocusHighlight`, and
 * `View.draw()` paints that highlight at step 7 - AFTER `onDrawForeground` (step
 * 6), which is where a foreground ring lives. So the system's light rounded rect
 * covered the fill and drew over the ring, which is both symptoms at once.
 * The chip now turns that highlight off at its source (the layout, plus the
 * adapter's bind) - a foreground ring was the right half of the first fix, one
 * draw step short of the whole.
 *
 * THE RING IS A FOREGROUND, NOT A STROKE ON THE BACKGROUND (spec 2026-11-15, F2).
 * The chip's background is rebuilt by [apply] on every bind and every focus
 * change, and it is also the one property the player's theme walk replaces -
 * `retintPlayerChromeView` swaps a view's background for a themed copy of it.
 * That copy is faithful (AOSP's `GradientDrawable.GradientState` copies
 * `mStrokeWidth` and `mStrokeColors`, so a stroke does survive it), but the chip
 * is the only ring in the chrome that lives inside a background the walk owns,
 * and "the walk is careful here" is a much weaker guarantee than "the walk cannot
 * touch this". A foreground is never walked: only [apply] puts a ring there.
 *
 * The fills and the accent are themed HERE rather than left to that walk, because
 * the walk no longer touches the chip at all: under AMOLED both surface tones
 * have to become the black they sit on, and a non-default accent has to replace
 * the brass the XML resolved.
 */
internal object SeasonChipStyle {

    /** The chip's 10dp corner - KBShapeChip's radius, which every chip wears. */
    private const val CORNER_DP = 10f

    /**
     * The TARGET chip's ring (spec 2026-11-17, F2).
     *
     * 3dp, not the episode rows' 2dp: the field has now read a 2dp ring as "no
     * ring" three times, and this chip's whole job is to say which season the
     * D-pad is about to open - a mark that has to be visible from a sofa, next
     * to a chip that is already accent-filled.
     */
    private const val RING_DP = 3f

    /**
     * How much the target chip grows (spec 2026-11-17, F2).
     *
     * Read through the adapter's reveal, which asks this much room around the
     * measured chip: a chip drawn 5% wider than it measures would otherwise have
     * the ring this whole change is about clipped at the strip's edge.
     */
    internal const val FOCUS_SCALE = 1.05f

    /**
     * The chip's label weight in each state (spec 2026-11-17, F2).
     *
     * Bold for the target, the chip's own medium cut otherwise: bold is the third
     * mark that has to survive the theme walk (which rebuilds the chip's fill and
     * could hide a ring in it), and it is the one that reads at TV distance
     * whatever the panel's contrast does.
     */
    internal fun labelFontRes(focused: Boolean): Int =
        if (focused) R.font.inter_bold else R.font.inter_medium

    private fun typefaceFor(context: Context, focused: Boolean): Typeface =
        ResourcesCompat.getFont(context, labelFontRes(focused))
            ?: Typeface.create(Typeface.DEFAULT, if (focused) Typeface.BOLD else Typeface.NORMAL)

    fun apply(chip: TextView, selected: Boolean, focused: Boolean) {
        val context = chip.context
        val density = context.resources.displayMetrics.density
        val fill = fillFor(context, selected, focused)
        chip.setTextColor(
            when {
                selected -> ContextCompat.getColor(context, R.color.kb_void)
                focused -> ContextCompat.getColor(context, R.color.kb_text_hi)
                else -> ContextCompat.getColor(context, R.color.kb_text_lo)
            }
        )
        // The target's other two marks (spec 2026-11-17, F2). Both are reset in
        // the same call that sets them, so a recycled or re-bound chip cannot
        // keep a bold label or a grown frame from the focus it used to have.
        chip.typeface = typefaceFor(context, focused)
        val scale = if (focused) FOCUS_SCALE else 1f
        chip.scaleX = scale
        chip.scaleY = scale
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_DP * density
            setColor(fill)
            // Kept as well as the foreground ring: this is the same 3dp stroke in
            // the same place, so the two coincide exactly and neither looks like a
            // second outline. A background stroke is the chip's own, drawn on the
            // chip it belongs to - the walk replacing the background with a
            // faithful copy leaves it there.
            if (focused) setStroke((RING_DP * density).toInt().coerceAtLeast(1), ringFor(context, fill))
        }
    }

    /**
     * The chip's fill for its two states (spec 2026-11-15, F2).
     *
     * Selected is the themed accent - never a resolved XML colour, never
     * whatever the theme walk leaves behind (the walk skips the chip entirely) -
     * and the two unselected states stay the tones they always were.
     */
    fun fillFor(context: Context, selected: Boolean, focused: Boolean): Int = when {
        selected -> themeAccentColor(context)
        focused -> themedTone(context, R.color.kb_surface_raised)
        else -> themedTone(context, R.color.kb_surface)
    }

    /**
     * The focused chip's ring: the 3dp stroke, and nothing else.
     *
     * Its colour comes from [ringFor], i.e. from the fill it will sit on: the
     * accent on the dark unselected chips, and the contrasting extreme on an
     * accent-filled selected one.
     */
    fun ring(context: Context, selected: Boolean): GradientDrawable =
        outline(context, ringFor(context, fillFor(context, selected, focused = true)))

    /** The 3dp outline in [color], transparent inside - the chip's own ring. */
    private fun outline(context: Context, color: Int): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_DP * density
            setColor(Color.TRANSPARENT)
            setStroke((RING_DP * density).toInt().coerceAtLeast(1), color)
        }
    }

    /**
     * The ring that reads on [fill] (spec 2026-11-15, F3).
     *
     * The old rule picked by state (near-white on the selected chip), which is how
     * the ring became invisible on a pale fill - and on a WHITE fill, which the
     * platform's focus highlight was painting over the whole chip, unreadable by
     * construction. The choice is made from the fill now: the accent where it
     * reads, the void or the near-white where it does not.
     */
    private fun ringFor(context: Context, fill: Int): Int = SeasonChipContrast.ringColor(
        fill = fill,
        accent = themeAccentColor(context),
        dark = ContextCompat.getColor(context, R.color.kb_void),
        light = ContextCompat.getColor(context, R.color.kb_text_hi)
    )

    /**
     * A chrome tone through the same mapping the player's theme walk uses, so the
     * chip sits on the same AMOLED surface as the panel it is pinned to, or keeps
     * the XML tone when the theme has not moved.
     */
    private fun themedTone(context: Context, colorRes: Int): Int {
        val xml = ContextCompat.getColor(context, colorRes)
        return themedChromeColor(context, xml) ?: xml
    }
}
