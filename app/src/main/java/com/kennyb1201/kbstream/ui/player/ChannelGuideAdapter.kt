package com.kennyb1201.kbstream.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.kennyb1201.kbstream.R

/**
 * One channel as the in-player guide paints it.
 *
 * Everything the row needs is resolved before it is handed over, so the adapter
 * stays a pure renderer and the activity owns when a read refreshes it -
 * [submit] re-binds in place without losing the viewer's scroll position.
 */
internal data class ChannelGuideRow(
    val channelNumber: String?,
    val name: String,
    /** Program title, "…" while the read is outstanding, or null = no guide. */
    val nowTitle: String?,
    val nowTime: String?,
    /** 0..1000 across the current program, or null when there is none. */
    val nowProgressPermille: Int?,
    val nextTitle: String?,
    /** True for the channel playing right now. */
    val isCurrent: Boolean,
    val onClick: () -> Unit
)

/**
 * The browsable channel lineup behind the in-player guide overlay.
 *
 * A RecyclerView adapter rather than a Compose list because the player is a
 * View-based Activity (the same reason the picker and settings panels are
 * views): hosting a ComposeView here would mean a second composition and a
 * second set of focus rules inside one screen.
 */
internal class ChannelGuideAdapter : RecyclerView.Adapter<ChannelGuideAdapter.ViewHolder>() {

    private var rows: List<ChannelGuideRow> = emptyList()

    internal class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.channel_guide_item_number)
        val name: TextView = view.findViewById(R.id.channel_guide_item_name)
        val now: TextView = view.findViewById(R.id.channel_guide_item_now)
        val time: TextView = view.findViewById(R.id.channel_guide_item_time)
        val progress: ProgressBar = view.findViewById(R.id.channel_guide_item_progress)
        val next: TextView = view.findViewById(R.id.channel_guide_item_next)
    }

    /**
     * Replaces the rows in place.
     *
     * The identity paint (numbers and names) goes up the moment the overlay
     * opens and the EPG read repaints the same channels a beat later. The list
     * length does not change between the two, so the viewer keeps the row they
     * had scrolled to instead of being thrown back to the top mid-browse.
     */
    fun submit(newRows: List<ChannelGuideRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.channel_guide_item, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context

        holder.number.text = row.channelNumber.orEmpty()
        holder.number.visibility =
            if (row.channelNumber.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.name.text = row.name

        // A channel with no guide match says so rather than leaving the line
        // blank, which is indistinguishable from a program with no title.
        val nowTitle = row.nowTitle
        holder.now.text = nowTitle ?: "No guide data"
        holder.now.setTextColor(
            ContextCompat.getColor(
                context,
                if (nowTitle == null) R.color.kb_text_lo else R.color.kb_text_hi
            )
        )
        holder.time.text = row.nowTime.orEmpty()
        holder.time.visibility = if (row.nowTime.isNullOrBlank()) View.GONE else View.VISIBLE

        val permille = row.nowProgressPermille
        holder.progress.visibility = if (permille == null) View.GONE else View.VISIBLE
        if (permille != null) holder.progress.progress = permille

        holder.next.text = row.nextTitle.orEmpty()
        holder.next.visibility = if (row.nextTitle.isNullOrBlank()) View.GONE else View.VISIBLE

        holder.itemView.isSelected = row.isCurrent
        holder.itemView.setOnClickListener { row.onClick() }
    }

    override fun getItemCount() = rows.size
}
