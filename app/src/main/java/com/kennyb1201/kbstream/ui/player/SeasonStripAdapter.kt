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
 * focused chip brightens so the D-pad's position is never in doubt.
 */
class SeasonStripAdapter(
    private val items: List<PickerItem>
) : RecyclerView.Adapter<SeasonStripAdapter.ViewHolder>() {

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
        holder.chip.text = item.label
        // Both states matter and they are not the same state: selected is where
        // the list is, focused is where the D-pad is. The style is applied again
        // from the focus listener so a chip that is only focused never loses its
        // ring when the selection moves elsewhere.
        fun restyle(focused: Boolean) = applyChipStyle(holder.chip, item.isSelected, focused)
        restyle(holder.chip.isFocused)
        holder.chip.setOnFocusChangeListener { _, focused -> restyle(focused) }
        holder.itemView.setOnClickListener { item.onClick() }
    }

    override fun getItemCount() = items.size

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
            if (focused && !selected) setStroke((1 * density).toInt(), accent)
        }
    }
}
