package com.kennyb1201.kbstream.ui.player

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * Track and A/V controls for the player's settings panel.
 *
 * The player's panel is the legacy View tree from `activity_player.xml`, whose
 * rows sit past the tooling's edit window — so this section is built in code and
 * appended to the end of the panel's own column, reusing the panel's drawables,
 * type sizes and colours so it reads as part of it rather than as a bolt-on.
 *
 * Every choice is delegated to [PlayerTrackBridge]: it applies the choice to the
 * running player, remembers it per show, and reflects the state back. Nothing
 * here reimplements any of that, which is why a change made here behaves
 * identically to one made anywhere else.
 */
internal class PlayerPanelSection(
    private val activity: NativePlayerActivity,
    private val panel: View
) {

    private val context = activity
    private val density = activity.resources.displayMetrics.density

    private var memory: TextView? = null
    private var syncLabel: TextView? = null
    private var trackColumn: LinearLayout? = null

    /** Labels of the track rows currently built; null until built once. */
    private var trackLabels: List<String>? = null

    private val audioPills = mutableListOf<Pair<TextView, String>>()
    private val subtitlePills = mutableListOf<Pair<TextView, String>>()
    private val trackPills = mutableListOf<Pair<TextView, String>>()
    private val audioDelayPills = mutableListOf<Pair<TextView, Int>>()
    private val subtitleOffsetPills = mutableListOf<Pair<TextView, Int>>()
    private val positionPills = mutableListOf<Pair<TextView, Int>>()
    private var audioDelayZeroPill: TextView? = null
    private var subtitleOffsetZeroPill: TextView? = null

    /** Guards against re-entering [refresh] from a layout pass it caused. */
    private var refreshing = false

    /** Builds the section once, at the end of the panel's column. */
    fun attach() {
        val column = (panel as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return
        if (trackColumn != null) return

        // ── LANGUAGE ───────────────────────────────────────────────────
        column.addView(header("LANGUAGE", topMarginDp = 16))
        memory = label("", topMarginDp = 0).also { column.addView(it) }

        column.addView(label("Audio", topMarginDp = 8))
        addPillGrid(column, 4, PlayerTrackBridge.LANGUAGE_OPTIONS, audioPills) { code ->
            PlayerTrackBridge.chooseAudioLanguage(context, code)
            refresh()
        }

        // The file's real tracks, listed once the player has parsed it.
        column.addView(label("Specific track (this file)", topMarginDp = 10))
        trackColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { column.addView(it) }

        column.addView(label("Subtitles", topMarginDp = 10))
        addPillGrid(column, 4, PlayerTrackBridge.LANGUAGE_OPTIONS, subtitlePills) { code ->
            PlayerTrackBridge.chooseSubtitleLanguage(context, code)
            refresh()
        }

        // ── A/V SYNC ───────────────────────────────────────────────────
        column.addView(header("A / V SYNC", topMarginDp = 16))
        syncLabel = label("", topMarginDp = 0).also { column.addView(it) }

        column.addView(label("Audio delay", topMarginDp = 6))
        // Nudge pills rather than a slider: one remote press per step, and it
        // matches the pill rows the panel already uses.
        val audioSteps = listOf("-5s" to -5_000, "-0.5s" to -500, "0" to 0, "+0.5s" to 500, "+5s" to 5_000)
        addPillRow(column, 4, audioSteps, audioDelayPills) { step ->
            if (step == 0) {
                PlayerTrackBridge.chooseAudioDelay(context, 0)
            } else {
                PlayerTrackBridge.chooseAudioDelay(context, PlayerTrackBridge.audioDelayMs + step)
            }
            refresh()
        }
        audioDelayZeroPill = audioDelayPills.firstOrNull { it.second == 0 }?.first

        column.addView(label("Subtitle offset", topMarginDp = 8))
        val subtitleSteps = listOf("-5s" to -5_000, "-0.5s" to -500, "0" to 0, "+0.5s" to 500, "+5s" to 5_000)
        addPillRow(column, 4, subtitleSteps, subtitleOffsetPills) { step ->
            // Through the bridge, so an offset tuned here is remembered for the
            // show — the panel's own ± buttons only change it for this session.
            if (step == 0) {
                PlayerTrackBridge.chooseSubtitleOffset(context, 0)
            } else {
                PlayerTrackBridge.chooseSubtitleOffset(context, activity.subtitleOffsetMs + step)
            }
            refresh()
        }
        subtitleOffsetZeroPill = subtitleOffsetPills.firstOrNull { it.second == 0 }?.first

        column.addView(label("Subtitle position", topMarginDp = 10))
        addPillRow(
            column,
            4,
            listOf("Low" to 0, "Mid" to 1, "High" to 2),
            positionPills
        ) { position ->
            AppPreferences.setDefaultSubtitlePosition(context, position)
            activity.subtitlePositionApplied(position)
            refresh()
        }

        if (PlayerTrackBridge.titleKey != null) {
            column.addView(label("", topMarginDp = 0))
            addPillRow(column, 4, listOf("Reset this show" to 1), null) {
                PlayerTrackBridge.forgetTitle(context)
                refresh()
            }
        }
    }

    /** Re-renders every pill and label from the bridge's current state. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            PlayerTrackBridge.refreshAudioTracks()

            memory?.text = if (PlayerTrackBridge.titleKey == null) {
                "Follows your global Language settings"
            } else {
                "Remembered for this show"
            }
            audioPills.forEach { (view, code) -> stylePill(view, PlayerTrackBridge.audioLanguage == code) }
            subtitlePills.forEach { (view, code) -> stylePill(view, PlayerTrackBridge.subtitleLanguage == code) }

            audioDelayZeroPill?.let { stylePill(it, PlayerTrackBridge.audioDelayMs == 0) }
            subtitleOffsetZeroPill?.let { stylePill(it, activity.subtitleOffsetMs == 0) }

            val position = AppPreferences.getDefaultSubtitlePosition(context)
            positionPills.forEach { (view, index) -> stylePill(view, position == index) }

            // The offset is read from the activity, not the bridge: the panel's
            // own ± buttons move it without going through the bridge.
            syncLabel?.text = "Audio delay: ${PlayerTrackBridge.audioDelayMs}ms  ·  " +
                "Subtitle offset: ${activity.subtitleOffsetMs}ms"

            rebuildTrackRows(force = false)
        } finally {
            refreshing = false
        }
    }

    /**
     * Rebuilds the dynamic "specific track" rows only when the file's track list
     * actually changed — rebuilding on every layout pass would drop the focus the
     * user is navigating with.
     */
    private fun rebuildTrackRows(force: Boolean) {
        val column = trackColumn ?: return
        val tracks = PlayerTrackBridge.audioTracks
        val labels = tracks.map { it.label }

        if (!force && labels == trackLabels) {
            trackPills.forEach { (view, signature) ->
                stylePill(view, PlayerTrackBridge.audioTrackSignature == signature)
            }
            return
        }
        trackLabels = labels
        column.removeAllViews()
        trackPills.clear()

        if (tracks.isEmpty()) {
            column.addView(label("Listed once playback starts", topMarginDp = 0))
            return
        }

        // "Default" = back to "any track in my language".
        addPillRow(column, 4, listOf("Default" to ""), trackPills) { signature ->
            PlayerTrackBridge.chooseAudioTrack(context, signature)
            refresh()
        }
        tracks.forEach { track ->
            addPillRow(column, 4, listOf(track.label to track.signature), trackPills) { signature ->
                PlayerTrackBridge.chooseAudioTrack(context, signature)
                refresh()
            }
        }
        trackPills.forEach { (view, signature) ->
            stylePill(view, PlayerTrackBridge.audioTrackSignature == signature)
        }
    }

    // ── View helpers, matched to the panel's XML rows ──────────────────────

    private fun <T> addPillGrid(
        column: LinearLayout,
        topMarginDp: Int,
        entries: List<Pair<String, T>>,
        sink: MutableList<Pair<TextView, T>>,
        onClick: (T) -> Unit
    ) {
        // Three per row: the 360dp panel fits three of the longest language
        // labels, and every option stays one press from its neighbour.
        entries.chunked(3).forEachIndexed { rowIndex, rowEntries ->
            addPillRow(column, if (rowIndex == 0) topMarginDp else 4, rowEntries, sink, onClick)
        }
    }

    private fun <T> addPillRow(
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

    private fun pill(text: String): TextView = TextView(context).apply {
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

    private fun header(text: String, topMarginDp: Int): TextView = label(text, topMarginDp, 12f).apply {
        setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private fun label(text: String, topMarginDp: Int, sizeSp: Float = 11f): TextView = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        runCatching { typeface = ResourcesCompat.getFont(context, R.font.oswald_medium) }
        setTextColor(ContextCompat.getColor(context, R.color.kb_text_lo))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topMarginDp) }
    }

    private fun stylePill(view: TextView, selected: Boolean) {
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

    private fun isSelected(view: View): Boolean = view.tag == true

    private fun dp(value: Int): Int = (value * density).toInt()
}
