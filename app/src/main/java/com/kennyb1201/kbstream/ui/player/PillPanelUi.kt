package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.theme.themeAccentColor

/**
 * The pill-chip rows both players' settings panels are built from.
 *
 * Neither panel is in its layout file's reach - the main player's rows are
 * appended in code ([PlayerPanelSection]) and the MPV player's whole panel is
 * built in code ([MpvSettingsSection]) - so the two share these helpers instead
 * of each keeping a copy. That is what makes a pill, a caption or a heading look
 * the same in both engines, and it leaves one place to change if the look ever
 * moves.
 */
internal class PillPanelUi(private val context: Context) {

    private val density = context.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()

    /** A section heading, in the panel's accent color. */
    fun header(text: String, topMarginDp: Int): TextView =
        label(text, topMarginDp, 12f).apply {
            setTextColor(themeAccentColor(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

    /** A caption or hint line, in the panel's dim color. */
    fun label(text: String, topMarginDp: Int, sizeSp: Float = 11f): TextView =
        TextView(context).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            runCatching { typeface = ResourcesCompat.getFont(context, R.font.oswald_medium) }
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_lo))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topMarginDp) }
        }

    fun pill(text: String): TextView = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        runCatching { typeface = ResourcesCompat.getFont(context, R.font.oswald_medium) }
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tag = false
        // The neutral fill, not the fixed @drawable/pill_chip_bg: its fill is
        // @color/kb_surface, which the AMOLED / pure-black toggles have to be
        // able to move (see pillChipBackground).
        background = pillChipBackground(context, selected = false, focused = false)
        setTextColor(ContextCompat.getColor(context, R.color.kb_text_hi))
        // Same look as applyPillState() in the activity: selection color plus a
        // distinct focused state, so a D-pad user can see where they are.
        setOnFocusChangeListener { v, _ -> stylePill(v as TextView, isSelected(v)) }
    }

    /**
     * Three pills per row: the 360dp panel fits three of the longest language
     * labels, and every option stays one press from its neighbor.
     */
    fun <T> addPillGrid(
        column: LinearLayout,
        topMarginDp: Int,
        entries: List<Pair<String, T>>,
        sink: MutableList<Pair<TextView, T>>,
        onClick: (T) -> Unit
    ) {
        entries.chunked(3).forEachIndexed { rowIndex, rowEntries ->
            addPillRow(column, if (rowIndex == 0) topMarginDp else 4, rowEntries, sink, onClick)
        }
    }

    fun <T> addPillRow(
        column: LinearLayout,
        topMarginDp: Int,
        entries: List<Pair<String, T>>,
        sink: MutableList<Pair<TextView, T>>?,
        onClick: (T) -> Unit
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topMarginDp) }
        }
        entries.forEachIndexed { index, (text, value) ->
            val pill = pill(text)
            if (index > 0) {
                (pill.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(6)
            }
            pill.setOnClickListener { onClick(value) }
            row.addView(pill)
            sink?.add(pill to value)
        }
        column.addView(row)
    }

    /**
     * A `- / value / +` row, for a setting that is a LEVEL rather than a set of
     * choices - the dialogue boost, which the panels used to offer as Off / Low
     * / High pills and now steps through [PlayerAudioTuning.DIALOGUE_MAX].
     *
     * The pads are pills, so they focus and press exactly like every other
     * control on the panel; the value between them is a plain label, because it
     * is the readout and not a target - giving it focus would put a dead press
     * in the middle of the row. The label is returned so the caller can write
     * each new level into it from its own refresh.
     */
    fun addStepperRow(
        column: LinearLayout,
        topMarginDp: Int,
        onMinus: () -> Unit,
        onPlus: () -> Unit
    ): TextView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topMarginDp) }
        }
        fun pad(text: String, onClick: () -> Unit): TextView = pill(text).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                dp(40),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
        val value = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            runCatching { typeface = ResourcesCompat.getFont(context, R.font.oswald_medium) }
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_hi))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val minusPad = pad("-", onMinus)
        val plusPad = pad("+", onPlus)
        // LEFT/RIGHT is pinned between the two pads by id instead of being left
        // to the geometric focus search. They are the row's only focusables (the
        // level between them is a readout, so it is deliberately not one), and
        // the + sits at the panel's right edge: when the search comes up empty
        // there, the press is clamped away as "nothing inside the panel that
        // way" and the pad reads as a button the remote cannot land on. Naming
        // the neighbor takes that geometry out of it - the pads are one step
        // apart in both directions, whatever the row is measured to.
        minusPad.nextFocusRightId = plusPad.id
        plusPad.nextFocusLeftId = minusPad.id

        row.addView(minusPad)
        row.addView(value)
        row.addView(plusPad)
        column.addView(row)
        return value
    }

    fun stylePill(view: TextView, selected: Boolean) {
        view.tag = selected
        // Every state is built from the theme at call time (see
        // [applyPillLook]): the fixed XML drawables kept the default brass
        // on a chosen accent - and their focused variants were layer-lists the
        // accent re-tint walk could not rebuild - so the panels' focused and
        // selected pills stayed amber.
        applyPillLook(context, view, selected, view.isFocused)
    }

    fun isSelected(view: View): Boolean = view.tag == true
}

/**
 * The one pill look: the theme-resolved fill plus the label color that belongs
 * to it (void on a filled pill, hi on a neutral one).
 *
 * Each of the three activities used to hold its own copy and they drifted - the
 * main player's helper set only the fill, because its callers happened to set
 * the label color beside it, while the other two set both. So "the pill look"
 * was three answers to the same question, and a selected pill that reached one
 * caller and not another kept the neutral label.
 */
internal fun applyPillLook(
    context: Context,
    view: TextView,
    selected: Boolean,
    focused: Boolean
) {
    view.background = pillChipBackground(context, selected, focused)
    view.setTextColor(
        ContextCompat.getColor(
            context,
            if (selected) R.color.kb_void else R.color.kb_text_hi
        )
    )
}

/**
 * The pill-chip background for one (selected, focused) state, resolved from
 * the CURRENT theme instead of the fixed XML drawables.
 *
 * The drawables behind these states hard-code @color/kb_accent, so every
 * selected or focused pill kept the default brass after the viewer picked a
 * different accent. The two focused variants are also LAYER-LISTS, so the
 * players' accent re-tint walk (which matches a GradientDrawable's own fill)
 * could not rebuild them either - which is why the Up Next card's focused
 * PLAY NEXT pill (the card focuses it on show) stayed amber on a themed
 * install while its neutral twin followed the theme. Building every state as a
 * plain GradientDrawable here makes the fill follow [themeAccentColor], and the
 * neutral fill follow the AMOLED-aware panel surface, exactly as the XML
 * drawables did on the default theme.
 */
internal fun pillChipBackground(
    context: Context,
    selected: Boolean,
    focused: Boolean
): android.graphics.drawable.Drawable {
    val density = context.resources.displayMetrics.density
    // Focus is not selection. The old mapping accent-filled a FOCUSED pill too,
    // so a D-pad crossing the panel turned every pill it touched into "the
    // chosen one" and the panel's real state vanished as the viewer moved
    // through it - and the focused NEUTRAL pill came out identical to the
    // chosen one beside it. A focused but unselected pill now wears the app's
    // one focus look - the raised panel fill with a 2dp accent stroke, the same
    // pair @drawable/mpv_control_bg and themedGuideRowBackground draw - and the
    // accent fill is left to mean "chosen".
    val fill = when {
        selected -> themeAccentColor(context)
        focused -> playerPanelRaisedColor(context)
        else -> playerPanelSurfaceColor(context)
    }
    val stroke = when {
        // Focus on a FILLED pill: the ring is the only thing left that can say
        // "you are here" without un-choosing the pill, exactly as
        // accentButtonBackground rings the accent-filled SKIP INTRO control.
        selected && focused -> ContextCompat.getColor(context, R.color.kb_text_hi)
        // Focus on a neutral pill: the accent stroke every other focused
        // surface in the app carries (was a text-colour border, which read as a
        // second kind of selection).
        focused -> themeAccentColor(context)
        else -> 0
    }
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = 6f * density
        if (stroke != 0) setStroke((2f * density).toInt(), stroke)
    }
}
