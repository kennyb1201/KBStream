package com.kennyb1201.kbstream.ui.player

import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.components.PosterContextAction

/**
 * The episode picker's long-press menu, as the player's View-side twin of
 * Compose's `PosterContextMenu` (spec 2026-11-13).
 *
 * The player is a View-based Activity on purpose (see `ChannelGuideAdapter`:
 * a ComposeView here would mean a second composition and a second set of focus
 * rules inside one screen), so the menu cannot BE the Compose component. What
 * it is instead is the same menu in the player's own world: the caller builds a
 * [PosterContextAction] list - the shared model, not a second one - and this
 * draws it with the picker rows' own plate (`picker_item_bg`, so a focused row
 * looks like a focused picker row), the same dim scrim, and the same three
 * focus rules the Compose menu documents:
 *
 *  1. the overlay owns the D-pad while it is up - directional presses cannot
 *     reach the panel or the bar behind it;
 *  2. LEFT/RIGHT are swallowed outright (a vertical list has no horizontal
 *     neighbours);
 *  3. UP/DOWN are swallowed at the list's edges, so the search that finds no
 *     next row cannot fall through to whatever is behind the scrim.
 *
 * Back dismisses it, and EVERY dismissal - Back or an action - hands focus back
 * to the picker row the menu was opened from. Playback is never touched: this
 * class has no player handle at all.
 */
internal class PickerEpisodeMenu(
    private val activity: ComponentActivity,
    /** Called once per dismissal, to put focus back on the picker row. */
    private val onDismissed: () -> Unit
) {

    private var overlay: View? = null
    private val rowViews = mutableListOf<View>()
    private var dismissing = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            dismiss()
        }
    }

    /** True while the menu is up (a fading one is already closing). */
    val isShowing: Boolean
        get() = overlay != null

    /**
     * Raises the menu, replacing any menu already up.
     *
     * Added to the activity's content view, so it paints over the picker panel
     * and the chrome alike - the same place the Compose menu's in-window
     * overlay sits - and removed again on dismissal, so nothing is left in the
     * tree to intercept a press.
     */
    fun show(
        title: String,
        subtitle: String?,
        actions: List<PosterContextAction>
    ) {
        if (actions.isEmpty()) return
        if (isShowing) dismiss(restoreFocus = false)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.panel_bg)
            setPadding(dp(18), dp(16), dp(18), dp(14))
            isFocusable = false
        }
        panel.addView(
            TextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(ContextCompat.getColor(activity, R.color.kb_accent))
                typeface = runCatching {
                    ResourcesCompat.getFont(activity, R.font.inter_medium)
                }.getOrNull()
            }
        )
        subtitle?.takeIf { it.isNotBlank() }?.let { line ->
            panel.addView(
                TextView(activity).apply {
                    text = line
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(activity, R.color.kb_text_lo))
                    setPadding(0, dp(2), 0, 0)
                    typeface = runCatching {
                        ResourcesCompat.getFont(activity, R.font.inter_regular)
                    }.getOrNull()
                }
            )
        }

        // The scrim goes FIRST in the overlay, so the panel paints above it and
        // a tap anywhere outside the panel dismisses (the phone's way out).
        val scrim = View(activity).apply {
            // FrameLayout params: the overlay stack is a FrameLayout, and a
            // child carrying another container's params crashes its measure.
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            isFocusable = false
            setOnClickListener { dismiss() }
        }

        actions.forEachIndexed { index, action ->
            panel.addView(buildRow(action, index, actions.lastIndex))
        }

        val stack = android.widget.FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(scrim)
            addView(
                panel,
                android.widget.FrameLayout.LayoutParams(
                    dp(MENU_WIDTH_DP),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        content.addView(stack)
        overlay = stack
        dismissing = false
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        backCallback.isEnabled = true
        KbViewMotion.fadeIn(stack)
        stack.post { focusFirst() }
    }

    /**
     * Closes the menu. Focus goes back to the picker row unless the caller is
     * closing the whole panel (then there is no row left to return to).
     */
    fun dismiss(restoreFocus: Boolean = true) {
        val view = overlay ?: return
        if (dismissing) return
        dismissing = true
        overlay = null
        // A fade is not a fence: a press during the 120ms exit must not run a
        // row's action a second time.
        rowViews.forEach { row -> row.isEnabled = false }
        rowViews.clear()
        backCallback.isEnabled = false
        // The callback takes itself out of the dispatcher: `remove()` is the
        // pair of `addCallback` on every androidx.activity version this app
        // ships against, where the dispatcher's own remove arrived later.
        backCallback.remove()
        KbViewMotion.fadeOut(view) { (view.parent as? ViewGroup)?.removeView(view) }
        if (restoreFocus) onDismissed()
    }

    /**
     * Density in pixels. A member, not a local in [show], because the rows are
     * built by [buildRow] and a local function cannot be handed across as a
     * value (`::dp` or a lambda would be needed where a call will do).
     */
    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** Hands the D-pad to the menu's first row. */
    fun focusFirst() {
        rowViews.firstOrNull()?.requestFocus()
    }

    /** True when [view] is this menu's own overlay or sits inside it. */
    fun contains(view: View): Boolean {
        val overlay = overlay ?: return false
        var current: View? = view
        while (current != null) {
            if (current === overlay) return true
            current = current.parent as? View
        }
        return false
    }

    /**
     * One action row: its word, its sub-line when it has one, and the edge
     * clamping that keeps the press inside the list.
     */
    private fun buildRow(
        action: PosterContextAction,
        index: Int,
        lastIndex: Int
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(activity, R.drawable.picker_item_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (index == 0) dp(10) else dp(4)
            }
            setOnClickListener {
                dismiss()
                action.onClick()
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> index == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> index == lastIndex
                    else -> false
                }
            }
        }
        row.addView(
            TextView(activity).apply {
                text = action.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(activity, R.color.kb_text_hi))
                typeface = runCatching {
                    ResourcesCompat.getFont(activity, R.font.inter_medium)
                }.getOrNull()
            }
        )
        action.description?.takeIf { it.isNotBlank() }?.let { line ->
            row.addView(
                TextView(activity).apply {
                    text = line
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(activity, R.color.kb_text_lo))
                    setPadding(0, dp(2), 0, 0)
                    typeface = runCatching {
                        ResourcesCompat.getFont(activity, R.font.inter_regular)
                    }.getOrNull()
                }
            )
        }
        rowViews += row
        return row
    }

    private companion object {
        /** Wide enough for the whole-series row's sub-line on a phone. */
        const val MENU_WIDTH_DP = 340
    }
}
