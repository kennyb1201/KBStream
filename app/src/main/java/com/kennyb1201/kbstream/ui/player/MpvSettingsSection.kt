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
    private val downmixPills = mutableListOf<Pair<TextView, Int>>()
    private val volumePills = mutableListOf<Pair<TextView, Int>>()
    private var dialogueValue: TextView? = null
    private val bufferPills = mutableListOf<Pair<TextView, Int>>()
    private var externalSubtitleLabel: TextView? = null
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

        // The main player's Network buffer row. mpv's cache is a per-file
        // option, so a change made here is taken by the next title rather than
        // by the one playing - which is what the note under the row says.
        column.addView(ui.label("Network buffer", topMarginDp = 10))
        ui.addPillRow(
            column,
            4,
            listOf("Balanced" to 0, "Low latency" to 1),
            bufferPills
        ) { mode ->
            activity.chooseBufferMode(mode)
            refresh()
        }
        column.addView(
            ui.label(
                if (activity.bufferMode() == 1) "Lower buffer for live content"
                else "Best for most streams",
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
        // The sidecar a viewer loaded from a file or from OpenSubtitles, the
        // same line the main player's panel shows.
        externalSubtitleLabel = ui.label("", topMarginDp = 6).also { column.addView(it) }

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

        // ── AUDIO ──────────────────────────────────────────────────────
        // The main player's own three knobs, over mpv's properties and audio
        // filter instead of the app's PCM chain. "Global" follows Settings →
        // Video & Audio; anything else is remembered for THIS title only, so a
        // badly mixed series does not push the next one through the same boost.
        column.addView(ui.header("AUDIO", topMarginDp = 16))
        column.addView(
            ui.label(
                if (activity.hasTitleMemory()) "Remembered for this show"
                else "Applies for this session",
                topMarginDp = 0
            )
        )

        column.addView(ui.label("Downmix", topMarginDp = 8))
        ui.addPillGrid(
            column,
            4,
            listOf("Global" to -1) + PlayerAudioTuning.DOWNMIX_OPTIONS,
            downmixPills
        ) { target ->
            activity.chooseAudioDownmix(target)
            refresh()
        }
        column.addView(
            ui.label(
                "Auto folds 5.1/7.1 down to what this device can carry; Stereo " +
                    "always folds. Every option here can be changed while the film " +
                    "plays - pick one and listen for it.",
                topMarginDp = 4
            )
        )

        // A level, not a set of choices: the same ± stepper the main player's
        // panel carries, so the dialogue boost reads and drives the same way in
        // both engines. The bottom step is "Global" (follow Settings).
        column.addView(ui.label("Dialogue boost", topMarginDp = 8))
        dialogueValue = ui.addStepperRow(
            column,
            4,
            onMinus = { stepDialogue(-1) },
            onPlus = { stepDialogue(1) }
        )
        column.addView(
            ui.label(
                "Each step lifts the centre channel (voices) on a multichannel mix, " +
                    "and the phantom centre a stereo track keeps its dialogue in.",
                topMarginDp = 4
            )
        )

        column.addView(ui.label("Volume boost", topMarginDp = 8))
        ui.addPillGrid(
            column,
            4,
            listOf("Global" to -1) + PlayerAudioTuning.VOLUME_OPTIONS,
            volumePills
        ) { db ->
            activity.chooseVolumeBoost(db)
            refresh()
        }
        column.addView(
            ui.label(
                "Extra gain for quiet mixes. Unlike the main player's, this one has " +
                    "no limiter behind it: mpv applies the gain and stops, so a boost " +
                    "on an already-hot stream can clip.",
                topMarginDp = 4
            )
        )

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

    /**
     * One press of the dialogue stepper's pads; see
     * [PlayerAudioTuning.stepDialogueLevel] for what a step away from "Global"
     * starts from.
     */
    private fun stepDialogue(delta: Int) {
        val next = PlayerAudioTuning.stepDialogueLevel(
            current = activity.dialogueBoostOverride(),
            globalLevel = AppPreferences.getAudioDialogueBoost(activity),
            delta = delta
        )
        activity.chooseDialogueBoost(next)
        refresh()
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

            // The tuning pills mark the OVERRIDE, not the resolved value: -1 is
            // the "Global" pill, which is why the row reads the same here as it
            // does in the main player's panel.
            downmixPills.forEach { (view, value) ->
                ui.stylePill(view, activity.audioDownmixOverride() == value)
            }
            dialogueValue?.text = PlayerAudioTuning.dialogueLevelText(
                activity.dialogueBoostOverride()
            )
            volumePills.forEach { (view, value) ->
                ui.stylePill(view, activity.volumeBoostOverride() == value)
            }
            bufferPills.forEach { (view, mode) ->
                ui.stylePill(view, activity.bufferMode() == mode)
            }
            externalSubtitleLabel?.text = activity.externalSubtitleNote()
                ?.let { "Loaded: $it" }
                ?: "No external subtitle loaded"

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
