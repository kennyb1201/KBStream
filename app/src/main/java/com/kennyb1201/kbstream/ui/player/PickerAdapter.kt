package com.kennyb1201.kbstream.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.ui.settings.AppPreferences

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
        applyBadgePosition(
            holder,
            AppPreferences.getBadgesAboveFile(holder.itemView.context)
        )
        bindBadgeRow(holder.badges, item.badges)
    }

    override fun getItemCount() = items.size

    companion object {
        /**
         * Chip metrics lifted from the Compose StreamBadgeChip/StreamBadgeRow
         * (16dp art, 22dp chip, 8dp corners = KBShapeSmall, 4dp gap, 11sp Oswald
         * Medium label). The player strip and the streams picker show the same
         * badges one screen apart, so both rows have to measure the same.
         */
        private const val BADGE_IMAGE_HEIGHT_DP = 16
        private const val BADGE_CHIP_HEIGHT_DP = 22
        private const val BADGE_CHIP_GAP_DP = 4
        private const val BADGE_CHIP_CORNER_DP = 8
        private const val BADGE_LABEL_SIZE_SP = 11f

        /** 1sp of tracking at an 11sp label — TextView tracks in em, Compose in sp. */
        private const val BADGE_LABEL_TRACKING_EM = 1f / BADGE_LABEL_SIZE_SP

        /**
         * Puts the badge row above or below the file name.
         *
         * The item layout ships with the chips above the name (the default);
         * the "Badges above the file name" setting can move them under it. A
         * RecyclerView reuses holders, so this re-runs on every bind rather
         * than only when the layout is inflated -- the padding moves with the
         * row, since a header wants a gap under it and a footer wants one
         * above it.
         */
        fun applyBadgePosition(holder: ViewHolder, above: Boolean) {
            val parent = holder.badges.parent as? ViewGroup ?: return
            val target = if (above) 0 else parent.childCount - 1
            if (parent.indexOfChild(holder.badges) != target) {
                parent.removeView(holder.badges)
                parent.addView(holder.badges, target)
            }
            val density = holder.itemView.resources.displayMetrics.density
            val hPad = (14 * density).toInt()
            holder.badges.setPadding(
                hPad,
                (if (above) 10 * density else 0f).toInt(),
                hPad,
                (if (above) 0f else 8 * density).toInt()
            )
        }

        /**
         * Fills a horizontal LinearLayout with badge chips (hosted image art,
         * KB-style), mirroring the Compose StreamBadgeChip visuals.
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
            val hPad = (3 * density).toInt()
            val vPad = (2 * density).toInt()
            val labelTypeface = runCatching {
                ResourcesCompat.getFont(context, R.font.oswald_medium)
            }.getOrNull()

            /** Same label every Compose chip falls back to: the badge's own name. */
            fun chipLabel(name: String, color: Int) = TextView(context).apply {
                text = name
                textSize = BADGE_LABEL_SIZE_SP
                setTextColor(color)
                typeface = labelTypeface
                letterSpacing = BADGE_LABEL_TRACKING_EM
                setPadding(hPad, 0, hPad, 0)
            }

            badges.forEachIndexed { index, badge ->
                val filled = badge.tagStyle.equals("filled", ignoreCase = true)
                val labelColor = badge.textColor.toArgb(fallback = 0xFFFFFFFF.toInt())
                val chip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = (BADGE_CHIP_HEIGHT_DP * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = BADGE_CHIP_CORNER_DP * density
                        setColor(if (filled) badge.tagColor.toArgb(fallback = 0x00000000) else 0x00000000)
                        setStroke(
                            (1 * density).toInt(),
                            badge.borderColor.toArgb(fallback = 0x00000000)
                        )
                    }
                    // The Compose chip clips its art to its own rounded shape;
                    // the outline comes from the background set just above.
                    clipToOutline = true
                }
                if (badge.imageURL.isNotBlank()) {
                    val text = chipLabel(badge.name, labelColor).apply {
                        visibility = View.GONE
                    }
                    val image = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            (BADGE_IMAGE_HEIGHT_DP * density).toInt()
                        ).apply {
                            minimumWidth = (34 * density).toInt()
                            maxWidth = (92 * density).toInt()
                        }
                        adjustViewBounds = true
                    }
                    // On load failure (dead URL, unsupported format) swap the
                    // invisible empty image for a text chip so the badge stays
                    // visible, mirroring the Compose StreamBadgeChip fallback.
                    image.load(badge.imageURL) {
                        listener(
                            onSuccess = { _, _ -> text.visibility = View.GONE },
                            onError = { _, _ ->
                                image.visibility = View.GONE
                                text.visibility = View.VISIBLE
                            }
                        )
                    }
                    chip.addView(image)
                    chip.addView(text)
                } else {
                    chip.addView(chipLabel(badge.name, labelColor))
                }
                row.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        // Leave the gap on every chip but the first, the way
                        // Arrangement.spacedBy does on the Compose row.
                        if (index > 0) marginStart = (BADGE_CHIP_GAP_DP * density).toInt()
                    }
                )
            }
        }

        /** Parses "RRGGBB" or "AARRGGBB" hex into an ARGB Int; falls back to [fallback]. */
        private fun String.toArgb(fallback: Int): Int {
            val hex = trim().removePrefix("#")
            val padded = when (hex.length) {
                6 -> "FF$hex"
                8 -> hex
                else -> return fallback
            }
            return padded.toLongOrNull(16)?.toInt() ?: fallback
        }
    }
}
