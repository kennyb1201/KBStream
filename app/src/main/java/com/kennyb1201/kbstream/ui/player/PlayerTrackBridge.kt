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
import com.kennyb1201.kbstream.data.player.LanguageMatch
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

    /** The player we already wired; [setPlayer] can repeat an instance. */
    @Volatile
    private var wiredPlayer: Player? = null

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
        wiredPlayer = null
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
        //
        // Canonicalized on the way in: a language remembered from picking a
        // specific track ("eng") must come back as the code the panel's pills
        // and the global setting use ("en"), or nothing lines up.
        audioLanguage = LanguageMatch.canonical(remembered?.audioLang).orEmpty()
        subtitleLanguage = LanguageMatch.canonical(remembered?.subtitleLang).orEmpty()
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

    /** This show's language, or the global one when the show is on "Auto". */
    private fun effectiveAudioLanguage(): String = audioLanguage.ifBlank { globalAudioLanguage }

    private fun effectiveSubtitleLanguage(): String =
        subtitleLanguage.ifBlank { globalSubtitleLanguage }

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
        // panel agree on what this show should sound like. Stored canonically
        // ("eng" -> "en") so the panel's language pills highlight, and so the
        // comparison against the next episode's tags is apples to apples.
        LanguageMatch.canonical(match.group.getTrackFormat(match.index).language)
            ?.let { audioLanguage = it }
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
     * Applies the language preferences to a player that is ready to be told.
     *
     * The activity runs its own automatic selection first, and that one
     * compares language tags as strings — the settings store "en" while the
     * muxer writes "eng", so it matched nothing on real files: audio kept the
     * muxer's first track (a multi-language release plays in whatever language
     * it was authored in) and a subtitle preference read as "this file has no
     * such track", which switched subtitles OFF. Both are why a language
     * setting appeared to do nothing at all.
     *
     * Safe to call more than once: each call re-states the same choice.
     */
    fun reapplyLanguageSelection(player: Player?) {
        val player = player ?: return
        // A remembered specific track beats a language (it was applied as its
        // own override), and a language pass would pick the FIRST track in
        // that language — undoing the "ENG DD+ 5.1 rather than ENG stereo"
        // choice. [applyRememberedAudioTrack] re-asserts that one instead.
        if (audioTrackSignature.isBlank()) {
            applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())
        }
        // Blank subtitle preference is "Auto" with nothing configured: leave
        // the file's own default in place (subtitles off stays off, an
        // always-on track keeps playing) instead of forcing the type disabled.
        val subtitle = effectiveSubtitleLanguage()
        if (subtitle.isNotBlank()) applyLanguage(player, C.TRACK_TYPE_TEXT, subtitle)
    }

    /**
     * Called for every player instance the playback view attaches: puts the
     * language preferences back and re-applies a remembered specific track.
     *
     * Runs at STATE_READY, and one instance at a time — the view attaches the
     * player at the END of the activity's createPlayer(), so this listener is
     * registered after the activity's and therefore runs after its automatic
     * selection, which is what lets the corrected pass win.
     */
    fun onPlayerAttached(player: Player) {
        if (wiredPlayer === player) return
        wiredPlayer = player
        rememberedTrackApplied = false
        // The player may already be ready (rebuild after a source switch), in
        // which case a READY listener would never fire for this state.
        if (player.playbackState == Player.STATE_READY) applyOnReady(player)
        // Stays for the life of the player, deliberately: the activity re-runs
        // its own string-exact pass on EVERY ready transition (a rebuffer is
        // enough to trigger one), and that is what switches subtitles back off
        // after this pass fixed them. Repeating our own pass is harmless — it
        // only ever re-states the choice the user made.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) applyOnReady(player)
            }
        })
    }

    /** One corrected pass over a ready player: languages, then the track. */
    private fun applyOnReady(player: Player) {
        reapplyLanguageSelection(player)
        applyRememberedAudioTrack(player)
    }

    /** Log of what the file actually carries, for a language that missed. */
    private fun logTracks(player: Player, type: Int) {
        val tags = mutableListOf<String>()
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                tags.add(group.getTrackFormat(i).language ?: "(none)")
            }
        }
        Log.i(TAG, "track type $type carries ${tags.joinToString(", ").ifBlank { "nothing" }}")
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
                    LanguageMatch.matches(loose.first, format.language) &&
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
     * Applies a language to the running player: pick the first track whose
     * language matches, or (for subtitles) leave text disabled when nothing
     * matches — a subtitle language the stream does not carry must not
     * silently show the wrong track.
     *
     * Matching goes through [LanguageMatch] because the stored preference is a
     * two-letter code and the file's tags are almost always three-letter ones.
     */
    fun applyLanguage(player: Player?, type: Int, language: String): Boolean {
        if (player == null) return false

        // Nothing asked for on the audio side ("Auto" with no global default):
        // leave the file's own choice alone rather than logging a miss.
        if (type == C.TRACK_TYPE_AUDIO && language.isBlank()) return false

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
                    if (LanguageMatch.matches(language, lang)) {
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
            if (type == C.TRACK_TYPE_AUDIO) logTracks(player, type)
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
