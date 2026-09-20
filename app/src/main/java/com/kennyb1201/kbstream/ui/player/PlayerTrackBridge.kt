@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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

    /** One audio track of the playing file, as the panel lists it. */
    data class AudioTrackOption(
        val signature: String,
        val label: String,
        val selected: Boolean
    )

    // ── Panel state ────────────────────────────────────────────────────────
    var audioLanguage by mutableStateOf("")
        private set
    var subtitleLanguage by mutableStateOf("")
        private set
    var audioDelayMs by mutableStateOf(0)
        private set
    var subtitleOffsetMs by mutableStateOf(0)
        private set

    /** Audio tracks of the file that is playing right now (panel list). */
    var audioTracks by mutableStateOf<List<AudioTrackOption>>(emptyList())
        private set

    /** The specific audio track this show was set to, if any. */
    @Volatile
    var audioTrackSignature: String = ""
        private set

    // ── Appliers registered by the activity ───────────────────────────────
    private var applyAudioLanguage: ((String) -> Unit)? = null
    private var applySubtitleLanguage: ((String) -> Unit)? = null
    private var applyAudioDelay: ((Int) -> Unit)? = null
    private var applySubtitleOffset: ((Int) -> Unit)? = null

    /**
     * The running player, so the panel can list its tracks and apply an
     * override. Registered with a weak reference by the activity.
     */
    private var playerProvider: (() -> Player?)? = null

    /** One application per player instance; see [onPlayerAttached]. */
    @Volatile
    private var rememberedTrackApplied = false

    fun register(
        applyAudioLanguage: (String) -> Unit,
        applySubtitleLanguage: (String) -> Unit,
        applyAudioDelay: (Int) -> Unit,
        applySubtitleOffset: (Int) -> Unit,
        playerProvider: () -> Player?
    ) {
        this.applyAudioLanguage = applyAudioLanguage
        this.applySubtitleLanguage = applySubtitleLanguage
        this.applyAudioDelay = applyAudioDelay
        this.applySubtitleOffset = applySubtitleOffset
        this.playerProvider = playerProvider
    }

    fun unregister() {
        applyAudioLanguage = null
        applySubtitleLanguage = null
        applyAudioDelay = null
        applySubtitleOffset = null
        playerProvider = null
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
        audioTrackSignature = remembered?.audioTrackSignature.orEmpty()
        audioTracks = emptyList()
        rememberedTrackApplied = false
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
        // A language choice means "any track in this language": drop the more
        // specific track choice so the two do not disagree.
        audioTrackSignature = ""
        persist(context)
        applyAudioLanguage?.invoke(code.ifBlank { globalAudioLanguage })
    }

    /**
     * Picks one specific audio track (e.g. the DD+ 5.1 rather than the stereo
     * mix of the same language), applies it now, and remembers it for the show.
     * [signature] "" means "Default": back to the language preference.
     */
    fun chooseAudioTrack(context: Context, signature: String) {
        val player = playerProvider?.invoke()
        audioTrackSignature = signature
        persist(context)
        rememberedTrackApplied = true

        if (player == null) return
        if (signature.isBlank()) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            refreshAudioTracks()
            return
        }
        val match = findTrack(player, C.TRACK_TYPE_AUDIO, signature)
        if (match == null) {
            Log.i(TAG, "chosen audio track '$signature' not present in this file")
            refreshAudioTracks()
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
            .build()
        // The chosen language follows the track, so the autoplay path and the
        // panel agree on what this show should sound like.
        match.group.getTrackFormat(match.index).language
            ?.takeIf { it.isNotBlank() }
            ?.let { audioLanguage = it.lowercase() }
        refreshAudioTracks()
    }

    // ── Actual tracks of the playing file ─────────────────────────────────

    /** Refreshes [audioTracks] from the live player (call when the panel opens). */
    fun refreshAudioTracks() {
        val player = playerProvider?.invoke()
        if (player == null) {
            audioTracks = emptyList()
            return
        }
        val options = mutableListOf<AudioTrackOption>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                options.add(
                    AudioTrackOption(
                        signature = signatureOf(format),
                        label = labelFor(format),
                        selected = group.isTrackSelected(i)
                    )
                )
            }
        }
        audioTracks = options
    }

    /**
     * Re-applies a remembered specific track to a (re)created player.
     *
     * The activity's automatic language selection only knows languages, so
     * without this a "English DD+ 5.1" choice would silently come back as
     * whichever English track is first. Called for every player instance the
     * playback view attaches, and applied once per instance so a manual
     * change mid-session is never fought.
     */
    fun onPlayerAttached(player: Player) {
        rememberedTrackApplied = false
        if (audioTrackSignature.isBlank()) return
        // The player may already be ready (rebuild after a source switch).
        if (applyRememberedAudioTrack(player)) return
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                player.removeListener(this)
                applyRememberedAudioTrack(player)
            }
        }
        player.addListener(listener)
    }

    /** Applies the remembered track if this file carries it. True when applied. */
    private fun applyRememberedAudioTrack(player: Player?): Boolean {
        if (player == null) return false
        val signature = audioTrackSignature
        if (signature.isBlank() || rememberedTrackApplied) return false
        val match = findTrack(player, C.TRACK_TYPE_AUDIO, signature) ?: return false
        rememberedTrackApplied = true
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
            .build()
        Log.i(TAG, "re-applied remembered audio track '$signature'")
        return true
    }

    /** A track inside one of the player's groups. */
    private data class TrackRef(val group: Tracks.Group, val index: Int)

    /**
     * Identity of one track across sources: language, codec and channel count.
     * Exact-match first, then language + channels, because the codec string
     * varies between remuxes of the same audio ("ec-3" vs "eac3").
     */
    private fun findTrack(player: Player, type: Int, signature: String): TrackRef? {
        val loose = signatureParts(signature)
        var looseMatch: TrackRef? = null
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (signatureOf(format) == signature) return TrackRef(group, i)
                if (
                    looseMatch == null && loose != null &&
                    loose.first == format.language.orEmpty().lowercase() &&
                    loose.second == format.channelCount
                ) {
                    looseMatch = TrackRef(group, i)
                }
            }
        }
        return looseMatch
    }

    /** `language|channels` of a stored signature, or null when unparseable. */
    private fun signatureParts(signature: String): Pair<String, Int>? {
        val parts = signature.split('|')
        if (parts.size < 3) return null
        val channels = parts[2].toIntOrNull() ?: return null
        if (channels <= 0) return null
        return parts[0] to channels
    }

    /**
     * Stable-ish identity of an audio track, stored per show. Language, codec
     * and channel count — not an index, because the same show on another source
     * (or the next episode) orders its tracks differently.
     */
    fun signatureOf(format: Format): String {
        val channels = if (format.channelCount > 0) format.channelCount.toString() else "0"
        return listOf(
            format.language.orEmpty().lowercase(),
            format.codecs.orEmpty().lowercase(),
            channels
        ).joinToString("|")
    }

    /** Panel label: "ENG • E-AC3 • 6ch • 640 kbps". */
    fun labelFor(format: Format): String {
        val parts = mutableListOf<String>()
        format.language?.uppercase()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        format.codecs?.uppercase()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (format.channelCount > 0) parts.add("${format.channelCount}ch")
        if (format.bitrate > 0) parts.add("${format.bitrate / 1_000} kbps")
        return if (parts.isEmpty()) "Track" else parts.joinToString(" • ")
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
        audioTrackSignature = ""
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
                audioDelayMs = audioDelayMs,
                audioTrackSignature = audioTrackSignature
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
