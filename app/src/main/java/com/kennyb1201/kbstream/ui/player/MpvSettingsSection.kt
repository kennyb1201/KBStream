package com.kennyb1201.kbstream.ui.player

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * The MPV player's settings panel.
 *
 * It carries the same sections in the same order as the main player's panel -
 * STREAM, VIDEO, SUBTITLES, LANGUAGE, A/V SYNC, subtitle position, reset - with
 * the same pill rows, because [PillPanelUi] is literally the same code the main
 * panel uses. What differs is only what has to: every control here sets an mpv
 * property instead of an ExoPlayer one, and the two rows ExoPlayer has and mpv
 * cannot offer (buffer mode, tunneling) are absent rather than dead.
 *
 * Values are stored exactly where the main player stores them, so the engine
 * choice does not change what a title remembers: languages, A/V offsets and the
 * specific audio track go to [PlayerTitlePrefs] under the same title key, and
 * the subtitle look goes to the global display preferences.
 */
internal class MpvSettingsSection(
    private val activity: MpvPlayerActivity,
    private val panel: View
) {

    private val ui = PillPanelUi(activity)

    private var built = false
    private var streamLabel: TextView? = null
    private var memory: TextView? = null
    private var syncLabel: TextView? = null
    private var trackColumn: LinearLayout? = null

    /** Labels of the track rows currently built; null until built once. */
    private var trackLabels: List<String>? = null

    private val aspectPills = mutableListOf<Pair<TextView, Int>>()
    private val speedPills = mutableListOf<Pair<TextView, Float>>()
    private val decodePills = mutableListOf<Pair<TextView, Boolean>>()
    private val sizePills = mutableListOf<Pair<TextView, Int>>()
    private val backgroundPills = mutableListOf<Pair<TextView, Int>>()
    private val audioPills = mutableListOf<Pair<TextView, String>>()
    private val subtitlePills = mutableListOf<Pair<TextView, String>>()
    private val trackPills = mutableListOf<Pair<TextView, String>>()
    private val audioDelayPills = mutableListOf<Pair<TextView, Int>>()
    private val subtitleOffsetPills = mutableListOf<Pair<TextView, Int>>()
    private val positionPills = mutableListOf<Pair<TextView, Int>>()
    private var audioDelayZeroPill: TextView? = null
    private var subtitleOffsetZeroPill: TextView? = null

    /** The same "Auto · English" pills the main player's panel shows. */
    private val audioLanguageEntries = PlayerTrackBridge.playerLanguageOptions(
        AppPreferences.getPreferredAudioLanguage(activity)
    )
    private val subtitleLanguageEntries = PlayerTrackBridge.playerLanguageOptions(
        AppPreferences.getPreferredSubtitleLanguage(activity)
    )

    /** Guards against re-entering [refresh] from a layout pass it caused. */
    private var refreshing = false

    /** Builds the section once, into the panel's own column. */
    fun attach() {
        val column = (panel as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return
        if (built) return
        built = true

        // ── STREAM ─────────────────────────────────────────────────────
        // What mpv ended up playing and decoding, where the main player lists
        // its resolution / bitrate / codec lines.
        column.addView(ui.header("STREAM", topMarginDp = 16))
        streamLabel = ui.label("", topMarginDp = 2).also { column.addView(it) }

        // ── VIDEO ──────────────────────────────────────────────────────
        column.addView(ui.header("VIDEO", topMarginDp = 14))
        column.addView(ui.label("Aspect ratio", topMarginDp = 6))
        ui.addPillGrid(
            column,
            4,
            ASPECT_MODES.mapIndexed { index, name -> name to index },
            aspectPills
        ) { index ->
            activity.chooseAspect(index)
            refresh()
        }

        // The main player's own speed list, labels included, so the button and
        // the panel agree in both engines.
        column.addView(ui.label("Speed", topMarginDp = 8))
        ui.addPillGrid(
            column,
            4,
            SPEED_OPTIONS.map { speed -> "${speed}x" to speed },
            speedPills
        ) { speed ->
            activity.chooseSpeed(speed)
            refresh()
        }

        // Engine-specific, so it lives here rather than in the control bar: the
        // main player has no equivalent because ExoPlayer cannot decode in
        // software at all.
        column.addView(ui.label("Decoding", topMarginDp = 8))
        ui.addPillRow(
            column,
            4,
            listOf("Hardware" to true, "Software" to false),
            decodePills
        ) { hardware ->
            activity.chooseHardwareDecoding(hardware)
            refresh()
        }
        column.addView(
            ui.label(
                "Software decoding is the way out when this box has no video " +
                    "decoders left, which is often why a title landed on this engine.",
                topMarginDp = 4
            )
        )

        // ── SUBTITLES ──────────────────────────────────────────────────
        column.addView(ui.header("SUBTITLES", topMarginDp = 16))
        column.addView(ui.label("Size", topMarginDp = 6))
        ui.addPillRow(
            column,
            4,
            listOf("Small" to 0, "Normal" to 1, "Large" to 2),
            sizePills
        ) { size ->
            activity.chooseSubtitleSize(size)
            refresh()
        }

        column.addView(ui.label("Background", topMarginDp = 8))
        ui.addPillRow(
            column,
            4,
            listOf("None" to 0, "Semi" to 1, "Solid" to 2, "Text" to 3),
            backgroundPills
        ) { background ->
            activity.chooseSubtitleBackground(background)
            refresh()
        }
        column.addView(
            ui.label(
                "A styled ASS/SSA track keeps the look its author shipped, so " +
                    "these apply to plain text subtitles.",
                topMarginDp = 4
            )
        )

        // ── LANGUAGE ───────────────────────────────────────────────────
        column.addView(ui.header("LANGUAGE", topMarginDp = 16))
        memory = ui.label("", topMarginDp = 0).also { column.addView(it) }

        column.addView(ui.label("Audio", topMarginDp = 8))
        ui.addPillGrid(column, 4, audioLanguageEntries, audioPills) { code ->
            activity.chooseAudioLanguage(code)
            refresh()
        }

        // The file's real tracks, listed once mpv has parsed it.
        column.addView(ui.label("Specific track (this file)", topMarginDp = 10))
        trackColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { column.addView(it) }

        column.addView(ui.label("Subtitles", topMarginDp = 10))
        ui.addPillGrid(column, 4, subtitleLanguageEntries, subtitlePills) { code ->
            activity.chooseSubtitleLanguage(code)
            refresh()
        }

        // ── A / V SYNC ─────────────────────────────────────────────────
        column.addView(ui.header("A / V SYNC", topMarginDp = 16))
        syncLabel = ui.label("", topMarginDp = 0).also { column.addView(it) }

        column.addView(ui.label("Audio delay", topMarginDp = 6))
        // Nudge pills rather than a slider: one remote press per step, and it
        // matches the pill rows the panel already uses.
        val audioSteps = listOf("-5s" to -5_000, "-0.5s" to -500, "0" to 0, "+0.5s" to 500, "+5s" to 5_000)
        ui.addPillRow(column, 4, audioSteps, audioDelayPills) { step ->
            val current = activity.audioDelayMs()
            activity.chooseAudioDelay(if (step == 0) 0 else current + step)
            refresh()
        }
        audioDelayZeroPill = audioDelayPills.firstOrNull { it.second == 0 }?.first

        column.addView(ui.label("Subtitle offset", topMarginDp = 8))
        val subtitleSteps = listOf("-5s" to -5_000, "-0.5s" to -500, "0" to 0, "+0.5s" to 500, "+5s" to 5_000)
        ui.addPillRow(column, 4, subtitleSteps, subtitleOffsetPills) { step ->
            val current = activity.subtitleOffsetMs()
            activity.chooseSubtitleOffset(if (step == 0) 0 else current + step)
            refresh()
        }
        subtitleOffsetZeroPill = subtitleOffsetPills.firstOrNull { it.second == 0 }?.first

        column.addView(ui.label("Subtitle position", topMarginDp = 10))
        ui.addPillRow(
            column,
            4,
            listOf("Low" to 0, "Mid" to 1, "High" to 2),
            positionPills
        ) { position ->
            activity.chooseSubtitlePosition(position)
            refresh()
        }

        if (activity.hasTitleMemory()) {
            column.addView(ui.label("", topMarginDp = 0))
            ui.addPillRow(column, 4, listOf("Reset this show" to 1), null) {
                activity.forgetThisTitle()
                refresh()
            }
        }
    }

    /** Re-renders every pill and label from the player's current state. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            streamLabel?.text = activity.diagnosticsText()
            memory?.text = activity.languageMemoryNote()

            aspectPills.forEach { (view, index) -> ui.stylePill(view, activity.aspectModeIndex() == index) }
            speedPills.forEach { (view, speed) -> ui.stylePill(view, activity.playbackSpeed() == speed) }
            decodePills.forEach { (view, hardware) ->
                ui.stylePill(view, activity.hardwareDecoding() == hardware)
            }
            sizePills.forEach { (view, size) -> ui.stylePill(view, activity.subtitleSize() == size) }
            backgroundPills.forEach { (view, background) ->
                ui.stylePill(view, activity.subtitleBackground() == background)
            }
            audioPills.forEach { (view, code) -> ui.stylePill(view, activity.audioLanguage() == code) }
            subtitlePills.forEach { (view, code) ->
                ui.stylePill(view, activity.subtitleLanguage() == code)
            }
            audioDelayZeroPill?.let { ui.stylePill(it, activity.audioDelayMs() == 0) }
            subtitleOffsetZeroPill?.let { ui.stylePill(it, activity.subtitleOffsetMs() == 0) }
            positionPills.forEach { (view, index) ->
                ui.stylePill(view, activity.subtitlePosition() == index)
            }

            syncLabel?.text = "Audio delay: ${activity.audioDelayMs()}ms  ·  " +
                "Subtitle offset: ${activity.subtitleOffsetMs()}ms"

            rebuildTrackRows()
        } finally {
            refreshing = false
        }
    }

    /**
     * Rebuilds the dynamic "specific track" rows only when the file's track list
     * actually changed - rebuilding on every layout pass would drop the focus
     * the user is navigating with.
     */
    private fun rebuildTrackRows() {
        val column = trackColumn ?: return
        val tracks = activity.audioTrackOptions()
        val labels = tracks.map { it.first }

        if (labels == trackLabels) {
            trackPills.forEach { (view, signature) ->
                ui.stylePill(view, activity.audioTrackSignature() == signature)
            }
            return
        }
        trackLabels = labels
        column.removeAllViews()
        trackPills.clear()

        if (tracks.isEmpty()) {
            column.addView(ui.label("Listed once playback starts", topMarginDp = 0))
            return
        }

        // "Default" = back to "any track in my language".
        ui.addPillRow(column, 4, listOf("Default" to ""), trackPills) { signature ->
            activity.chooseAudioTrack(signature)
            refresh()
        }
        tracks.forEach { (label, signature) ->
            ui.addPillRow(column, 4, listOf(label to signature), trackPills) { chosen ->
                activity.chooseAudioTrack(chosen)
                refresh()
            }
        }
        trackPills.forEach { (view, signature) ->
            ui.stylePill(view, activity.audioTrackSignature() == signature)
        }
    }
}
