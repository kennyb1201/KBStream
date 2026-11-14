package com.kennyb1201.kbstream.ui.player

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.kennyb1201.kbstream.R

/**
 * The episode picker's season strip (spec 2026-11-12).
 *
 * The seasons used to be rows at the head of the episode list, so switching
 * season meant scrolling past them; they are a pinned, horizontally scrolling
 * strip above the list now. Its items are the same [PickerItem]s the rows are -
 * built by [PlayerEpisodePicker.seasonStrip], so both engines draw the strip
 * from one builder and the chip a viewer presses is the season the list loads -
 * but it draws them as chips through its own small layout, because a season in a
 * strip is one word wide, not the still-plus-overview shape of an episode row.
 *
 * The selected chip takes the accent, the way a selected row's label does; a
 * focused chip ALWAYS wears a 2dp ring - the accent on an unselected chip, and
 * kb_text_hi on the selected one, where an accent ring would vanish into the
 * fill - so the D-pad's position is never in doubt, including on the season the
 * list is already showing.
 */
class SeasonStripAdapter(
    items: List<PickerItem>
) : RecyclerView.Adapter<SeasonStripAdapter.ViewHolder>() {

    /**
     * The chips, replaced in place by [submit].
     *
     * ONE adapter per panel on purpose (spec 2026-11-14, F1(d)): the panel used
     * to build a brand-new adapter on every rebind, which threw away the chip's
     * focus before a ring could be drawn - a season switch or a menu refresh
     * silently ended the viewer's position in the strip.
     */
    private val items = items.toMutableList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chip: TextView = view.findViewById(R.id.picker_season_chip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.picker_season_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val chip = holder.chip
        chip.text = item.label
        // Both states matter and they are not the same state: selected is where
        // the list is, focused is where the D-pad is. The style is applied again
        // from the focus listener so a chip that is only focused never loses its
        // ring when the selection moves elsewhere.
        //
        // THE RING IS THE D-PAD'S (spec 2026-11-14, F1(e)): a touch leaves the
        // tapped chip focused (it is focusableInTouchMode), and a ring left on it
        // read as a selection that never happened.
        fun restyle(focused: Boolean) =
            applyChipStyle(chip, item.isSelected, focused && !chip.isInTouchMode)
        restyle(chip.isFocused)
        chip.setOnFocusChangeListener { _, focused -> restyle(focused) }
        // The focused view is the CHIP, not the root, and a center press calls
        // performClick() on the focused view - so the listener has to sit on the
        // chip or OK would do nothing on a focused chip (spec 2026-11-14,
        // F1(c)). The root keeps one too, for a touch on the chip's margin.
        chip.setOnClickListener { item.onClick() }
        holder.itemView.setOnClickListener { item.onClick() }
    }

    override fun getItemCount() = items.size

    /** Replaces the chips in place, keeping the adapter (and any focus) alive. */
    fun submit(newItems: List<PickerItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * Hands the D-pad to the chip of the loaded season (spec 2026-11-14, F1(a)).
     *
     * Answers whether a request was made - immediately, or on the next layout
     * when the chip was not placed yet - so the caller can consume the press
     * that asked for it instead of letting focus search land on the strip's
     * RecyclerView, which draws no ring at all.
     */
    fun focusSelected(parent: RecyclerView): Boolean {
        val index = items.indexOfFirst { it.isSelected }
        if (index < 0) return false
        parent.scrollToPosition(index)
        val placed = parent.findViewHolderForAdapterPosition(index)
            ?.itemView
            ?.findViewById<TextView>(R.id.picker_season_chip)
        if (placed != null && placed.requestFocus()) return true
        parent.post {
            parent.findViewHolderForAdapterPosition(index)
                ?.itemView
                ?.findViewById<TextView>(R.id.picker_season_chip)
                ?.requestFocus()
        }
        return true
    }

    /** True when a chip this adapter owns currently holds the D-pad. */
    fun hasChipFocus(parent: RecyclerView): Boolean =
        parent.findFocus()?.id == R.id.picker_season_chip

    private fun applyChipStyle(chip: TextView, selected: Boolean, focused: Boolean) {
        val context = chip.context
        val density = context.resources.displayMetrics.density
        val accent = ContextCompat.getColor(context, R.color.kb_accent)
        chip.setTextColor(
            when {
                selected -> ContextCompat.getColor(context, R.color.kb_void)
                focused -> ContextCompat.getColor(context, R.color.kb_text_hi)
                else -> ContextCompat.getColor(context, R.color.kb_text_lo)
            }
        )
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // KBShapeChip's 10dp, the radius every chip in the app wears.
            cornerRadius = 10 * density
            setColor(
                when {
                    selected -> accent
                    focused -> ContextCompat.getColor(context, R.color.kb_surface_raised)
                    else -> ContextCompat.getColor(context, R.color.kb_surface)
                }
            )
            // FOCUS IS A RING, ALWAYS (spec 2026-11-12). The old rule stroked
            // only when the chip was focused AND not selected, so the most
            // common landing - scrolling up onto the season already loaded -
            // drew exactly the same chip focused or not, and 1dp at a sofa's
            // distance read as nothing. 2dp is the episode rows' own weight
            // (picker_item_bg's state_focused).
            //
            // The colour has to read on both fills: the accent ring on the
            // surface-raised chip, and kb_text_hi on the accent-filled selected
            // chip, where an accent ring would be invisible.
            if (focused) {
                setStroke(
                    (2 * density).toInt(),
                    if (selected) ContextCompat.getColor(context, R.color.kb_text_hi) else accent
                )
            }
        }
    }
}
