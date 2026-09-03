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
    private const val KEY_DECODER_MODE = "decoder_mode"                      // 0=auto, 1=ffmpeg-only
    private const val KEY_DV_COMPAT_MODE = "dv_compat_mode"                  // 0=auto, 1=off, 2=auto+hdr10+
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

    // ── Decoder mode ────────────────────────────────────────────────
    // 0 = Auto (HW first, FFmpeg fallback)
    // 1 = FFmpeg only (software decoder for everything)
    fun getDecoderMode(context: Context): Int =
        prefs(context).getInt(KEY_DECODER_MODE, 0)

    fun setDecoderMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_DECODER_MODE, mode).apply()
    }

    // ── Dolby Vision compatibility ─────────────────────────────────────
    // 0 = Auto: rewrite DV Profile 7 remuxes (dvcc-declared or sniffed
    //     in-band) to HDR10 so they play through the hardware HEVC decoder.
    // 1 = Off: pass streams through untouched (for devices that handle DV
    //     natively, or to compare against the default behavior).
    // 2 = Auto + also strip HDR10+ SEI (for TVs that black-screen on HDR10+).
    const val DV_COMPAT_AUTO = 0
    const val DV_COMPAT_OFF = 1
    const val DV_COMPAT_AUTO_HDR10_PLUS = 2

    fun getDvCompatMode(context: Context): Int =
        prefs(context).getInt(KEY_DV_COMPAT_MODE, DV_COMPAT_AUTO)

    fun setDvCompatMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_DV_COMPAT_MODE, mode).apply()
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
