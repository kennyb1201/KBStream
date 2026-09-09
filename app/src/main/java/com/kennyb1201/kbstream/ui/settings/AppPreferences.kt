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
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
    private const val KEY_AUTO_SELECT_STREAM = "auto_select_stream"
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
    private const val KEY_DEFAULT_ASPECT_RATIO = "default_aspect_ratio"     // 0=fit, 1=zoom, 2=fill
    private const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_language"   // BCP-47 tag or "" for auto
    private const val KEY_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_language" // BCP-47 tag or "" for auto
    private const val KEY_HERO_TRAILER_AUTOPLAY = "hero_trailer_autoplay"
    private const val KEY_USE_24H_CLOCK = "use_24h_clock"
    private const val KEY_SHOW_CATALOG_TYPE = "home_rail_show_catalog_type"
    private const val KEY_SHOW_ADDON_NAME = "home_rail_show_addon_name"
    private const val KEY_HIDE_UPCOMING = "home_rail_hide_upcoming"
    private const val KEY_LANDSCAPE_CARDS = "home_landscape_cards"
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Buffer mode ──────────────────────────────────────────────────
    fun getDefaultBufferMode(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_BUFFER_MODE, 0)

    fun setDefaultBufferMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_BUFFER_MODE, mode).apply()
    }

    // ── Subtitle size ────────────────────────────────────────────────
    fun getDefaultSubtitleSize(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_SUBTITLE_SIZE, 1)

    fun setDefaultSubtitleSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_SUBTITLE_SIZE, size).apply()
    }

    // ── Subtitle background ──────────────────────────────────────────
    fun getDefaultSubtitleBackground(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_SUBTITLE_BG, 0)

    fun setDefaultSubtitleBackground(context: Context, bg: Int) {
        prefs(context).edit().putInt(KEY_DEFAULT_SUBTITLE_BG, bg).apply()
    }

    // ── Auto-play next episode
    fun getAutoPlayNext(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PLAY_NEXT, false)

    fun setAutoPlayNext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PLAY_NEXT, enabled).apply()
    }

    // ── Auto-select top stream on streams screen
    fun getAutoSelectStream(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SELECT_STREAM, false)

    fun setAutoSelectStream(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SELECT_STREAM, enabled).apply()
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
    }

    // ── Decoder priority (Nuvio-style) ───────────────────────────────
    // Audio decoder priority (Nuvio-style): position of the FFmpeg audio
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
        prefs(context).edit().putInt(KEY_DV_COMPAT_MODE, mode).apply()
    }

    // ── Per-profile 8.1 conversion toggles ───────────────────────────────
    // P7 → 8.1 is now the "P7 → 8.1" mode itself (DV_COMPAT_AUTO), not a
    // separate UI toggle. The P5 → 8.1 toggle below composes with that mode:
    // when on, declared P5 (ICtCp) streams are converted to Profile 8.1 in the
    // bitstream instead of passing through as native DV — for Dolby Vision
    // displays that accept 8.1 but not ICtCp P5. Ignored in "None" and
    // "Strip All" modes. The stored P7 flag is kept only for legacy-pref
    // migration (the old combined "8.1" mode); DV_COMPAT_AUTO always implies
    // P7 → 8.1.
    fun getConvertP7To81(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONVERT_P7_TO_81, false)

    fun setConvertP7To81(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONVERT_P7_TO_81, enabled).apply()
    }

    fun getConvertP5To81(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONVERT_P5_TO_81, false)

    fun setConvertP5To81(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONVERT_P5_TO_81, enabled).apply()
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
    }

    // ── Preferred audio language ──────────────────────────────────────
    // Empty string = auto (let ExoPlayer decide)
    fun getPreferredAudioLanguage(context: Context): String =
        prefs(context).getString(KEY_PREFERRED_AUDIO_LANG, "") ?: ""

    fun setPreferredAudioLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_PREFERRED_AUDIO_LANG, lang).apply()
    }

    // ── Preferred subtitle language ───────────────────────────────────
    // Empty string = auto (don't force any subtitle)
    fun getPreferredSubtitleLanguage(context: Context): String =
        prefs(context).getString(KEY_PREFERRED_SUBTITLE_LANG, "") ?: ""

    fun setPreferredSubtitleLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_PREFERRED_SUBTITLE_LANG, lang).apply()
    }

    // ── Hero trailer autoplay (Home hero) ─────────────────────────────
    fun getHeroTrailerAutoplay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HERO_TRAILER_AUTOPLAY, true)

    fun setHeroTrailerAutoplay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HERO_TRAILER_AUTOPLAY, enabled).apply()
    }

    // ── 24-hour clock (player overlay clock) ──────────────────────────
    fun getUse24HourClock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_24H_CLOCK, false)

    fun setUse24HourClock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_24H_CLOCK, enabled).apply()
    }

    // ── Home rail titles: show catalog type ───────────────────────────
    fun getHomeRailShowCatalogType(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CATALOG_TYPE, false)

    fun setHomeRailShowCatalogType(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CATALOG_TYPE, enabled).apply()
    }

    // ── Home rail titles: show addon name ─────────────────────────────
    fun getHomeRailShowAddonName(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ADDON_NAME, false)

    fun setHomeRailShowAddonName(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ADDON_NAME, enabled).apply()
    }

    // ── Home rails: hide not-yet-released titles (digital filter) ─────
    fun getHomeRailHideUpcoming(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_UPCOMING, false)

    fun setHomeRailHideUpcoming(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_UPCOMING, enabled).apply()
    }

    // ── Home rails: landscape backdrop cards instead of posters ───────
    fun getHomeLandscapeCards(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LANDSCAPE_CARDS, false)

    fun setHomeLandscapeCards(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LANDSCAPE_CARDS, enabled).apply()
    }
}
