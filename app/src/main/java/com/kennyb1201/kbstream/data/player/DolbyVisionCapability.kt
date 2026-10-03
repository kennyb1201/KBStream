package com.kennyb1201.kbstream.data.player

import android.media.MediaCodecList

/**
 * Whether this device advertises a Dolby Vision decoder to MediaCodec — a
 * property of the box, not of the file, so it belongs in the data layer where
 * both the settings store and the player can read it.
 *
 * On such devices single-layer DV (Profile 4 / Profile 8) decodes natively
 * through the platform's Dolby Vision pipeline — the platform handles sink
 * downconversion (DV → HDR10/SDR on non-DV displays), so re-advertising the
 * track as plain hvc1 and stripping the RPU NALs is unnecessary, and on
 * MediaTek-class DV hardware it is actively harmful: the HEVC decoder accepts
 * the mutated stream, sits waiting for RPUs that never arrive, and never
 * outputs a frame (configure OK, zero output).
 *
 * IMPORTANT: a DV decoder on the box does not mean the display can show DV.
 * The player's "Strip All" mode overrides this probe — the user has told us the
 * display has no Dolby Vision, so P4/P8 are stripped to HDR10 even on
 * DV-capable devices (e.g. Fire TV Sticks, where the DV pipeline does not
 * reliably downconvert for non-DV TVs and P8 black-screens). Returns false on
 * any codec-list read failure so callers keep their non-DV fallback behavior.
 *
 * Cached: walking MediaCodecList is not free, the codec list does not change
 * while the process lives, and the callers include per-stream playback
 * decisions and the settings screen, so it is evaluated once.
 */
object DolbyVisionCapability {

    val supportsNativeDolbyVision: Boolean by lazy {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var found = false
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals("video/dolby-vision", ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
            found
        } catch (_: Exception) {
            false
        }
    }
}
