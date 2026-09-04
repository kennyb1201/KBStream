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
    private const val KEY_VIDEO_DECODER = "video_decoder"
    private const val KEY_AUDIO_DECODER = "audio_decoder"                      // 0=auto, 1=ffmpeg-only
    private const val KEY_DV_COMPAT_MODE = "dv_compat_mode"                  // 0=auto, 1=off, 3=all (2=legacy auto+hdr10+)
    private const val KEY_STRIP_HDR10_PLUS = "strip_hdr10_plus"             // independent of the DV mode
    private const val KEY_DEFAULT_ASPECT_RATIO = "default_aspect_ratio"     // 0=fit, 1=zoom, 2=fill
    private const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_language"   // BCP-47 tag or "" for auto
    private const val KEY_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_language" // BCP-47 tag or "" for auto

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Buffer mode ──────────────────────────────────────────────────
    fun getDefaultBufferMode(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_BUFFER_MODE, 2)

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
    // Controls whether hardware or software (FFmpeg) decoders are used for
    // audio, and whether the software video renderer is available:
    // 0 = Device decoders only (pure MediaCodec, no FFmpeg anywhere)
    // 1 = Prefer device decoders (hardware first, FFmpeg fallback)
    // 2 = Prefer app decoders / FFmpeg (FFmpeg audio first, software video)
    // Video decoder: which renderer family produces video frames.
    // 0 = Prefer device (hardware first, FFmpeg video as fallback)
    // 1 = FFmpeg software video (auto-uses hardware where software can't help)
    const val VIDEO_DECODER_PREFER_DEVICE = 0
    const val VIDEO_DECODER_FFMPEG = 1
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
        if (sp.contains(KEY_VIDEO_DECODER) && sp.contains(KEY_AUDIO_DECODER)) return
        // Combined builds stored one "decoder_priority" (0=device only,
        // 1=prefer device, 2=prefer app); earlier builds stored the legacy
        // "decoder_mode" toggle (0=Auto, 1=FFmpeg only). Derive both split
        // preferences from whichever exists, then drop the old keys.
        var video = VIDEO_DECODER_PREFER_DEVICE
        var audio = AUDIO_DECODER_PREFER_DEVICE
        when {
            sp.contains(KEY_DECODER_PRIORITY) -> when (sp.getInt(KEY_DECODER_PRIORITY, 1)) {
                0 -> audio = AUDIO_DECODER_DEVICE_ONLY
                2 -> {
                    video = VIDEO_DECODER_FFMPEG
                    audio = AUDIO_DECODER_PREFER_APP
                }
                else -> audio = AUDIO_DECODER_PREFER_DEVICE
            }
            sp.contains(KEY_DECODER_MODE) -> when (sp.getInt(KEY_DECODER_MODE, 0)) {
                1 -> {
                    video = VIDEO_DECODER_FFMPEG
                    audio = AUDIO_DECODER_PREFER_APP
                }
                else -> audio = AUDIO_DECODER_PREFER_DEVICE
            }
        }
        sp.edit()
            .putInt(KEY_VIDEO_DECODER, video)
            .putInt(KEY_AUDIO_DECODER, audio)
            .remove(KEY_DECODER_PRIORITY)
            .remove(KEY_DECODER_MODE)
            .apply()
    }

    fun getVideoDecoder(context: Context): Int {
        migrateDecoderPrefs(context)
        return prefs(context).getInt(KEY_VIDEO_DECODER, VIDEO_DECODER_PREFER_DEVICE)
    }

    fun setVideoDecoder(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_VIDEO_DECODER, mode.coerceIn(0, 1)).apply()
    }

    fun getAudioDecoder(context: Context): Int {
        migrateDecoderPrefs(context)
        return prefs(context).getInt(KEY_AUDIO_DECODER, AUDIO_DECODER_PREFER_DEVICE)
    }

    fun setAudioDecoder(context: Context, priority: Int) {
        prefs(context).edit().putInt(KEY_AUDIO_DECODER, priority.coerceIn(0, 2)).apply()
    }

    // ── Dolby Vision compatibility ─────────────────────────────────────
    // 0 = Auto: rewrite only dual-layer Profile 7 (dvcc-declared, or sniffed
    //     in-band on plain-HEVC remuxes) to HDR10 so it plays through the
    //     hardware HEVC decoder. Every other DV profile (4/5/8) passes through
    //     untouched so a Dolby-Vision display plays it as real Dolby Vision.
    // 1 = Off: pass streams through untouched (for devices that handle DV
    //     natively, or to compare against the default behavior).
    // 3 = All: rewrite every DV profile — 4/5/7/8 — for TVs without Dolby
    //     Vision. P5 is a best-effort plain-HEVC fallback (no HDR10 base,
    //     colors may be off).
    // HDR10+ (ST 2094-40) stripping is a separate toggle: getStripHdr10Plus.
    const val DV_COMPAT_AUTO = 0
    const val DV_COMPAT_OFF = 1
    // Legacy value of the old "Auto + strip HDR10+" mode. Kept so prefs from
    // earlier builds migrate cleanly — reading it now behaves as Auto with the
    // HDR10+ strip toggle turned on.
    const val DV_COMPAT_AUTO_HDR10_PLUS_LEGACY = 2
    const val DV_COMPAT_ALL = 3

    fun getDvCompatMode(context: Context): Int {
        val p = prefs(context)
        val mode = p.getInt(KEY_DV_COMPAT_MODE, DV_COMPAT_AUTO)
        if (mode == DV_COMPAT_AUTO_HDR10_PLUS_LEGACY) {
            // The old Auto+ mode was split into Auto + the HDR10+ strip toggle;
            // preserve the user's intent by migrating the stored value.
            p.edit()
                .putInt(KEY_DV_COMPAT_MODE, DV_COMPAT_AUTO)
                .putBoolean(KEY_STRIP_HDR10_PLUS, true)
                .apply()
            return DV_COMPAT_AUTO
        }
        return mode
    }

    fun setDvCompatMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_DV_COMPAT_MODE, mode).apply()
    }

    // ── HDR10+ (ST 2094-40) stripping ──────────────────────────────────
    // Independent of the DV mode above: removes dynamic HDR10+ metadata from
    // plain-HEVC (HDR10+ without DV) and DV-converted streams, for displays
    // that black-screen on HDR10+ content. Works alongside Auto/All DV; in
    // Off mode it strips HDR10+ only and never touches DV.
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
}
