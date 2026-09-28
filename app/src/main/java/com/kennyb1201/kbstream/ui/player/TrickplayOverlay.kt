package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The card a decoded preview frame appears in, above the seek bar.
 *
 * Built in code and attached to the window's content view rather than added to
 * the player layout, for the same reason the overlay-less scrub bubble is (see
 * NativePlayerActivity's `ensureScrubHint`): it has to be able to appear over
 * whatever the overlay is doing — including nothing at all — and a view inside
 * the controls overlay disappears exactly when the viewer is scrubbing with the
 * controls hidden, which is half of when previews are wanted.
 *
 * It tracks the thumb while the seek bar is being dragged ([show]'s `anchorX`)
 * and sits centered the rest of the time, and it never takes focus: on a TV, a
 * preview that can be focused would steal the D-pad from the controls it is
 * floating over.
 */
internal class TrickplayOverlay(private val activity: Activity) {

    private var card: ImageView? = null

    val isVisible: Boolean get() = card?.visibility == View.VISIBLE

    /**
     * Shows [frame], horizontally centered on [anchorView] — the seek bar, whose
     * thumb is the thing a scrubbing viewer is watching — or centered on the
     * screen when there is none to anchor to (the overlay-less scrub, where the
     * card sits over the position bubble instead).
     *
     * The anchor is resolved here rather than passed in as a coordinate so that
     * it is always read at the moment the frame appears: a frame arrives a
     * second or more after the press that asked for it, by which time the thumb
     * has usually moved on.
     */
    fun show(frame: Bitmap, anchorView: View?) {
        val view = ensureCard()
        view.setImageBitmap(frame)
        view.visibility = View.VISIBLE
        val parent = view.parent as? View ?: return
        val width = parent.width
        // From the layout params, not the measured width: the card is added and
        // shown in the same message, so on the very first frame it has not been
        // through a layout pass yet and would be centered instead of tracking.
        val cardWidth = (view.layoutParams?.width ?: 0).takeIf { it > 0 } ?: view.width
        if (width <= 0 || cardWidth <= 0 || anchorView == null) {
            view.translationX = 0f
            return
        }
        view.translationX = trickplayAnchorTranslation(
            anchorX = centerOf(anchorView, parent),
            contentWidth = width,
            cardWidth = cardWidth,
            edgeMarginPx = CARD_EDGE_MARGIN_DP * view.resources.displayMetrics.density
        )
    }

    /** [view]'s horizontal center, in [parent]'s coordinates. */
    private fun centerOf(view: View, parent: View): Float {
        val target = IntArray(2)
        val origin = IntArray(2)
        view.getLocationInWindow(target)
        parent.getLocationInWindow(origin)
        return target[0] - origin[0] + view.width / 2f
    }

    fun hide() {
        card?.visibility = View.GONE
    }

    private fun ensureCard(): ImageView {
        card?.let { return it }
        val density = activity.resources.displayMetrics.density
        val view = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(CARD_BACKGROUND)
                cornerRadius = CARD_CORNER_DP * density
                setStroke((1f * density).toInt(), CARD_BORDER)
            }
            clipToOutline = true
            elevation = 8f * density
            // Decorative, and deliberately untouchable: see the class KDoc.
            isFocusable = false
            isClickable = false
            visibility = View.GONE
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            view,
            FrameLayout.LayoutParams(
                (CARD_WIDTH_DP * density).toInt(),
                (CARD_HEIGHT_DP * density).toInt(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (CARD_BOTTOM_MARGIN_DP * density).toInt() }
        )
        card = view
        return view
    }

    private companion object {
        const val CARD_WIDTH_DP = 240f
        const val CARD_HEIGHT_DP = 135f
        const val CARD_CORNER_DP = 12f
        const val CARD_EDGE_MARGIN_DP = 16f

        /**
         * Clear of the seek bar row (a 48 dp bar plus the two clocks under it)
         * and, because the same card serves the overlay-less case, clear of the
         * scrub bubble that sits at 56 dp.
         */
        const val CARD_BOTTOM_MARGIN_DP = 116f

        const val CARD_BACKGROUND = 0xE614181E.toInt()
        const val CARD_BORDER = 0x33FFFFFF
    }
}
