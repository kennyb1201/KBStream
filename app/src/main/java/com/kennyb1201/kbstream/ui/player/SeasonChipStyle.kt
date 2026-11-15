package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    /** The episode rows' own focus weight (picker_item_bg's state_focused). */
    private const val RING_DP = 2f

    fun apply(chip: TextView, selected: Boolean, focused: Boolean) {
        val context = chip.context
        val density = context.resources.displayMetrics.density
        val accent = themeAccentColor(context)
        chip.setTextColor(
            when {
                selected -> ContextCompat.getColor(context, R.color.kb_void)
                focused -> ContextCompat.getColor(context, R.color.kb_text_hi)
                else -> ContextCompat.getColor(context, R.color.kb_text_lo)
            }
        )
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_DP * density
            setColor(
                when {
                    selected -> accent
                    focused -> themedTone(context, R.color.kb_surface_raised)
                    else -> themedTone(context, R.color.kb_surface)
                }
            )
            // Kept as well as the foreground ring: this is the same 2dp stroke in
            // the same place, so the two coincide exactly and neither looks like a
            // second outline. A background stroke is the chip's own, drawn on the
            // chip it belongs to - the walk replacing the background with a
            // faithful copy leaves it there.
            if (focused) setStroke((RING_DP * density).toInt().coerceAtLeast(1), ringColor(context, selected))
        }
    }

    /**
     * The focused chip's ring: the 2dp stroke, and nothing else.
     *
     * The colour has to read on both fills - the accent on the surface chip, and
     * kb_text_hi on the accent-filled selected chip, where an accent ring would
     * be invisible.
     */
    fun ring(context: Context, selected: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_DP * density
            setColor(Color.TRANSPARENT)
            setStroke((RING_DP * density).toInt().coerceAtLeast(1), ringColor(context, selected))
        }
    }

    private fun ringColor(context: Context, selected: Boolean): Int =
        if (selected) {
            ContextCompat.getColor(context, R.color.kb_text_hi)
        } else {
            themeAccentColor(context)
        }

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
