package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.os.Build
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * Which engine plays a title, and whether the backup engine may take over.
 *
 * ExoPlayer is the primary engine: it is the one wired into the media session,
 * the Dolby Vision layers, the track memory and the whole player panel. MPV is
 * the *backup* — it exists for the cases ExoPlayer cannot solve on the device
 * at all, in particular:
 *
 *  - "no decoder resources available" (OMX_ErrorInsufficientResources,
 *    0x80001000). Once one video decoder on the process fails that way the box
 *    returns it for every later MediaCodec decoder too, and ExoPlayer has no
 *    way back; mpv can decode the file in software instead.
 *  - files MediaCodec has no decoder for (exotic codecs, some 10-bit/4:4:4
 *    profiles), which mpv's bundled FFmpeg handles.
 *  - fansub ASS/SSA typesetting, which mpv renders through libass.
 *
 * Two places use this:
 *
 *  1. A launch: MainActivity asks [prefersMpv] to decide whether to open MPV
 *     straight away (the "MPV" setting).
 *  2. A failure: NativePlayerActivity asks [mpvFallbackEnabled] before handing
 *     its own launch intent to the MPV player (the default setting).
 */
object PlayerEngine {

    /**
     * libmpv's own floor. The bundled artifact declares minSdk 26 and the app
     * declares 23, so the manifest overrides the library's floor for the
     * merger; this constant is what actually keeps the native libraries off a
     * device they were not built for. Nothing may call into MPV without
     * checking this first.
     */
    const val MIN_SDK = Build.VERSION_CODES.O

    /** Whether this device can run the MPV engine at all. */
    fun isMpvAvailable(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    /**
     * The stored choice, coerced to something this device can actually run: a
     * Fire OS 6 box (API 25) has no libmpv, so a stored "MPV" reads back as
     * ExoPlayer there instead of being honoured at playback time and dying on
     * a missing native library.
     */
    fun selected(context: Context): Int {
        val chosen = AppPreferences.getPlayerEngine(context)
        return if (chosen == AppPreferences.PLAYER_ENGINE_MPV && !isMpvAvailable()) {
            AppPreferences.PLAYER_ENGINE_EXO
        } else {
            chosen
        }
    }

    /** True when a launch should open the MPV player instead of ExoPlayer. */
    fun prefersMpv(context: Context): Boolean =
        selected(context) == AppPreferences.PLAYER_ENGINE_MPV

    /**
     * True when ExoPlayer may hand a stream it cannot play over to MPV.
     *
     * This is the default: the fallback is the point of the setting, and the
     * one choice that disables it (ExoPlayer only) exists because a mid-title
     * engine change is a visible thing to have happen without asking.
     */
    fun mpvFallbackEnabled(context: Context): Boolean =
        isMpvAvailable() && selected(context) != AppPreferences.PLAYER_ENGINE_EXO_ONLY

    /** Human-readable name of the engine a launch will use here. */
    fun displayName(context: Context): String =
        if (prefersMpv(context)) "MPV" else "ExoPlayer"
}
