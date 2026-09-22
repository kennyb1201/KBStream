package com.kennyb1201.kbstream.ui.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent player defaults stored in SharedPreferences.
 *
 * These values are loaded when the player opens and provide the
 * starting point for each session. They can be overridden per-session
 * in the player overlay settings panel.
 *
 * The home screen Settings screen edits these values. The player
 * reads them once at creation and lets the user override during playback.
 */
object AppPreferences {

    private const val PREFS_NAME = "kbstream_player_prefs"

    // Keys
    private const val KEY_DEFAULT_BUFFER_MODE = "default_buffer_mode"       // 0=balanced, 1=low-latency, 2=auto
    private const val KEY_DEFAULT_SUBTITLE_SIZE = "default_subtitle_size"   // 0=small, 1=normal, 2=large
    private const val KEY_DEFAULT_SUBTITLE_BG = "default_subtitle_bg"       // 0=none, 1=semi, 2=solid
    private const val KEY_DEFAULT_SUBTITLE_POSITION = "default_subtitle_position" // 0=low, 1=mid, 2=high
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
    // Automatic skipping of IntroDB segments. Both default OFF: moving the
    // playhead with no press is opted into, never assumed.
    private const val KEY_AUTO_SKIP_INTRO = "auto_skip_intro"
    private const val KEY_AUTO_SKIP_CREDITS = "auto_skip_credits"
    private const val KEY_AUTO_SELECT_STREAM = "auto_select_stream"
    private const val KEY_USE_STREAM_RANKER = "use_stream_ranker"
    private const val KEY_BINGE_GROUP_PREFER = "binge_group_prefer"
    private const val KEY_BINGE_GROUP_REUSE = "binge_group_reuse"
    private const val KEY_BINGE_GROUP_FALLBACK = "binge_group_fallback"
    private const val KEY_STILL_THERE_PROMPT = "still_there_prompt"
    private const val KEY_STILL_THERE_EPISODES = "still_there_episodes"
    private const val KEY_FORCE_SOFTWARE_DECODER = "force_software_decoder"
    private const val KEY_ENABLE_TUNNELING = "enable_tunneling"
    private const val KEY_ENABLE_PIP = "enable_pip"
    private const val KEY_DECODER_MODE = "decoder_mode" // legacy toggle, migrated below
    private const val KEY_DECODER_PRIORITY = "decoder_priority" // combined (one release), migrated below
    private const val KEY_VIDEO_DECODER = "video_decoder" // legacy key, removed on migration
    private const val KEY_AUDIO_DECODER = "audio_decoder"                      // 0=auto, 1=ffmpeg-only
    private const val KEY_DV_COMPAT_MODE = "dv_compat_mode"                  // 0=p7->8.1, 1=none, 3=strip all (2=legacy auto+hdr10+, 4=legacy combined 8.1)
    private const val KEY_STRIP_HDR10_PLUS = "strip_hdr10_plus"             // independent of the DV mode
    private const val KEY_CONVERT_P7_TO_81 = "dv_convert_p7_to_81"          // P7 → Profile 8.1 (independent of the DV mode)
    private const val KEY_CONVERT_P5_TO_81 = "dv_convert_p5_to_81"          // P5 → Profile 8.1 (independent of the DV mode)
    private const val KEY_DV_PASSTHROUGH_FAILED_AT = "dv_passthrough_failed_at" // learned: box's DV decoder failed, 0 = none
    private const val KEY_P5_GLES_CORRECTION = "dv_p5_gles_correction"      // legacy: P5 GLES color path is derived now, see getP5GlesCorrection
    private const val KEY_DEFAULT_ASPECT_RATIO = "default_aspect_ratio"     // 0=fit, 1=zoom, 2=fill
    private const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_language"   // BCP-47 tag or "" for auto
    private const val KEY_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_language" // BCP-47 tag or "" for auto
    private const val KEY_HERO_TRAILER_AUTOPLAY = "hero_trailer_autoplay"
    private const val KEY_HERO_TRAILER_MUTED = "hero_trailer_muted"
    private const val KEY_USE_24H_CLOCK = "use_24h_clock"
    private const val KEY_NOTIFY_NEW_EPISODES = "new_episode_notifications"
    private const val KEY_LIVE_REMINDER_NOTIFICATIONS = "live_reminder_notifications"
    private const val KEY_SHOW_CATALOG_TYPE = "home_rail_show_catalog_type"
    private const val KEY_SHOW_ADDON_NAME = "home_rail_show_addon_name"
    private const val KEY_SEARCH_SHOW_CATALOG_TYPE = "search_rail_show_catalog_type"
    private const val KEY_SEARCH_SHOW_ADDON_NAME = "search_rail_show_addon_name"
    private const val KEY_POSTER_SIZE = "poster_size"                       // 0=small, 1=medium, 2=large
    private const val KEY_POSTER_CAPTION_TITLE = "poster_caption_title"
    private const val KEY_POSTER_CAPTION_YEAR = "poster_caption_year"
    private const val KEY_POSTER_CAPTION_RATING = "poster_caption_rating"
    // Back-compat: a key pasted into the old OMDb field pre-migration.
    private const val KEY_OMDB_API_KEY = "omdb_api_key"
    private const val KEY_MDBLIST_API_KEY = "mdblist_api_key"
    private const val KEY_OPENSUBTITLES_API_KEY = "opensubtitles_api_key"
    private const val KEY_HIDE_UPCOMING = "home_rail_hide_upcoming"
    private const val KEY_BROWSE_ENGLISH_ONLY = "browse_english_only"
    private const val KEY_LANDSCAPE_CARDS = "home_landscape_cards"
    private const val KEY_POSTER_PARTIAL_WATCH_BADGE = "poster_partial_watch_badge"
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, PREFS_NAME),
            Context.MODE_PRIVATE
        )

    // ── Buffer mode ──────────────────────────────────────────────────
    fun getDefaultBufferMode(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_BUFFER_MODE, 0)

    fun setDefaultBufferMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_BUFFER_MODE, mode).apply()
    }

    // ── Subtitle size ────────────────────────────────────────────────
    // These two sync with the rest of the display prefs, so they need the
    // tolerant dual-type read (same pattern as getPosterSize): this build
    // stores an Int, the sync applier stores a Long, and a raw getInt on a
    // Long-stored value throws — mid-recomposition in Settings or at player
    // start, which is the worst possible place for it.
    fun getDefaultSubtitleSize(context: Context): Int =
        readIntPref(context, KEY_DEFAULT_SUBTITLE_SIZE, 1)

    fun setDefaultSubtitleSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_SUBTITLE_SIZE, size).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Subtitle background ──────────────────────────────────────────
    fun getDefaultSubtitleBackground(context: Context): Int =
        readIntPref(context, KEY_DEFAULT_SUBTITLE_BG, 0)

    fun setDefaultSubtitleBackground(context: Context, bg: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_SUBTITLE_BG, bg).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Subtitle position ────────────────────────────────────────────
    // 0 = low (the placement every earlier build used), 1 = mid, 2 = high.
    // A display pref like size/background, so it rides the same sync blob.
    fun getDefaultSubtitlePosition(context: Context): Int =
        readIntPref(context, KEY_DEFAULT_SUBTITLE_POSITION, 0)

    fun setDefaultSubtitlePosition(context: Context, position: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_SUBTITLE_POSITION, position).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Auto-skip intros / credits ───────────────────────────────────
    fun getAutoSkipIntro(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SKIP_INTRO, false)

    fun setAutoSkipIntro(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SKIP_INTRO, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    fun getAutoSkipCredits(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SKIP_CREDITS, false)

    fun setAutoSkipCredits(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SKIP_CREDITS, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    /**
     * Reads an Int pref that older builds wrote with putInt and the sync
     * applier writes with putLong. SharedPreferences type-checks the cast, so
     * the mismatch is a ClassCastException rather than a wrong default.
     */
    private fun readIntPref(context: Context, key: String, fallback: Int): Int {
        val p = prefs(context)
        return try {
            p.getInt(key, fallback)
        } catch (e: ClassCastException) {
            try {
                p.getLong(key, fallback.toLong()).toInt()
            } catch (_: Exception) {
                fallback
            }
        }
    }

    // ── Auto-play next episode
    fun getAutoPlayNext(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PLAY_NEXT, false)

    fun setAutoPlayNext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PLAY_NEXT, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Auto-select top stream on streams screen
    fun getAutoSelectStream(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SELECT_STREAM, false)

    fun setAutoSelectStream(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SELECT_STREAM, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Stream ranker on/off (off = keep addon-provided order) ──────
    fun getUseStreamRanker(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_STREAM_RANKER, true)

    fun setUseStreamRanker(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_STREAM_RANKER, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Binge group (Stremio behaviorHints.bingeGroup) ───────────────
    //
    // prefer  — when the next episode's sources load, put streams that
    //           belong to the previous episode's bingeGroup first so the
    //           auto-pick continues the same link/quality.
    // reuse   — if a matching group is NOT found, keep trying the previous
    //           episode's exact stream anyway (same addon / same name) on the
    //           theory that the provider just hasn't tagged the next file.
    // fallback— if neither group-match nor reuse produced a playable stream,
    //           fall back to the normal ranked top stream instead of stopping
    //           autoplay. When reuse is on, fallback only fires after reuse
    //           itself has failed.
    fun getBingeGroupPrefer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BINGE_GROUP_PREFER, true)

    fun setBingeGroupPrefer(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BINGE_GROUP_PREFER, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    fun getBingeGroupReuse(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BINGE_GROUP_REUSE, true)

    fun setBingeGroupReuse(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BINGE_GROUP_REUSE, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    fun getBingeGroupFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BINGE_GROUP_FALLBACK, true)

    fun setBingeGroupFallback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BINGE_GROUP_FALLBACK, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── "Are you still there?" binge watchdog ────────────────────────
    // After N consecutive episodes that were auto-advanced (the user never
    // touched the remote), pause on a prompt so the app doesn't binge all
    // night unattended. Stored count lives in its own prefs file so it is
    // trivially resettable from anywhere.
    fun getStillTherePrompt(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STILL_THERE_PROMPT, true)

    fun setStillTherePrompt(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STILL_THERE_PROMPT, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    fun getStillThereEpisodes(context: Context): Long {
        val p = prefs(context)
        // Tolerant read: the sync applier writes numbers as Long, but older
        // builds wrote this key with putInt. getInt on a Long-stored value
        // (and getLong on an Int-stored one) throws ClassCastException —
        // which used to detonate mid-recomposition in Settings and kill the
        // app (Sentry ANDROID-A). Accept both storage types.
        return try {
            p.getLong(KEY_STILL_THERE_EPISODES, 3L)
        } catch (e: ClassCastException) {
            try {
                p.getInt(KEY_STILL_THERE_EPISODES, 3).toLong()
            } catch (_: Exception) {
                3L
            }
        }
    }

    fun setStillThereEpisodes(context: Context, episodes: Long) {
        prefs(context).edit().putLong(KEY_STILL_THERE_EPISODES, episodes).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── New-episode notifications ────────────────────────────────────
    // On by default: the checker only alerts for shows this profile already
    // watches, so the volume is inherently low. The toggle is synced with the
    // other behavior prefs ("am I a notifications person" is not device-
    // specific), while the per-show already-notified snapshot stays local.
    fun getNewEpisodeNotifications(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_NEW_EPISODES, true)

    fun setNewEpisodeNotifications(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_NEW_EPISODES, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    /**
     * "REMIND ME" alerts for live programmes, delivered as system
     * notifications. On by default: pressing REMIND ME in the guide is already
     * an explicit request for an alert, and until now nothing delivered it
     * outside the guide screen.
     */
    fun getLiveReminderNotifications(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIVE_REMINDER_NOTIFICATIONS, true)

    fun setLiveReminderNotifications(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_REMINDER_NOTIFICATIONS, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    private const val BINGE_STATE_PREFS = "kbstream_binge_state"
    private const val KEY_CONSECUTIVE_AUTOPLAYS = "consecutive_autoplays"

    private fun bingeStatePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, BINGE_STATE_PREFS),
            Context.MODE_PRIVATE
        )

    /** Episodes auto-advanced in a row without user input. */
    fun getConsecutiveAutoplays(context: Context): Int =
        bingeStatePrefs(context).getInt(KEY_CONSECUTIVE_AUTOPLAYS, 0)

    fun setConsecutiveAutoplays(context: Context, count: Int) {
        bingeStatePrefs(context)
            .edit().putInt(KEY_CONSECUTIVE_AUTOPLAYS, count.coerceAtLeast(0)).apply()
    }

    fun resetConsecutiveAutoplays(context: Context) {
        setConsecutiveAutoplays(context, 0)
    }

    // ── Tunneled playback ────────────────────────────────────────────
    fun getEnableTunneling(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLE_TUNNELING, false)

    fun setEnableTunneling(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_TUNNELING, enabled).apply()
    }

    // ── Force software decoder (legacy) ──────────────────────────────
    fun getForceSoftwareDecoder(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_SOFTWARE_DECODER, false)

    fun setForceSoftwareDecoder(context: Context, forced: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_SOFTWARE_DECODER, forced).apply()
    }

    // ── Picture-in-Picture ──────────────────────────────────────────
    fun getEnablePip(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLE_PIP, false)

    fun setEnablePip(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_PIP, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Decoder priority (KB-style) ───────────────────────────────
    // Audio decoder priority (KB-style): position of the FFmpeg audio
    // extension relative to MediaCodec.
    // 0 = Device decoders only (no FFmpeg audio)
    // 1 = Prefer device decoders (FFmpeg fallback behind MediaCodec)
    // 2 = Prefer app decoders (FFmpeg audio first; decodes DTS/TrueHD)
    const val AUDIO_DECODER_DEVICE_ONLY = 0
    const val AUDIO_DECODER_PREFER_DEVICE = 1
    const val AUDIO_DECODER_PREFER_APP = 2

    private fun migrateDecoderPrefs(context: Context) {
        val sp = prefs(context)
        if (sp.contains(KEY_AUDIO_DECODER)) {
            // The video decoder preference is no longer used (the bundled
            // FFmpeg build ships audio decoders only); drop the stale key.
            if (sp.contains(KEY_VIDEO_DECODER)) {
                sp.edit().remove(KEY_VIDEO_DECODER).apply()
            }
            return
        }
        // Combined builds stored one "decoder_priority" (0=device only,
        // 1=prefer device, 2=prefer app); earlier builds stored the legacy
        // "decoder_mode" toggle (0=Auto, 1=FFmpeg only). Derive the audio
        // preference from whichever exists, then drop the old keys. The
        // old "FFmpeg" choices now collapse to "prefer device" for audio
        // — the video half of those modes never worked (no video decoders
        // in the bundled FFmpeg) and the software-retry paths are gone.
        val audio = when {
            sp.contains(KEY_DECODER_PRIORITY) -> when (sp.getInt(KEY_DECODER_PRIORITY, 1)) {
                0 -> AUDIO_DECODER_DEVICE_ONLY
                2 -> AUDIO_DECODER_PREFER_APP
                else -> AUDIO_DECODER_PREFER_DEVICE
            }
            sp.contains(KEY_DECODER_MODE) -> when (sp.getInt(KEY_DECODER_MODE, 0)) {
                1 -> AUDIO_DECODER_PREFER_APP
                else -> AUDIO_DECODER_PREFER_DEVICE
            }
            else -> AUDIO_DECODER_PREFER_DEVICE
        }
        sp.edit()
            .putInt(KEY_AUDIO_DECODER, audio)
            .remove(KEY_VIDEO_DECODER)
            .remove(KEY_DECODER_PRIORITY)
            .remove(KEY_DECODER_MODE)
            .apply()
    }

    fun getAudioDecoder(context: Context): Int {
        migrateDecoderPrefs(context)
        return prefs(context).getInt(KEY_AUDIO_DECODER, AUDIO_DECODER_PREFER_DEVICE)
    }

    fun setAudioDecoder(context: Context, priority: Int) {
        prefs(context).edit().putInt(KEY_AUDIO_DECODER, priority.coerceIn(0, 2)).apply()
    }

    // ── Dolby Vision compatibility ─────────────────────────────────────
    // 0 = "P7 → 8.1" (Auto): convert dual-layer Profile 7 (dvcc-declared, or
    //     sniffed in-band on plain-HEVC remuxes) to Profile 8.1 in the
    //     bitstream so it plays through the hardware HEVC decoder on Dolby
    //     Vision displays that accept 8.1 but not Blu-ray P7. Every other DV
    //     profile (4/5/8) passes through untouched so a Dolby-Vision display
    //     plays it as real Dolby Vision — unless the P5 → 8.1 toggle below is
    //     on, which converts P5 (ICtCp) to 8.1 too.
    // 1 = None: pass streams through untouched (for devices that handle DV
    //     natively, or to compare against the default behavior).
    // 3 = Strip All: rewrite every DV profile — 4/5/7/8 — for TVs without
    //     Dolby Vision. P5 is a best-effort plain-HEVC fallback (no HDR10
    //     base, colors may be off).
    // The P5 → 8.1 conversion is an independent toggle that composes with the
    // "P7 → 8.1" mode: when on, P5 is converted to Profile 8.1 in the
    // bitstream (RPU metadata rewritten per dovi_tool convert mode 2,
    // enhancement layer dropped, single-layer VPS, codec dvhe/dvh1.08) for
    // Dolby Vision displays that accept 8.1 but not ICtCp P5. It is ignored
    // in "None" and "Strip All" modes. P5 pixels stay ICtCp in the stream —
    // the GLES shader / FFmpeg color path converts them for display. P4/P8
    // are never 8.1-converted; they follow the mode (Strip All strips them to
    // HDR10, otherwise they pass through as native DV).
    // HDR10+ (ST 2094-40) stripping is a separate toggle: getStripHdr10Plus.
    const val DV_COMPAT_AUTO = 0
    const val DV_COMPAT_OFF = 1
    // Legacy value of the old "Auto + strip HDR10+" mode. Kept so prefs from
    // earlier builds migrate cleanly — reading it now behaves as the "P7 →
    // 8.1" mode with the HDR10+ strip toggle turned on.
    const val DV_COMPAT_AUTO_HDR10_PLUS_LEGACY = 2
    const val DV_COMPAT_ALL = 3
    // Legacy value of the old combined "8.1" mode (converted both P5 and P7).
    // Reading it now migrates to the "P7 → 8.1" mode with the P5 → 8.1
    // toggle on.
    const val DV_COMPAT_TO_81 = 4

    fun getDvCompatMode(context: Context): Int {
        val p = prefs(context)
        val mode = p.getInt(KEY_DV_COMPAT_MODE, DV_COMPAT_OFF)
        if (mode == DV_COMPAT_AUTO_HDR10_PLUS_LEGACY) {
            // The old Auto+ mode was split into Auto + the HDR10+ strip toggle;
            // preserve the user's intent by migrating the stored value.
            p.edit()
                .putInt(KEY_DV_COMPAT_MODE, DV_COMPAT_AUTO)
                .putBoolean(KEY_STRIP_HDR10_PLUS, true)
                .apply()
            return DV_COMPAT_AUTO
        }
        if (mode == DV_COMPAT_TO_81) {
            // The old combined "8.1" mode converted P5 AND P7. It was replaced
            // by the "P7 → 8.1" mode (P7 conversion is implied by the mode
            // itself) plus the P5 → 8.1 toggle — P4/P8 pass through untouched,
            // exactly like 8.1 mode did.
            p.edit()
                .putInt(KEY_DV_COMPAT_MODE, DV_COMPAT_AUTO)
                .putBoolean(KEY_CONVERT_P7_TO_81, true)
                .putBoolean(KEY_CONVERT_P5_TO_81, true)
                .apply()
            return DV_COMPAT_AUTO
        }
        return mode
    }

    fun setDvCompatMode(context: Context, mode: Int) {
        prefs(context).edit()
            .putInt(KEY_DV_COMPAT_MODE, mode)
            // Touching the DV mode is the user's "try again" — drop the learned
            // device capability so the next DV title probes passthrough afresh
            // (see getDvPassthroughFailedAt).
            .remove(KEY_DV_PASSTHROUGH_FAILED_AT)
            .apply()
    }

    // ── Learned Dolby Vision capability of THIS box ───────────────────────
    // Some devices advertise a video/dolby-vision decoder, report
    // format_supported=YES, and then fail the DV decoder on the first frame
    // (TCL/Realtek: OMX_ErrorInsufficientResources, 0x80001000). That is a
    // property of the hardware, not of the file, so the player records when it
    // happened and lets the auto mode start DV titles stripped instead of
    // repeating the failed attempt on every viewing. The timestamp (not a
    // bool) keeps it self-healing: DolbyVisionCompat
    // .DV_PASSTHROUGH_FAILURE_TTL_MS decides when to probe again, and changing
    // the DV mode clears it outright.
    fun getDvPassthroughFailedAt(context: Context): Long =
        prefs(context).getLong(KEY_DV_PASSTHROUGH_FAILED_AT, 0L)

    fun setDvPassthroughFailedAt(context: Context, atMillis: Long) {
        prefs(context).edit().putLong(KEY_DV_PASSTHROUGH_FAILED_AT, atMillis).apply()
    }

    fun clearDvPassthroughFailure(context: Context) {
        if (getDvPassthroughFailedAt(context) == 0L) return
        prefs(context).edit().remove(KEY_DV_PASSTHROUGH_FAILED_AT).apply()
    }

    // ── Per-profile 8.1 conversion toggles ───────────────────────────────
    // P7 → 8.1 is now the "P7 → 8.1" mode itself (DV_COMPAT_AUTO), not a
    // separate UI toggle: it relabels the profile digits, drops the enhancement
    // layer and re-emits the RPUs as 8.1 metadata, which is a real conversion
    // because Profile 7's base layer is HDR10. The P5 toggle below composes
    // with that mode, but P5 is handled differently — see getConvertP5To81.
    // Ignored in "None" and "Strip All" modes. The stored P7 flag is kept only
    // for legacy-pref migration (the old combined "8.1" mode); DV_COMPAT_AUTO
    // always implies P7 → 8.1.
    fun getConvertP7To81(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONVERT_P7_TO_81, false)

    fun setConvertP7To81(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONVERT_P7_TO_81, enabled).apply()
    }

    /**
     * Effective state of the P5 conversion: "this session rewrites Profile 5
     * tracks".
     *
     * Profile 5 is single-layer ICtCp and has no HDR10 base layer, so no
     * bitstream-level rewrite can change its pixels — showing them as Rec.2020
     * PQ is what a green and purple picture is. The conversion is therefore only
     * ever done as a *strip* to plain HEVC with the GPU color path engaged (see
     * [getP5GlesCorrection] and p5GlesPathWanted in the player), never as the
     * Profile 8.1 relabel it used to be.
     *
     * That conversion is required, not optional, on a device with no Dolby
     * Vision decoder to play P5 for us: such a box renders ICtCp as Rec.2020 PQ
     * however the track is advertised, so passing it through cannot produce a
     * correct picture. On a device that does advertise a Dolby Vision decoder,
     * only the user's toggle triggers it — P5 otherwise plays natively as Dolby
     * Vision, which is the best picture that device can show. (Ignored in
     * "None" — byte-exact playback — and in "Strip All", which converts P5
     * anyway.)
     */
    fun getConvertP5To81(context: Context): Boolean {
        if (prefs(context).getBoolean(KEY_CONVERT_P5_TO_81, false)) return true
        return isP5ConversionRequired(context)
    }

    /**
     * True when this device cannot play Profile 5 as provided — it advertises no
     * Dolby Vision decoder, so the ICtCp planes have to be converted on the GPU.
     * The P5 conversion is implied (and its settings switch locked on) whenever
     * this holds, because passthrough cannot produce a correct picture here:
     * ICtCp rendered as Rec.2020 PQ is green and purple.
     */
    fun isP5ConversionRequired(context: Context): Boolean =
        getDvCompatMode(context) == DV_COMPAT_AUTO &&
            !com.kennyb1201.kbstream.ui.player.DolbyVisionCompat.supportsNativeDolbyVision()

    fun setConvertP5To81(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONVERT_P5_TO_81, enabled).apply()
    }

    /**
     * Effective state of the P5 raw-plane GLES color path. It is no longer a
     * user choice — there is no settings switch for it any more, because as a
     * standalone toggle it could only misfire:
     *
     *  - with P5 left as Dolby Vision there is no stripped HEVC track for the
     *    plane renderer to claim (it only takes video/hevc), so the switch did
     *    nothing; and
     *  - in "None" it hid the player view with nothing able to feed the GL
     *    view — a black screen with audio.
     *
     * So it runs exactly while a P5 conversion does: Strip All, the P5 → HDR10
     * toggle, or a device with no Dolby Vision decoder to play Profile 5 (see
     * [getConvertP5To81]). The stored legacy flag is intentionally ignored; the
     * key is kept only so an existing install's prefs stay readable.
     */
    fun getP5GlesCorrection(context: Context): Boolean {
        val mode = getDvCompatMode(context)
        if (mode == DV_COMPAT_OFF) return false
        return mode == DV_COMPAT_AUTO && getConvertP5To81(context)
    }

    /**
     * Legacy writer for the removed P5 color-path switch. Kept so older builds
     * (and any stored preference) stay compatible; nothing reads it.
     */
    fun setP5GlesCorrection(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_P5_GLES_CORRECTION, enabled).apply()
    }

    // ── HDR10+ (ST 2094-40) stripping ──────────────────────────────────
    // Independent of the DV mode above: removes dynamic HDR10+ metadata from
    // plain-HEVC (HDR10+ without DV) and DV-converted streams, for displays
    // that black-screen on HDR10+ content. Works alongside the P7 → 8.1 and
    // Strip All modes; in None mode it strips HDR10+ only and never touches DV.
    fun getStripHdr10Plus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRIP_HDR10_PLUS, false)

    fun setStripHdr10Plus(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRIP_HDR10_PLUS, enabled).apply()
    }

    // ── Default aspect ratio ─────────────────────────────────────────
    fun getDefaultAspectRatio(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_ASPECT_RATIO, 0)

    fun setDefaultAspectRatio(context: Context, ratio: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_ASPECT_RATIO, ratio).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Preferred audio language ──────────────────────────────────────
    // Empty string = auto (let ExoPlayer decide)
    fun getPreferredAudioLanguage(context: Context): String =
        prefs(context).getString(KEY_PREFERRED_AUDIO_LANG, "") ?: ""

    fun setPreferredAudioLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_PREFERRED_AUDIO_LANG, lang).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Preferred subtitle language ───────────────────────────────────
    // Empty string = auto (don't force any subtitle)
    fun getPreferredSubtitleLanguage(context: Context): String =
        prefs(context).getString(KEY_PREFERRED_SUBTITLE_LANG, "") ?: ""

    fun setPreferredSubtitleLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_PREFERRED_SUBTITLE_LANG, lang).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Hero trailer autoplay (Home hero) ─────────────────────────────
    fun getHeroTrailerAutoplay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HERO_TRAILER_AUTOPLAY, true)

    fun setHeroTrailerAutoplay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HERO_TRAILER_AUTOPLAY, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Hero trailer audio (Home hero) ─────────────────────────────
    // Default is sound ON — this only exists to let users silence the
    // hero trailers entirely while keeping the visual autoplay.
    fun getHeroTrailerMuted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HERO_TRAILER_MUTED, false)

    fun setHeroTrailerMuted(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HERO_TRAILER_MUTED, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── 24-hour clock (player overlay clock) ──────────────────────────
    fun getUse24HourClock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_24H_CLOCK, false)

    fun setUse24HourClock(context: Context, enabled: Boolean) {
        syncDisplayPrefsBlob(context)
        prefs(context).edit().putBoolean(KEY_USE_24H_CLOCK, enabled).apply()
    }

    // ── AMOLED black theme ─────────────────────────────────────
    private const val KEY_AMOLED_BLACK = "amoled_black"

    /** Reads the pref AND mirrors it into the theme's live state. */
    fun getAmoledBlack(context: Context): Boolean {
        val value = prefs(context).getBoolean(KEY_AMOLED_BLACK, false)
        com.kennyb1201.kbstream.ui.theme.kbAmoledBlackState.value = value
        return value
    }

    fun setAmoledBlack(context: Context, enabled: Boolean) {
        com.kennyb1201.kbstream.ui.theme.kbAmoledBlackState.value = enabled
        prefs(context).edit().putBoolean(KEY_AMOLED_BLACK, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Pure black surface (cards/panels/containers join the background) ──
    private const val KEY_PURE_BLACK_SURFACE = "pure_black_surface"

    /** Reads the pref AND mirrors it into the theme's live state. */
    fun getPureBlackSurface(context: Context): Boolean {
        val value = prefs(context).getBoolean(KEY_PURE_BLACK_SURFACE, false)
        com.kennyb1201.kbstream.ui.theme.kbPureBlackSurfaceState.value = value
        return value
    }

    fun setPureBlackSurface(context: Context, enabled: Boolean) {
        com.kennyb1201.kbstream.ui.theme.kbPureBlackSurfaceState.value = enabled
        prefs(context).edit().putBoolean(KEY_PURE_BLACK_SURFACE, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Home rail titles: show catalog type ───────────────────────────
    fun getHomeRailShowCatalogType(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CATALOG_TYPE, false)

    fun setHomeRailShowCatalogType(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CATALOG_TYPE, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Home rail titles: show addon name ─────────────────────────────
    fun getHomeRailShowAddonName(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ADDON_NAME, false)

    fun setHomeRailShowAddonName(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ADDON_NAME, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Search rails (add-on search only): show catalog type ──────────
    // Governs the addon search rails on the Search screen (AIOMetadata,
    // Cinemeta, ...) — NOT the built-in TMDB results, which never carry a
    // catalog type. Mirrors the home-rail toggle.
    fun getSearchRailShowCatalogType(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEARCH_SHOW_CATALOG_TYPE, false)

    fun setSearchRailShowCatalogType(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEARCH_SHOW_CATALOG_TYPE, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Search rails (add-on search only): show addon name ────────────
    fun getSearchRailShowAddonName(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEARCH_SHOW_ADDON_NAME, false)

    fun setSearchRailShowAddonName(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEARCH_SHOW_ADDON_NAME, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Poster size (all screens) ─────────────────────────────────────
    // 0=small 110x165, 1=medium 124x186 (default), 2=large 140x210. Stored
    // as a Long because the display-prefs sync blob round-trips numbers as
    // Long on the receiving device.
    fun getPosterSize(context: Context): Long {
        val p = prefs(context)
        // Same tolerant dual-type read as getStillThereEpisodes: legacy
        // builds stored this with putInt, the sync applier stores Long.
        return try {
            p.getLong(KEY_POSTER_SIZE, 1L)
        } catch (e: ClassCastException) {
            try {
                p.getInt(KEY_POSTER_SIZE, 1).toLong()
            } catch (_: Exception) {
                1L
            }
        }
    }

    fun setPosterSize(context: Context, size: Long) {
        prefs(context).edit().putLong(KEY_POSTER_SIZE, size).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Poster captions (all screens except Home rails) ───────────────
    // Each screen shows only what its data source provides: search tiles
    // honor all three, collection tiles honor title/year, etc. Home rails
    // are poster-only and unaffected.
    fun getPosterCaptionTitle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POSTER_CAPTION_TITLE, true)

    fun setPosterCaptionTitle(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POSTER_CAPTION_TITLE, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    fun getPosterCaptionYear(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POSTER_CAPTION_YEAR, true)

    fun setPosterCaptionYear(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POSTER_CAPTION_YEAR, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    fun getPosterCaptionRating(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POSTER_CAPTION_RATING, true)

    fun setPosterCaptionRating(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POSTER_CAPTION_RATING, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── MDBList API key (critic ratings: IMDb / RT / Metacritic / more) ─
    // Stored here so the user can paste their mdblist.com key without
    // rebuilding. The build-time BuildConfig key (local.properties / env)
    // takes precedence when present. A key left over in the old OMDb slot
    // migrates once so nobody silently loses their ratings row.
    fun getMdbListApiKey(context: Context): String {
        val current = prefs(context).getString(KEY_MDBLIST_API_KEY, "")?.trim().orEmpty()
        if (current.isNotBlank()) return current

        val legacyOmdb = prefs(context).getString(KEY_OMDB_API_KEY, "")?.trim().orEmpty()
        if (legacyOmdb.isNotBlank()) {
            prefs(context).edit().putString(KEY_MDBLIST_API_KEY, legacyOmdb).apply()
            return legacyOmdb
        }
        return ""
    }

    fun setMdbListApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_MDBLIST_API_KEY, key.trim()).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── OpenSubtitles API key (in-player online subtitle search) ─────
    // Free key from opensubtitles.com; synced like the OMDb key so every
    // device gets the player's SEARCH SUBTITLES entry.
    fun getOpensubtitlesApiKey(context: Context): String =
        prefs(context).getString(KEY_OPENSUBTITLES_API_KEY, "")?.trim().orEmpty()

    fun setOpensubtitlesApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_OPENSUBTITLES_API_KEY, key.trim()).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Home rails: hide not-yet-released titles (digital filter) ─────
    fun getHomeRailHideUpcoming(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_UPCOMING, false)

    fun setHomeRailHideUpcoming(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_UPCOMING, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Browse & discover: English-only catalogs ─────────────────────
    //
    // On by default. Every discover rail behind the Search browse chips then
    // sends with_original_language=en, so a Studio/Service/Genre/Keyword/
    // Decade page is an English catalog instead of a worldwide one — which is
    // what the rails kept surfacing (foreign broadcasters' slates, anime
    // shorts, non-US soap operas) no matter how the sort was floored.
    //
    // Off is the escape hatch for the categories where English-only is
    // actively wrong: Animation (anime is Japanese), and the Spanish-language
    // networks (Telemundo, Univision) whose catalog is Spanish by definition.
    fun getBrowseEnglishOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BROWSE_ENGLISH_ONLY, true)

    fun setBrowseEnglishOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BROWSE_ENGLISH_ONLY, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Home rails: landscape backdrop cards instead of posters ───────
    fun getHomeLandscapeCards(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LANDSCAPE_CARDS, false)

    fun setHomeLandscapeCards(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LANDSCAPE_CARDS, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    // ── Poster eye badge: shows started-but-not-finished shows ────────
    fun getPosterPartialWatchBadge(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POSTER_PARTIAL_WATCH_BADGE, true)

    fun setPosterPartialWatchBadge(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POSTER_PARTIAL_WATCH_BADGE, enabled).apply()
        syncDisplayPrefsBlob(context)
    }

    /**
     * Cross-device sync: called by every setter of a SYNCED pref. Debounced
     * inside SupabaseSync (outbox coalescing), so spamming toggles is cheap.
     */
    internal fun syncDisplayPrefsBlob(context: Context) {
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_DISPLAY_PREFS,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildDisplayPrefs(appContext)
            )
        }
    }
}
