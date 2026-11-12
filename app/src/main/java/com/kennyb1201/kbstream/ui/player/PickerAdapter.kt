package com.kennyb1201.kbstream.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.theme.themeAccentColor
import com.kennyb1201.kbstream.data.badges.StreamBadge
import com.kennyb1201.kbstream.data.settings.AppPreferences

data class PickerItem(
    val label: String,
    val isSelected: Boolean = false,
    val badges: List<StreamBadge> = emptyList(),
    /**
     * The episode still, for the episode picker's rows. Null on every other
     * row, where the thumbnail slot stays GONE.
     */
    val thumbnail: String? = null,
    /**
     * Art to fall back to when [thumbnail] is absent or dead - the title's own
     * poster, the same fallback `EpisodeCard` loads. Null leaves the still
     * empty rather than guessing at one.
     */
    val fallbackImageUrl: String? = null,
    /** The episode overview, drawn small under the title and capped at two lines. */
    val description: String? = null,
    /**
     * The episode's air date, already formatted for display (spec 2026-11-12).
     *
     * A field of its own rather than text appended to [label], because the date
     * must survive a long title: the row gives the label the remaining width and
     * lets it ellipsize, while the date keeps its own measured space. Null on
     * every non-episode row, and on an episode with no air date - the row then
     * draws no date at all, never a dangling separator.
     */
    val date: String? = null,
    /**
     * The episode's TMDB vote average - the crowd's rating, the same number the
     * episode cards print. Null on every non-episode row and on an episode TMDB
     * has no vote average for; the row then draws no chip.
     */
    val rating: Double? = null,
    /** The history already calls this episode watched: the row carries a check. */
    val watched: Boolean = false,
    /** An unaired episode: the UNAVAILABLE chip, and a press does nothing. */
    val unavailable: Boolean = false,
    /**
     * The episode on screen right now: the NOW PLAYING chip (spec 2026-11-12).
     *
     * The row also keeps the accent label - the chip is the at-a-glance mark a
     * viewer scanning the list needs, so the playing episode reads apart from
     * the watched check and from whichever row merely has focus.
     */
    val nowPlaying: Boolean = false,
    val onClick: () -> Unit
)

class PickerAdapter(
    private val items: List<PickerItem>
) : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.picker_item_label)
        val badges: LinearLayout = view.findViewById(R.id.picker_item_badges)
        val thumb: ImageView = view.findViewById(R.id.picker_item_thumb)
        val description: TextView = view.findViewById(R.id.picker_item_desc)
        val date: TextView = view.findViewById(R.id.picker_item_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.picker_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        // The watched mark rides the label: one row, one line, and the check
        // reads before the episode's own name rather than after it.
        holder.label.text = if (item.watched) "\u2713 ${item.label}" else item.label
        holder.label.setTextColor(
            if (item.isSelected) themeAccentColor(holder.itemView.context)
            else ContextCompat.getColor(holder.itemView.context, R.color.kb_text_hi)
        )
        holder.itemView.setOnClickListener { item.onClick() }
        applyBadgePosition(
            holder,
            AppPreferences.getBadgesAboveFile(holder.itemView.context)
        )
        // The chips row carries the picker's own badges (a source's quality
        // chips) and, for an episode the episode list marks unavailable, the
        // UNAVAILABLE chip Detail's cards wear. Bodies of both kinds never mix:
        // no other row is ever unavailable.
        bindBadgeRow(holder.badges, item.badges)
        // One fixed order for the three chips (F5): rating, then NOW PLAYING,
        // then UNAVAILABLE. Rating first because it is the number a viewer scans
        // a season for; NOW PLAYING next to the label it belongs to; UNAVAILABLE
        // last as the row's verdict. Each appends with the same gap, so any
        // combination of the three reads as one row of chips and never wraps
        // onto the title.
        bindRatingChip(holder.badges, item.rating)
        bindNowPlayingChip(holder.badges, item.nowPlaying)
        bindUnavailableChip(holder.badges, item.unavailable)
        bindStill(holder, item)
        bindDate(holder.date, item.date)
        bindDescription(holder.description, item.description)
    }

    /**
     * Binds the air date beside the label, or hides the slot.
     *
     * Its own view, not part of the label's text: appended to the label, a long
     * episode title would ellipsize the DATE off the end - the one thing the row
     * must keep (S2). Beside it, the label takes what is left and cuts first.
     */
    private fun bindDate(date: TextView, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) {
            date.visibility = View.GONE
            date.text = null
            return
        }
        // The separator travels WITH the date, not with the title: a label line
        // is `S2E07 · Title · Nov 15, 2026`, and the dot has to survive whatever
        // the title does. In the label's own text, a long title would take it.
        date.text = "\u00b7 $text"
        date.visibility = View.VISIBLE
    }

    /**
     * Loads the episode still, or hides the slot.
     *
     * The fallback is the title's own art, exactly the rule `EpisodeCard`
     * keeps, and a still that fails to load drops the slot rather than leaving
     * a broken box on the row: a row with no thumbnail still draws its title
     * and description.
     */
    private fun bindStill(holder: ViewHolder, item: PickerItem) {
        val url = item.thumbnail?.takeIf { it.isNotBlank() }
            ?: item.fallbackImageUrl?.takeIf { it.isNotBlank() }
        holder.thumb.visibility = View.GONE
        if (url == null) return
        holder.thumb.visibility = View.VISIBLE
        holder.thumb.load(url) {
            listener(onError = { _, _ -> holder.thumb.visibility = View.GONE })
        }
    }

    /** Draws the overview, or leaves the slot out entirely when there is none. */
    private fun bindDescription(view: TextView, description: String?) {
        val text = description?.trim().orEmpty()
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        view.text = text
    }

    /**
     * The UNAVAILABLE chip, drawn into the same row the source chips use.
     *
     * A plain chip rather than a [StreamBadge]: the episode list's unavailable
     * state is the app's own word about an unaired episode, not a provider's
     * tag with its own colours and art.
     */
    private fun bindUnavailableChip(row: LinearLayout, unavailable: Boolean) {
        if (!unavailable) return
        val context = row.context
        val density = context.resources.displayMetrics.density
        val chip = TextView(context).apply {
            text = UNAVAILABLE_LABEL
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_lo))
            typeface = runCatching {
                ResourcesCompat.getFont(context, R.font.inter_medium)
            }.getOrNull()
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            background = runCatching {
                ContextCompat.getDrawable(context, R.drawable.pill_chip_bg)
            }.getOrNull()
        }
        row.visibility = View.VISIBLE
        // With a gap, because it is no longer always the row's first chip (F5
        // puts the rating and NOW PLAYING before it); flush only when alone.
        row.addView(
            chip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (row.childCount > 0) marginStart = (BADGE_CHIP_GAP_DP * density).toInt()
            }
        )
    }

    /**
     * The NOW PLAYING chip: a filled accent plate, the one chip that is not a
     * neutral tag.
     *
     * It has to read at a glance against the two things it could be confused
     * with - the watched check, which prefixes the label, and the accent label
     * of the selected row - so it is a solid accent chip with void text rather
     * than outline or dim text, and it says the words, not a glyph.
     */
    private fun bindNowPlayingChip(row: LinearLayout, nowPlaying: Boolean) {
        if (!nowPlaying) return
        val context = row.context
        val density = context.resources.displayMetrics.density
        val chip = TextView(context).apply {
            text = NOW_PLAYING_LABEL
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.kb_void))
            typeface = runCatching {
                ResourcesCompat.getFont(context, R.font.inter_medium)
            }.getOrNull()
            letterSpacing = 1f / 11f
            setPadding(
                (8 * density).toInt(),
                (3 * density).toInt(),
                (8 * density).toInt(),
                (3 * density).toInt()
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(ContextCompat.getColor(context, R.color.kb_accent))
            }
        }
        row.visibility = View.VISIBLE
        row.addView(
            chip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (row.childCount > 0) marginStart = (BADGE_CHIP_GAP_DP * density).toInt()
            }
        )
    }

    override fun getItemCount() = items.size

    companion object {
        /**
         * Chip metrics lifted from the Compose StreamBadgeChip/StreamBadgeRow
         * (16dp art, 22dp chip, 8dp corners = KBShapeSmall, 4dp gap, 11sp Inter
         * Medium label). The player strip and the streams picker show the same
         * badges one screen apart, so both rows have to measure the same.
         */
        /** Detail's own word for an episode that has not aired yet. */
        internal const val UNAVAILABLE_LABEL = "UNAVAILABLE"

        /** The playing episode's own mark, beside the accent label. */
        internal const val NOW_PLAYING_LABEL = "NOW PLAYING"

        /** The plate's wording, unchanged from the episode cards (spec 2026-11-12). */
        internal const val RATING_PLATE_LABEL = "IMDb"

        /**
         * The View twin of Compose's EpisodeRatingChip (KBEpisodeTouch.kt:317).
         *
         * Same yellow IMDb plate, same plate shape, same one-decimal score and
         * the same colours - read from the SAME constants (KBImdbTint,
         * KBShapeChip's 10dp, KBTextHi) rather than re-typed here, because the
         * two surfaces showing one episode must not end up disagreeing about its
         * number. The plate's wording is the cards' wording too: the value is
         * TMDB's vote average and the plate says IMDb today, which is the
         * existing card convention, not something this row invents.
         *
         * Null draws nothing at all - an episode without a vote average leaves
         * the row exactly as it was, with no gap for a chip that is not there.
         */
        fun bindRatingChip(row: LinearLayout, rating: Double?) {
            if (rating == null) return
            val context = row.context
            val density = context.resources.displayMetrics.density
            val plate = TextView(context).apply {
                text = RATING_PLATE_LABEL
                textSize = RATING_PLATE_SIZE_SP
                setTextColor(android.graphics.Color.BLACK)
                typeface = runCatching {
                    ResourcesCompat.getFont(context, R.font.inter_medium)
                }.getOrNull()
                letterSpacing = RATING_PLATE_TRACKING_EM
                setPadding(
                    (3 * density).toInt(),
                    (1 * density).toInt(),
                    (3 * density).toInt(),
                    (1 * density).toInt()
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = RATING_PLATE_CORNER_DP * density
                    setColor(com.kennyb1201.kbstream.ui.components.KBImdbTint.toArgb())
                }
            }
            val score = TextView(context).apply {
                // The card's own format call, so the two can never round or
                // localise one episode's number differently.
                text = "%.1f".format(rating)
                textSize = RATING_SCORE_SIZE_SP
                setTextColor(com.kennyb1201.kbstream.ui.theme.KBTextHi.toArgb())
                typeface = runCatching {
                    ResourcesCompat.getFont(context, R.font.inter_regular)
                }.getOrNull()
            }
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            chip.addView(plate)
            chip.addView(
                score,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (RATING_SCORE_GAP_DP * density).toInt()
                }
            )
            row.visibility = View.VISIBLE
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // The same gap the badge chips leave, so the rating reads as
                    // one of the row's chips and not as part of the last badge.
                    if (row.childCount > 0) marginStart = (BADGE_CHIP_GAP_DP * density).toInt()
                }
            )
        }

        private const val BADGE_IMAGE_HEIGHT_DP = 16
        private const val BADGE_CHIP_HEIGHT_DP = 22
        private const val BADGE_CHIP_GAP_DP = 4
        private const val BADGE_CHIP_CORNER_DP = 8
        private const val BADGE_LABEL_SIZE_SP = 11f

        /** 1sp of tracking at an 11sp label — TextView tracks in em, Compose in sp. */
        private const val BADGE_LABEL_TRACKING_EM = 1f / BADGE_LABEL_SIZE_SP

        // labelSmall (11sp Medium, 0.5sp tracking) and bodySmall (12sp) of the
        // app's typography, and KBShapeChip's 10dp, restated as View values.
        private const val RATING_PLATE_SIZE_SP = 11f
        private const val RATING_SCORE_SIZE_SP = 12f
        private const val RATING_PLATE_TRACKING_EM = 0.5f / RATING_PLATE_SIZE_SP
        private const val RATING_PLATE_CORNER_DP = 10

        /** EpisodeRatingChip's Arrangement.spacedBy(6.dp) between plate and score. */
        private const val RATING_SCORE_GAP_DP = 6

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
                ResourcesCompat.getFont(context, R.font.inter_medium)
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
