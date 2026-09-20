@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs

/**
 * Bridge between the Compose playback panel and [NativePlayerActivity].
 *
 * The panel lives in its own file and cannot reach the player, and the
 * activity's player plumbing sits far from its onCreate, so the panel reads and
 * writes Compose state here and the activity registers small appliers when a
 * session starts. Every user choice is (a) applied to the player immediately,
 * (b) remembered per title, and (c) reflected back into the panel's state.
 *
 * Per-title memory means "this show", so episode 2 opens with episode 1's audio
 * language, subtitle language and A/V offsets. "Auto" means "follow the global
 * Language settings", which is exactly the pre-memory behavior.
 */
internal object PlayerTrackBridge {

    private const val TAG = "PLAYER_TRACK_BRIDGE"

    /**
     * Same options the Language settings pane offers (Auto = follow the global
     * preference). Kept in one place here so the panel and the settings list
     * cannot drift apart.
     */
    val LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
        "Auto" to "",
        "English" to "en",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Chinese" to "zh",
        "Portuguese" to "pt",
        "Italian" to "it",
        "Russian" to "ru"
    )

    /** Identity of the title playing now; null for live channels (no memory). */
    @Volatile
    var titleKey: String? = null
        private set

    // ── Panel state ────────────────────────────────────────────────────────
    var audioLanguage by mutableStateOf("")
        private set
    var subtitleLanguage by mutableStateOf("")
        private set
    var audioDelayMs by mutableStateOf(0)
        private set
    var subtitleOffsetMs by mutableStateOf(0)
        private set

    // ── Appliers registered by the activity ───────────────────────────────
    private var applyAudioLanguage: ((String) -> Unit)? = null
    private var applySubtitleLanguage: ((String) -> Unit)? = null
    private var applyAudioDelay: ((Int) -> Unit)? = null
    private var applySubtitleOffset: ((Int) -> Unit)? = null

    fun register(
        applyAudioLanguage: (String) -> Unit,
        applySubtitleLanguage: (String) -> Unit,
        applyAudioDelay: (Int) -> Unit,
        applySubtitleOffset: (Int) -> Unit
    ) {
        this.applyAudioLanguage = applyAudioLanguage
        this.applySubtitleLanguage = applySubtitleLanguage
        this.applyAudioDelay = applyAudioDelay
        this.applySubtitleOffset = applySubtitleOffset
    }

    fun unregister() {
        applyAudioLanguage = null
        applySubtitleLanguage = null
        applyAudioDelay = null
        applySubtitleOffset = null
        titleKey = null
    }

    /**
     * Seeds the panel state for a new session: this title's remembered choices
     * when there are any, otherwise the global defaults. [titleKey] null (live
     * channel) disables remembering entirely.
     */
    fun loadFor(
        context: Context,
        titleKey: String?,
        globalAudioLanguage: String,
        globalSubtitleLanguage: String
    ) {
        this.titleKey = titleKey
        val remembered = PlayerTitlePrefs.get(context, titleKey)

        // "" is "Auto" = follow the global Language settings, which is exactly
        // how the player behaved before per-title memory existed.
        audioLanguage = remembered?.audioLang.orEmpty()
        subtitleLanguage = remembered?.subtitleLang.orEmpty()
        audioDelayMs = remembered?.audioDelayMs ?: 0
        subtitleOffsetMs = remembered?.subtitleOffsetMs ?: 0
    }

    /** The global preference a title inherits when its own choice is "Auto". */
    private var globalAudioLanguage = ""
    private var globalSubtitleLanguage = ""

    fun setGlobalLanguages(audio: String, subtitle: String) {
        globalAudioLanguage = audio
        globalSubtitleLanguage = subtitle
    }

    // ── User choices from the panel ───────────────────────────────────────

    /** [code] "" means Auto: the activity then falls back to the global pref. */
    fun chooseAudioLanguage(context: Context, code: String) {
        audioLanguage = code
        persist(context)
        applyAudioLanguage?.invoke(code.ifBlank { globalAudioLanguage })
    }

    fun chooseSubtitleLanguage(context: Context, code: String) {
        subtitleLanguage = code
        persist(context)
        applySubtitleLanguage?.invoke(code.ifBlank { globalSubtitleLanguage })
    }

    fun chooseAudioDelay(context: Context, ms: Int) {
        val clamped = ms.coerceIn(-AudioDelayProcessor.MAX_MS, AudioDelayProcessor.MAX_MS)
        audioDelayMs = clamped
        persist(context)
        applyAudioDelay?.invoke(clamped)
    }

    fun chooseSubtitleOffset(context: Context, ms: Int) {
        val clamped = ms.coerceIn(-5_000, 5_000)
        subtitleOffsetMs = clamped
        persist(context)
        applySubtitleOffset?.invoke(clamped)
    }

    /** Drops this title's remembered choices and goes back to the defaults. */
    fun forgetTitle(context: Context) {
        PlayerTitlePrefs.forget(context, titleKey)
        audioLanguage = ""
        subtitleLanguage = ""
        audioDelayMs = 0
        subtitleOffsetMs = 0
        applyAudioDelay?.invoke(0)
        applySubtitleOffset?.invoke(0)
        applyAudioLanguage?.invoke(globalAudioLanguage)
        applySubtitleLanguage?.invoke(globalSubtitleLanguage)
    }

    private fun persist(context: Context) {
        val key = titleKey ?: return
        PlayerTitlePrefs.remember(
            context = context,
            key = key,
            prefs = PlayerTitlePrefs.Prefs(
                audioLang = audioLanguage,
                subtitleLang = subtitleLanguage,
                subtitleOffsetMs = subtitleOffsetMs,
                audioDelayMs = audioDelayMs
            )
        )
    }

    // ── Player-side helpers (called from the registered appliers) ──────────

    /**
     * Applies a language to the running player the same way the automatic
     * selector does: pick the first track whose language matches, or (for
     * subtitles) leave text disabled when nothing matches — a subtitle
     * language the stream does not carry must not silently show the wrong
     * track.
     */
    fun applyLanguage(player: Player?, type: Int, language: String): Boolean {
        if (player == null) return false

        if (type == C.TRACK_TYPE_TEXT && language.isBlank()) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return true
        }

        var matched: TrackSelectionOverride? = null
        player.currentTracks.groups
            .filter { it.type == type }
            .forEach { group ->
                if (matched != null) return@forEach
                for (i in 0 until group.length) {
                    val lang = group.getTrackFormat(i).language
                    if (lang.equals(language, ignoreCase = true)) {
                        matched = TrackSelectionOverride(group.mediaTrackGroup, i)
                        return@forEach
                    }
                }
            }

        val override = matched
        if (override == null) {
            if (type == C.TRACK_TYPE_TEXT) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                Log.i(TAG, "no subtitle track for '$language'; subtitles off")
                return false
            }
            Log.i(TAG, "no audio track for '$language'; keeping current")
            return false
        }

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .apply {
                if (type == C.TRACK_TYPE_TEXT) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            .setOverrideForType(override)
            .build()
        Log.i(TAG, "applied language '$language' to track type $type")
        return true
    }
}
