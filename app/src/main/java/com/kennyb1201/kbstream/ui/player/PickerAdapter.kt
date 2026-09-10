package com.kennyb1201.kbstream.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.badges.StreamBadge

data class PickerItem(
    val label: String,
    val isSelected: Boolean = false,
    val badges: List<StreamBadge> = emptyList(),
    val onClick: () -> Unit
)

class PickerAdapter(
    private val items: List<PickerItem>
) : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.picker_item_label)
        val badges: LinearLayout = view.findViewById(R.id.picker_item_badges)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.picker_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        holder.label.setTextColor(
            if (item.isSelected) ContextCompat.getColor(holder.itemView.context, R.color.kb_accent)
            else ContextCompat.getColor(holder.itemView.context, R.color.kb_text_hi)
        )
        holder.itemView.setOnClickListener { item.onClick() }
        bindBadgeRow(holder.badges, item.badges)
    }

    override fun getItemCount() = items.size

    companion object {
        /**
         * Fills a horizontal LinearLayout with badge chips (hosted image art,
         * Nuvio-style), mirroring the Compose StreamBadgeChip visuals.
         */
        fun bindBadgeRow(row: LinearLayout, badges: List<StreamBadge>) {
            row.removeAllViews()
            if (badges.isEmpty()) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            val context = row.context
            val density = context.resources.displayMetrics.density
            badges.forEach { badge ->
                val chip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val hPad = (3 * density).toInt()
                    val vPad = (2 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(badge.tagColor.toArgb("filled"))
                        setStroke(
                            (1 * density).toInt(),
                            badge.borderColor.toArgb(default = 0x00000000) ?: 0x00000000
                        )
                    }
                }
                if (badge.imageURL.isNotBlank()) {
                    val image = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            (16 * density).toInt()
                        ).apply { minimumWidth = (34 * density).toInt() }
                        adjustViewBounds = true
                        clipToOutline = true
                    }
                    image.load(badge.imageURL)
                    chip.addView(image)
                } else {
                    val text = TextView(context).apply {
                        text = badge.name
                        textSize = 10f
                        setTextColor(
                            badge.textColor.toArgb(default = 0xFFFFFFFF)
                                ?: ContextCompat.getColor(context, android.R.color.white)
                        )
                    }
                    chip.addView(text)
                }
                row.addView(chip)
            }
        }

        private fun String.toArgb(default: String? = null): Int? {
            val hex = trim().removePrefix("#")
            val argb = when (hex.length) {
                6 -> "FF$hex"
                8 -> hex
                else -> return default?.toLongOrNull(16)?.toInt()
                    ?: (if (default == "filled") null else null)
            }
            return argb.toLongOrNull(16)?.toInt()
        }
    }
}
