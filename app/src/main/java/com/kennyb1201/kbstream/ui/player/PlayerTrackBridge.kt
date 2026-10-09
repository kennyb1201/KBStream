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
import com.kennyb1201.kbstream.data.settings.AppPreferences

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

    /**
     * The in-player Auto choice inherits the global language. Name that
     * inheritance in the pill so Auto is not mistaken for ignoring the global
     * English (or other language) preference.
     */
    fun playerLanguageOptions(globalLanguage: String): List<Pair<String, String>> {
        if (globalLanguage.isBlank()) return LANGUAGE_OPTIONS
        val globalName = languageName(globalLanguage)
        return LANGUAGE_OPTIONS.mapIndexed { index, (label, code) ->
            if (index == 0) "Auto · $globalName" to code else label to code
        }
    }

    fun languageName(language: String): String =
        LANGUAGE_OPTIONS.firstOrNull { (_, code) ->
            code.isNotBlank() && LanguageMatch.matches(code, language)
        }?.first ?: language.trim().uppercase().ifBlank { "Auto" }

    fun inheritedLanguageSummary(): String =
        languageSummary(
            audioLanguage = audioLanguage,
            subtitleLanguage = subtitleLanguage,
            globalAudioLanguage = globalAudioLanguage,
            globalSubtitleLanguage = globalSubtitleLanguage
        )

    /**
     * The same wording, for a caller that holds these values itself: the MPV
     * player mirrors this panel and has to say the same thing about the same
     * stored preferences.
     */
    fun languageSummary(
        audioLanguage: String,
        subtitleLanguage: String,
        globalAudioLanguage: String,
        globalSubtitleLanguage: String
    ): String {
        val inherited = buildList {
            if (audioLanguage.isBlank()) {
                add("Audio ${languageName(globalAudioLanguage)}")
            }
            if (subtitleLanguage.isBlank()) {
                add("Subtitles ${languageName(globalSubtitleLanguage)}")
            }
        }
        return when {
            inherited.isEmpty() -> "Using this title's remembered languages"
            globalAudioLanguage.isBlank() && globalSubtitleLanguage.isBlank() ->
                "Auto uses each file's default tracks"
            else -> "Auto follows global: ${inherited.joinToString(" · ")}"
        }
    }

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

    /** The specific subtitle track this show was set to, if any. */
    @Volatile
    var subtitleTrackSignature: String = ""
        private set

    /**
     * The viewer turned subtitles OFF for this show (the picker's OFF row). Not
     * the same as "Auto": Auto follows the global subtitle mode, while this is
     * an explicit per-show refusal. The ready-time language pass clears it with
     * a hard disable, so a configured subtitle language cannot turn subtitles
     * back on against it - the "subtitles come back after a rebuffer" report.
     */
    @Volatile
    var subtitlesOff: Boolean = false
        private set

    /**
     * The global subtitle mode (see [SubtitleModeRules]), published with the
     * global languages. The language pass must not arm a subtitle track when it
     * is not [SubtitleModeRules.ON], or a session the viewer set to Off gets
     * text on the first ready transition.
     */
    @Volatile
    private var subtitleMode: Int = SubtitleModeRules.DEFAULT

    /**
     * Per-show audio tuning, or -1 for "follow the global setting". The global
     * values live in [PlayerAudioTuning] already; these are only the overrides
     * this title carries, and every change republishes the resolved values so
     * the audio processor picks them up on the next buffer.
     */
    var audioDownmix by mutableStateOf(-1)
        private set
    var audioDialogueBoost by mutableStateOf(-1)
        private set
    var audioVolumeBoostDb by mutableStateOf(-1)
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

    /** One re-assertion per player instance; see [applyRememberedSubtitleTrack]. */
    @Volatile
    private var rememberedSubtitleTrackApplied = false

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
        audioDownmix = -1
        audioDialogueBoost = -1
        audioVolumeBoostDb = -1
        subtitleTrackSignature = ""
        subtitlesOff = false
        rememberedTrackApplied = false
        rememberedSubtitleTrackApplied = false
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
        subtitleTrackSignature = remembered?.subtitleTrackSignature.orEmpty()
        subtitlesOff = remembered?.subtitleOff == true
        // -1 means "no override": the session then uses the global setting.
        audioDownmix = remembered?.audioDownmix ?: -1
        audioDialogueBoost = remembered?.audioDialogueBoost ?: -1
        audioVolumeBoostDb = remembered?.audioVolumeBoostDb ?: -1
        audioTracks = emptyList()
        rememberedTrackApplied = false
        rememberedSubtitleTrackApplied = false

        // Resolve global + override into the live tuning the processor reads.
        publishAudioTuning(context)
    }

    /** The global preference a title inherits when its own choice is "Auto". */
    private var globalAudioLanguage = ""
    private var globalSubtitleLanguage = ""

    fun setGlobalLanguages(audio: String, subtitle: String) {
        globalAudioLanguage = audio
        globalSubtitleLanguage = subtitle
    }

    /**
     * Publishes the global subtitle mode alongside the global languages. Read
     * by [reapplyLanguageSelection], which runs on every ready transition, so a
     * session the viewer set to Off cannot silently acquire subtitles when a
     * subtitle language happens to be configured.
     */
    fun setSubtitleMode(mode: Int) {
        subtitleMode = SubtitleModeRules.normalized(mode)
    }

    /**
     * Publishes the audio tuning in effect for this session: the global defaults
     * from Settings, overridden by whatever this title remembers.
     *
     * Called when a session starts and after every panel change; the processor
     * reads [PlayerAudioTuning] per buffer, so this is all it takes to hear a
     * change.
     */
    fun publishAudioTuning(context: Context) {
        PlayerAudioTuning.apply(
            downmixTarget = audioDownmix.takeIf { it >= 0 }
                ?: AppPreferences.getAudioDownmix(context),
            dialogueBoost = audioDialogueBoost.takeIf { it >= 0 }
                ?: AppPreferences.getAudioDialogueBoost(context),
            volumeBoostDb = audioVolumeBoostDb.takeIf { it >= 0 }
                ?: AppPreferences.getAudioVolumeBoostDb(context)
        )
    }

    // ── Audio tuning choices from the panel (per show) ─────────────────────

    /** [target] -1 = follow the global downmix setting. */
    fun chooseAudioDownmix(context: Context, target: Int) {
        audioDownmix = target
        persist(context)
        publishAudioTuning(context)
    }

    /**
     * [level] -1 = follow the global dialogue-boost setting, 0 = Off, and
     * [PlayerAudioTuning.DIALOGUE_MAX] is the top step the panels offer.
     */
    fun chooseAudioDialogueBoost(context: Context, level: Int) {
        audioDialogueBoost = level.coerceIn(-1, PlayerAudioTuning.DIALOGUE_MAX)
        persist(context)
        publishAudioTuning(context)
    }

    /** [db] -1 = follow the global volume-boost setting. */
    fun chooseAudioVolumeBoost(context: Context, db: Int) {
        audioVolumeBoostDb = db
        persist(context)
        publishAudioTuning(context)
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
        val refs = trackRefs(player, C.TRACK_TYPE_AUDIO)
        audioTracks = refs.mapIndexed { position, ref ->
            val format = ref.group.getTrackFormat(ref.index)
            AudioTrackOption(
                // The layout parts are what let a hand-picked track whose
                // language says nothing be found again in the next episode (see
                // [signatureOf]); the panel hands this signature straight back
                // to [chooseAudioTrack].
                signature = signatureOf(format, position, refs.size),
                label = labelFor(format),
                selected = ref.group.isTrackSelected(ref.index)
            )
        }
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
        // Subtitles, in priority order.
        //
        // 1. An explicit per-show OFF is a hard disable: the activity's own
        //    automatic pass may have armed a track from the global mode and
        //    language, and this runs after it, so skipping alone is not enough.
        if (subtitlesOff) {
            applyLanguage(player, C.TRACK_TYPE_TEXT, "")
            return
        }
        // 2. A remembered specific track is re-asserted by
        //    [applyRememberedSubtitleTrack] instead; running the language pass
        //    as well would replace it with the first track in that language.
        if (subtitleTrackSignature.isNotBlank()) return
        // 3. The mode must be On. Off must arm nothing, and Forced has already
        //    chosen its track - either one is undone by a language match here.
        if (SubtitleModeRules.normalized(subtitleMode) != SubtitleModeRules.ON) return
        // 4. Blank subtitle preference is "Auto" with nothing configured: leave
        //    the file's own default in place (subtitles off stays off, an
        //    always-on track keeps playing) instead of forcing the type disabled.
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
        rememberedSubtitleTrackApplied = false
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

    /** One corrected pass over a ready player: languages, then the tracks. */
    private fun applyOnReady(player: Player) {
        reapplyLanguageSelection(player)
        applyRememberedAudioTrack(player)
        applyRememberedSubtitleTrack(player)
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

    /**
     * Applies the remembered subtitle track if this file carries it. True when
     * applied. Mirrors [applyRememberedAudioTrack]: a hand-picked subtitle row
     * (SDH, forced, a second language) is not the first track the language pass
     * would pick, so it has to be re-stated once per player instance.
     */
    private fun applyRememberedSubtitleTrack(player: Player?): Boolean {
        if (player == null) return false
        val signature = subtitleTrackSignature
        if (signature.isBlank() || rememberedSubtitleTrackApplied) return false
        val match = findTrack(player, C.TRACK_TYPE_TEXT, signature) ?: return false
        rememberedSubtitleTrackApplied = true
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
            .build()
        Log.i(TAG, "re-applied remembered subtitle track '$signature'")
        return true
    }

    /**
     * The first audio track not known to be the wrong language, or null when
     * every track names a language and none of them is [language].
     * [LanguageMatch.fallbackTrackIndex] does the deciding; this only carries
     * the way back to the media3 objects.
     */
    private fun fallbackAudioTrack(player: Player, language: String): TrackRef? {
        val refs = trackRefs(player, C.TRACK_TYPE_AUDIO)
        val position = LanguageMatch.fallbackTrackIndex(
            refs.map { it.group.getTrackFormat(it.index).language },
            language
        ) ?: return null
        return refs[position]
    }

    /** A track inside one of the player's groups. */
    private data class TrackRef(val group: Tracks.Group, val index: Int)

    /** Every track of [type], in player order, with its position among them. */
    private fun trackRefs(player: Player, type: Int): List<TrackRef> =
        player.currentTracks.groups
            .filter { it.type == type }
            .flatMap { group -> (0 until group.length).map { TrackRef(group, it) } }

    /**
     * The facts about one track that a stored signature is matched against.
     * Split out from the media3 objects so the matching itself can be pinned by
     * a test without a player: see [resolveSignature].
     */
    internal data class TrackIdentity(
        val language: String?,
        val codecs: String,
        val channels: Int
    )

    /**
     * A stored track signature, split into its fields. The last two are the
     * layout a hand-picked track was remembered with; a signature written
     * before they existed has neither (see [signatureOf]).
     */
    internal data class StoredSignature(
        val language: String,
        val codecs: String,
        val channels: Int,
        val index: Int?,
        val trackCount: Int?
    )

    /**
     * Which of [tracks] a stored [signature] means, or null when none does.
     *
     * 1. Exact: the same language, codec and channel count — and, for a
     *    signature that carries them, the same position in a file of the same
     *    size. A legacy three-field signature matches on its three fields.
     * 2. Loose: the same language and channel count. The codec string is what
     *    changes between two remuxes of the same audio ("ec-3" vs "eac3").
     * 3. Position: the last resort for a hand-picked track that names no
     *    language at all — a dual-audio EN/RU release with untagged tracks has
     *    no language to match on, so "the second audio track" is all that
     *    survives to the next episode. Only when the file has the same number
     *    of tracks, so the position still means the same thing, and only onto a
     *    track that does not NAME a different language: that one is known-wrong,
     *    and a guess at a known-wrong track is the one thing this path never
     *    does.
     */
    internal fun resolveSignature(signature: String, tracks: List<TrackIdentity>): Int? {
        // 1. Exact. Deliberately independent of parsing: a signature whose
        //    channel count is not a number still names a track by its tag.
        tracks.forEachIndexed { position, track ->
            val exact = signatureOf(track.language, track.codecs, track.channels, position, tracks.size)
            if (exact == signature) return position
            if (signatureOf(track.language, track.codecs, track.channels) == signature) return position
        }

        val stored = parseSignature(signature) ?: return null

        // 2. Loose.
        if (stored.channels > 0) {
            tracks.forEachIndexed { position, track ->
                if (
                    LanguageMatch.matches(stored.language, track.language) &&
                    stored.channels == track.channels
                ) {
                    return position
                }
            }
        }

        // 3. Position.
        val position = stored.index
        if (position != null && stored.trackCount == tracks.size && position in tracks.indices) {
            val candidate = tracks[position]
            val knownWrong = LanguageMatch.canonical(candidate.language) != null &&
                !LanguageMatch.matches(stored.language, candidate.language)
            if (!knownWrong) return position
        }
        return null
    }

    /**
     * Identity of one track across sources, through [resolveSignature]: exact
     * match first, then language + channels, then the remembered position.
     */
    private fun findTrack(player: Player, type: Int, signature: String): TrackRef? {
        val refs = trackRefs(player, type)
        val position = resolveSignature(signature, refs.map { identityOf(it) }) ?: return null
        return refs[position]
    }

    private fun identityOf(ref: TrackRef): TrackIdentity {
        val format = ref.group.getTrackFormat(ref.index)
        return TrackIdentity(
            language = format.language,
            codecs = format.codecs.orEmpty().lowercase(),
            channels = format.channelCount
        )
    }

    /**
     * Splits a stored signature. Every signature carries the first three fields;
     * the two after them are the layout a manual pick added, and an older
     * three-field signature simply has neither. Null when the identity itself
     * cannot be read — which leaves only the exact match above.
     */
    internal fun parseSignature(signature: String): StoredSignature? {
        val parts = signature.split('|')
        if (parts.size < 3) return null
        return StoredSignature(
            language = parts[0],
            codecs = parts[1],
            channels = parts[2].toIntOrNull() ?: return null,
            // A tail this cannot read reads as absent rather than as
            // corruption: the identity fields alone still resolve the track.
            index = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it >= 0 },
            trackCount = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }
        )
    }

    /**
     * Stable-ish identity of a track, stored per show. Language, codec and
     * channel count — not an index, because the same show on another source (or
     * the next episode) orders its tracks differently.
     *
     * A manually picked track adds `|index|trackCount`: its position among the
     * file's tracks of that type and how many there were. That is the last
     * resort [resolveSignature] has for a track whose language says nothing —
     * there is no language to match on, and the position is then the only thing
     * that survives to the next episode.
     */
    fun signatureOf(format: Format, index: Int? = null, trackCount: Int? = null): String =
        signatureOf(
            language = format.language,
            codecs = format.codecs,
            channelCount = format.channelCount,
            index = index,
            trackCount = trackCount
        )

    /** The same, from the fields — so the format of a signature can be pinned
     *  by a test without building a media3 [Format]. */
    internal fun signatureOf(
        language: String?,
        codecs: String?,
        channelCount: Int,
        index: Int? = null,
        trackCount: Int? = null
    ): String {
        val parts = mutableListOf(
            language.orEmpty().lowercase(),
            codecs.orEmpty().lowercase(),
            if (channelCount > 0) channelCount.toString() else "0"
        )
        // Both parts or neither: a position with no track count cannot be
        // checked against the next file, and is then worse than absent.
        if (index != null && trackCount != null && trackCount > 0 && index in 0 until trackCount) {
            parts += index.toString()
            parts += trackCount.toString()
        }
        return parts.joinToString("|")
    }

    /** Panel label for one audio track — see [audioTrackLabel]. */
    fun labelFor(format: Format): String = audioTrackLabel(format)

    fun chooseSubtitleLanguage(context: Context, code: String) {
        subtitleLanguage = code
        // A language choice means "any track in this language": drop both the
        // per-show OFF and the more specific track choice so the three cannot
        // disagree.
        subtitlesOff = false
        subtitleTrackSignature = ""
        persist(context)
        applySubtitleLanguage?.invoke(code.ifBlank { globalSubtitleLanguage })
    }

    /**
     * The picker's OFF row: an explicit per-show "no subtitles", applied now and
     * remembered, so the ready-time language pass cannot turn them back on.
     * Unlike "Auto" (a blank language) this survives the global mode's language
     * rules.
     */
    fun chooseSubtitlesOff(context: Context) {
        subtitlesOff = true
        subtitleTrackSignature = ""
        persist(context)
        val player = playerProvider?.invoke() ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /**
     * Picks one specific subtitle track from the picker, applies it now, and
     * remembers it for the show. Mirrors [chooseAudioTrack]: the ready-time
     * language pass would otherwise replace it with the first track in the
     * preferred language (the "my subtitle pick reverted" report).
     */
    fun chooseSubtitleTrack(context: Context, signature: String) {
        val player = playerProvider?.invoke()
        subtitlesOff = false
        subtitleTrackSignature = signature
        persist(context)
        rememberedSubtitleTrackApplied = true
        if (player == null) return
        val match = findTrack(player, C.TRACK_TYPE_TEXT, signature)
        if (match == null) {
            Log.i(TAG, "chosen subtitle track '$signature' not present in this file")
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
            .build()
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
        subtitlesOff = false
        subtitleTrackSignature = ""
        audioDelayMs = 0
        subtitleOffsetMs = 0
        audioDownmix = -1
        audioDialogueBoost = -1
        audioVolumeBoostDb = -1
        applyAudioDelay?.invoke(0)
        applySubtitleOffset?.invoke(0)
        applyAudioLanguage?.invoke(globalAudioLanguage)
        applySubtitleLanguage?.invoke(globalSubtitleLanguage)
        publishAudioTuning(context)
    }

    private fun persist(context: Context) {
        val key = titleKey ?: return
        // The aspect override is written by the player's own aspect control
        // (it is not bridge state), so carry whatever is already stored
        // through this write instead of resetting it to "follow global" on
        // the next track change.
        val storedAspect = PlayerTitlePrefs.get(context, key)?.aspectRatio ?: -1
        PlayerTitlePrefs.remember(
            context = context,
            key = key,
            prefs = PlayerTitlePrefs.Prefs(
                audioLang = audioLanguage,
                subtitleLang = subtitleLanguage,
                subtitleOff = subtitlesOff,
                subtitleTrackSignature = subtitleTrackSignature,
                subtitleOffsetMs = subtitleOffsetMs,
                audioDelayMs = audioDelayMs,
                audioTrackSignature = audioTrackSignature,
                audioDownmix = audioDownmix,
                audioDialogueBoost = audioDialogueBoost,
                audioVolumeBoostDb = audioVolumeBoostDb,
                aspectRatio = storedAspect
            )
        )
    }

    // ── Player-side helpers (called from the registered appliers) ──────────

    /**
     * The track the language pass would arm for [type], or null when the file
     * carries nothing [language] matches.
     *
     * For text tracks a track this engine cannot draw is skipped rather than
     * armed. ExoPlayer has no PGS/VobSub/DVB renderer, so a bitmap track armed
     * here draws nothing and raises nothing: there is no exception to trip the
     * decoder ladder and nothing is logged either, which is the "options
     * listed, none display" failure. The answer is the same one the picker
     * rows already give ([SubtitleTrackRules.cannotDrawNote]), so the language
     * pass and the rows cannot disagree about which tracks are dead. Skipping
     * leaves the caller's "no track in this language" path to decide what
     * happens next (the activity's NeedsMpv handoff, the OpenSubtitles fetch)
     * instead of leaving a dead override behind.
     *
     * The audio side keeps its plain first-match: this engine draws every audio
     * track it can select, and [fallbackAudioTrack] owns what happens when
     * nothing matches.
     *
     * Matching goes through [LanguageMatch] because the stored preference is a
     * two-letter code and the file's tags are almost always three-letter ones.
     * Split out from [applyLanguage] so the rule can be pinned by a test that
     * builds fake groups instead of a player.
     */
    internal fun matchingOverride(
        tracks: Tracks,
        type: Int,
        language: String
    ): TrackSelectionOverride? {
        var matched: TrackSelectionOverride? = null
        tracks.groups
            .filter { it.type == type }
            .forEach { group ->
                if (matched != null) return@forEach
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (!LanguageMatch.matches(language, format.language)) continue
                    // Text only: an audio track has no bitmap format, and the
                    // untagged-audio fallback owns the audio side.
                    if (
                        type == C.TRACK_TYPE_TEXT &&
                        SubtitleTrackRules.cannotDrawNote(
                            mimeType = format.sampleMimeType,
                            supported = group.isTrackSupported(i)
                        ) != null
                    ) continue
                    matched = TrackSelectionOverride(group.mediaTrackGroup, i)
                    return@forEach
                }
            }
        return matched
    }

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

        val override = matchingOverride(player.currentTracks, type, language)
        if (override == null) {
            if (type == C.TRACK_TYPE_TEXT) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                Log.i(TAG, "no subtitle track for '$language'; subtitles off")
                return false
            }
            // Audio, and nothing in that language. "Keeping current" is the
            // muxer's default track, and on a dual-audio EN/RU release that is
            // the Russian one - the preference looks ignored. Take the first
            // track NOT known to be the wrong language instead: an untagged
            // track might be the one asked for, while a `rus` track certainly
            // is not. When every track names a language, the current one stands
            // exactly as before.
            logTracks(player, type)
            val fallback = fallbackAudioTrack(player, language)
            if (fallback != null) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(fallback.group.mediaTrackGroup, fallback.index)
                    )
                    .build()
                Log.i(
                    TAG,
                    "no '$language' audio track; avoided the current default and picked " +
                        "track ${fallback.index} (" +
                        (fallback.group.getTrackFormat(fallback.index).language ?: "untagged") +
                        ")"
                )
                return true
            }
            Log.i(
                TAG,
                "no audio track for '$language'; every track names a language, keeping current"
            )
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

/**
 * One audio track as the player's pickers list it: language, codec, channel
 * count and bitrate, e.g. "ENG • EAC3 • 6ch • 640 kbps".
 *
 * The codec is the field a viewer picks *between* — the same film often ships
 * twice in one file (5.1 and stereo, or a dub and a commentary) — so it must
 * not go missing. Language alone was all this used to show, because it read
 * [Format.codecs] and nothing fills that in for container audio: media3's
 * Matroska and FFmpeg paths leave it empty for essentially every audio track.
 * The format's sample MIME type carries the same information ("audio/eac3"),
 * so it is the fallback and the codec field is always populated.
 */
internal fun audioTrackLabel(format: Format): String {
    val parts = mutableListOf<String>()
    format.language?.uppercase()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

    val codec = normalizeCodec(format.codecs?.takeIf { it.isNotBlank() } ?: format.sampleMimeType)
    if (codec != "—") parts.add(codec.uppercase())

    if (format.channelCount > 0) parts.add("${format.channelCount}ch")
    if (format.bitrate > 0) parts.add("${format.bitrate / 1_000} kbps")
    return if (parts.isEmpty()) "Track" else parts.joinToString(" • ")
}
