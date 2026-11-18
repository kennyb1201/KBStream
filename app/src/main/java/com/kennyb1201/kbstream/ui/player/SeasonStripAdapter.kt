package com.kennyb1201.kbstream.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
 * focused chip ALWAYS wears a 3dp ring - the accent on an unselected chip, and
 * kb_text_hi on the selected one, where an accent ring would vanish into the
 * fill - so the D-pad's position is never in doubt, including on the season the
 * list is already showing.
 *
 * THE TARGET HAS TO BE UNMISTAKABLE (spec 2026-11-17, F2). A ring alone was read
 * as "no ring" three times in the field, and the two chips it has to be told
 * apart from are both neighbours: the selected one (accent fill) and the plain
 * ones. So the focused chip now carries three marks at once - the 3dp ring, a
 * bold label and a slight scale-up - and the strip keeps it whole at the panel's
 * edge ([reveal]), because a half-cut chip is a season the viewer cannot read.
 *
 * FOCUSED ALWAYS LOOKS FOCUSED (spec 2026-11-17 amendment to F2). The marks used
 * to be gated on `!chip.isInTouchMode`, and that gate is the one thing that can
 * zero them out: a chip focused while the device is in touch mode rendered
 * exactly like an unselected, unfocused one - the field's "still no visible focus
 * indicator". It is gone, and the worry it answered does not apply: a tap SELECTS
 * that season through the chip's onClick, so a tapped chip is the accent-filled
 * selected one, where a ring marks the selection rather than inventing one.
 *
 * The POSTER NEVER FOLLOWS FOCUS (spec 2026-11-17, F4): it shows the season the
 * episode list is showing, so the two cannot disagree. [PlayerEpisodePicker.seasonStrip]'s
 * selected chip is that season; the strip is what carries the target.
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
        // THE FRAMEWORK'S OWN FOCUS HIGHLIGHT IS OFF, AND THAT IS THE FIX (spec
        // 2026-11-15, second attempt). View.draw() paints it at step 7, after the
        // foreground (step 6) - so on a focused chip it covered both the accent
        // fill and the ring this adapter sets, which is the field's white pill
        // with no outline. Set in the layout as well; set here so a layout edit
        // cannot quietly bring the white pill back.
        view.findViewById<TextView>(R.id.picker_season_chip)
            .setDefaultFocusHighlightEnabled(false)
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
        // FOCUS IS FOCUS, ON ANY INPUT PATH (spec 2026-11-17 amendment to F2).
        // This used to be `focused && !chip.isInTouchMode`, carried over from
        // spec 2026-11-14 F1(e), where a ring left on a tapped chip read as a
        // selection that never happened. That worry is answered by the tap
        // itself: a touched chip's onClick selects its season, so the chip is
        // the selected (accent-filled) one and a ring on it confirms the
        // selection instead of inventing one. What the gate DID do was make a
        // chip focused in touch mode - the chip focus lands on when a panel
        // opens, before any remote press - look identical to an unfocused one,
        // ring and all, which is the field's "no visible focus indicator" for
        // the fourth time. So the focused state is unconditional now.
        fun restyle(focused: Boolean) {
            SeasonChipStyle.apply(chip, item.isSelected, focused)
            // THE RING IS THE CHIP'S FOREGROUND (spec 2026-11-15, F2). It used to
            // be a stroke on the background, and the background is the one thing
            // the player's theme walk replaces wholesale - a copy of the shape
            // with its fill rewritten. The rows survive that because they are
            // rebuilt from a selector; the chip's is built by hand, so a pass
            // after the focus restyle could replace the ring away and nothing
            // re-ran the restyle, because no focus change followed. A foreground
            // is never part of that walk: the ring now sits above the fill, where
            // only this listener can put it or take it away.
            chip.foreground = if (focused) SeasonChipStyle.ring(chip.context, item.isSelected) else null
        }
        restyle(chip.isFocused)
        chip.setOnFocusChangeListener { _, focused ->
            restyle(focused)
            // THE TARGET STAYS WHOLE (spec 2026-11-17, F3). Focus search on a
            // RecyclerView scrolls a child just far enough to TOUCH the viewport
            // edge, which leaves half a chip under the panel's border: the season
            // the viewer was about to pick, unreadable. Posted so the chip is
            // laid out at its final position before the strip is asked to move.
            if (focused) holder.itemView.post { reveal(holder) }
        }
        // The focused view is the CHIP, not the root, and a center press calls
        // performClick() on the focused view - so the listener has to sit on the
        // chip or OK would do nothing on a focused chip (spec 2026-11-14,
        // F1(c)). The root keeps one too, for a touch on the chip's margin.
        chip.setOnClickListener { item.onClick() }
        holder.itemView.setOnClickListener { item.onClick() }
        // THE STRIP'S OWN EDGES (spec 2026-11-14, F2). DOWN is wired through
        // nextFocusDownId; LEFT at the first chip, RIGHT at the last and UP
        // anywhere had no edge at all, so the press fell to focus search, which
        // walks the whole window and finds the cast row of the overlay drawn
        // behind the panel - the field report: the ring jumped out of the strip
        // and the next OK opened an actor page mid-episode. Consuming those
        // presses holds the ring on the end chip; the decision itself is the
        // shared, tested [PickerDpadRules.holdsWithinStrip], so the two engines
        // cannot answer differently.
        chip.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val direction = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> StripDirection.LEFT
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> StripDirection.RIGHT
                android.view.KeyEvent.KEYCODE_DPAD_UP -> StripDirection.UP
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> StripDirection.DOWN
                else -> StripDirection.OTHER
            }
            val position = holder.bindingAdapterPosition
            if (position < 0) return@setOnKeyListener false
            PickerDpadRules.holdsWithinStrip(direction, position, items.lastIndex)
        }
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

    /**
     * Scrolls the strip the least it can to show the focused chip whole
     * (spec 2026-11-17, F3).
     *
     * The arithmetic is [SeasonStripReveal]'s, so both the "how far" and the
     * "does it fit at all" answers are testable without a window.
     */
    private fun reveal(holder: ViewHolder) {
        val strip = holder.itemView.parent as? RecyclerView ?: return
        val child = holder.itemView
        // A post runs after the D-pad may have moved on, and after a recycle may
        // have handed this view to another season: a chip that is no longer the
        // focused one must not be scrolled to.
        if (!child.hasFocus()) return
        if (child.width <= 0 || strip.width <= 0) return
        // The chip is DRAWN 5% larger than it measures (SeasonChipStyle.FOCUS_SCALE),
        // so the room it needs is the grown chip's - the ring is the point of the
        // focus state and it must not be the part left under the edge.
        val grow = ((child.width * (SeasonChipStyle.FOCUS_SCALE - 1f)) / 2f).toInt()
        // The chip's own left/right are already in the strip's coordinates, which
        // start at its left edge.
        val dx = SeasonStripReveal.dx(
            childLeft = child.left - grow,
            childRight = child.right + grow,
            viewportLeft = 0,
            viewportRight = strip.width
        )
        if (dx != 0) strip.scrollBy(dx, 0)
    }

    /** True when a chip this adapter owns currently holds the D-pad. */
    fun hasChipFocus(parent: RecyclerView): Boolean =
        parent.findFocus()?.id == R.id.picker_season_chip
}
