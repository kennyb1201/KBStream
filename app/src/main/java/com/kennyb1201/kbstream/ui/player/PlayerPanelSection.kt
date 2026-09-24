package com.kennyb1201.kbstream.ui.player

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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

    /**
     * The pill / label / heading chrome, shared with the MPV player's panel so
     * the two cannot drift apart.
     */
    private val ui = PillPanelUi(activity)

    private var memory: TextView? = null
    private var syncLabel: TextView? = null
    private var trackColumn: LinearLayout? = null

    /** Labels of the track rows currently built; null until built once. */
    private var trackLabels: List<String>? = null

    private val audioPills = mutableListOf<Pair<TextView, String>>()
    private val subtitlePills = mutableListOf<Pair<TextView, String>>()
    private val audioLanguageEntries = PlayerTrackBridge.playerLanguageOptions(
        AppPreferences.getPreferredAudioLanguage(context)
    )
    private val subtitleLanguageEntries = PlayerTrackBridge.playerLanguageOptions(
        AppPreferences.getPreferredSubtitleLanguage(context)
    )
    private val trackPills = mutableListOf<Pair<TextView, String>>()
    private val audioDelayPills = mutableListOf<Pair<TextView, Int>>()
    private val positionPills = mutableListOf<Pair<TextView, Int>>()
    private val downmixPills = mutableListOf<Pair<TextView, Int>>()
    private val volumePills = mutableListOf<Pair<TextView, Int>>()
    private var audioDelayZeroPill: TextView? = null
    private var dialogueValue: TextView? = null

    /** Guards against re-entering [refresh] from a layout pass it caused. */
    private var refreshing = false

    /** Builds the section once, at the end of the panel's column. */
    fun attach() {
        val column = (panel as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return
        if (trackColumn != null) return

        // The end-of-episode panels (the Up Next card and the credits
        // recommendations) are in the same past-the-window XML tail, so their
        // finish is applied from code here - this is the first point after
        // bindViews() has inflated and bound both of them.
        activity.prepareEndOfEpisodePanels()

        // ── LANGUAGE ───────────────────────────────────────────────────
        column.addView(header("LANGUAGE", topMarginDp = 16))
        memory = label("", topMarginDp = 0).also { column.addView(it) }

        column.addView(label("Audio", topMarginDp = 8))
        addPillGrid(column, 4, audioLanguageEntries, audioPills) { code ->
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
        addPillGrid(column, 4, subtitleLanguageEntries, subtitlePills) { code ->
            PlayerTrackBridge.chooseSubtitleLanguage(context, code)
            refresh()
        }

        // ── A/V SYNC ───────────────────────────────────────────────────
        // ── AUDIO ───────────────────────────────────────────────────────
        // The app's own PCM knobs - the same three the MPV panel offers, over
        // the same prefs the global Settings screen writes. "Global" follows
        // Settings → Video & Audio; anything else is remembered for THIS show
        // only, so a badly mixed series does not push the next one through the
        // same boost.
        column.addView(header("AUDIO", topMarginDp = 16))
        column.addView(
            label(
                if (PlayerTrackBridge.titleKey == null) "Applies for this session"
                else "Remembered for this show",
                topMarginDp = 0
            )
        )

        column.addView(label("Downmix", topMarginDp = 8))
        addPillGrid(
            column,
            4,
            listOf("Global" to -1) + PlayerAudioTuning.DOWNMIX_OPTIONS,
            downmixPills
        ) { target ->
            PlayerTrackBridge.chooseAudioDownmix(context, target)
            refresh()
        }
        column.addView(
            label(
                "Auto folds 5.1/7.1 down to what this device can carry; Stereo always " +
                    "folds. Either way the centre (dialogue) is lifted.",
                topMarginDp = 4
            )
        )

        // A level, not a set of choices: the pads step one at a time, so a
        // viewer can stop where it sounds right instead of picking between Off,
        // Low and High. The bottom step is "Global" (follow Settings).
        column.addView(label("Dialogue boost", topMarginDp = 8))
        dialogueValue = ui.addStepperRow(
            column,
            4,
            onMinus = { stepDialogue(-1) },
            onPlus = { stepDialogue(1) }
        )
        column.addView(
            label(
                "Each step lifts the centre channel (voices), or the phantom centre of a " +
                    "stereo track, and trims the surrounds that carry score and effects.",
                topMarginDp = 4
            )
        )

        column.addView(label("Volume boost", topMarginDp = 8))
        addPillGrid(
            column,
            4,
            listOf("Global" to -1) + PlayerAudioTuning.VOLUME_OPTIONS,
            volumePills
        ) { db ->
            PlayerTrackBridge.chooseAudioVolumeBoost(context, db)
            refresh()
        }
        column.addView(
            label(
                "Extra gain for mixes that are simply too quiet, with a limiter so loud " +
                    "scenes do not clip.",
                topMarginDp = 4
            )
        )

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

        // No subtitle-offset row here: the panel's own ± row above (SUBTITLES →
        // Offset) already owns that number, and two controls for one value read
        // as two settings. Those pads go through the bridge now, so an offset
        // dialled in there is remembered for the show exactly as these pills
        // were.

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

            memory?.text = PlayerTrackBridge.inheritedLanguageSummary()
            audioPills.forEach { (view, code) ->
                stylePill(view, PlayerTrackBridge.audioLanguage == code)
            }
            subtitlePills.forEach { (view, code) ->
                stylePill(view, PlayerTrackBridge.subtitleLanguage == code)
            }

            audioDelayZeroPill?.let { stylePill(it, PlayerTrackBridge.audioDelayMs == 0) }
            downmixPills.forEach { (view, value) ->
                stylePill(view, PlayerTrackBridge.audioDownmix == value)
            }
            volumePills.forEach { (view, value) ->
                stylePill(view, PlayerTrackBridge.audioVolumeBoostDb == value)
            }
            dialogueValue?.text = PlayerAudioTuning.dialogueLevelText(
                PlayerTrackBridge.audioDialogueBoost
            )

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
     * One press of the dialogue stepper's pads. From "Global" the step starts
     * at the level the global setting is actually on, so + is louder and -
     * quieter than what is playing now rather than dropping straight to Off;
     * stepping down past Off lands back on Global.
     */
    private fun stepDialogue(delta: Int) {
        val next = PlayerAudioTuning.stepDialogueLevel(
            current = PlayerTrackBridge.audioDialogueBoost,
            globalLevel = AppPreferences.getAudioDialogueBoost(context),
            delta = delta
        )
        PlayerTrackBridge.chooseAudioDialogueBoost(context, next)
        refresh()
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
    ) = ui.addPillGrid(column, topMarginDp, entries, sink, onClick)

    private fun <T> addPillRow(
        column: LinearLayout,
        topMarginDp: Int,
        entries: List<Pair<String, T>>,
        sink: MutableList<Pair<TextView, T>>?,
        onClick: (T) -> Unit
    ) = ui.addPillRow(column, topMarginDp, entries, sink, onClick)

    private fun pill(text: String): TextView = ui.pill(text)

    private fun header(text: String, topMarginDp: Int): TextView = ui.header(text, topMarginDp)

    private fun label(text: String, topMarginDp: Int, sizeSp: Float = 11f): TextView =
        ui.label(text, topMarginDp, sizeSp)

    private fun stylePill(view: TextView, selected: Boolean) = ui.stylePill(view, selected)

    private fun isSelected(view: View): Boolean = ui.isSelected(view)

    private fun dp(value: Int): Int = ui.dp(value)
}
