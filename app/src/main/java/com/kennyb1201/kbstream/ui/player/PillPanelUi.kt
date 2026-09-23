package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.kennyb1201.kbstream.R

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

    /** A section heading, in the panel's accent colour. */
    fun header(text: String, topMarginDp: Int): TextView =
        label(text, topMarginDp, 12f).apply {
            setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

    /** A caption or hint line, in the panel's dim colour. */
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
        setBackgroundResource(R.drawable.pill_chip_bg)
        setTextColor(ContextCompat.getColor(context, R.color.kb_text_hi))
        // Same look as applyPillState() in the activity: selection colour plus a
        // distinct focused state, so a D-pad user can see where they are.
        setOnFocusChangeListener { v, _ -> stylePill(v as TextView, isSelected(v)) }
    }

    /**
     * Three pills per row: the 360dp panel fits three of the longest language
     * labels, and every option stays one press from its neighbour.
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

    fun stylePill(view: TextView, selected: Boolean) {
        view.tag = selected
        view.setBackgroundResource(
            when {
                selected && view.isFocused -> R.drawable.pill_chip_selected_focused_bg
                selected -> R.drawable.pill_chip_selected_bg
                view.isFocused -> R.drawable.pill_chip_focused_bg
                else -> R.drawable.pill_chip_bg
            }
        )
        view.setTextColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.kb_void else R.color.kb_text_hi
            )
        )
    }

    fun isSelected(view: View): Boolean = view.tag == true
}
