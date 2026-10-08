package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadge

/*
 * Format / codec / header helpers shared by the native player and the Compose
 * paths that draw its panels. These carry no player state on purpose - they are
 * pure functions over a url, a stream or a codec string - so they live apart
 * from [NativePlayerActivity] instead of inside its 10k-line body.
 */

// --- Utility Functions (shared with Compose path) ---

internal fun resolveMimeType(url: String): String? {
    val lower = url.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    return when {
        ".m3u8" in path -> MimeTypes.APPLICATION_M3U8
        // Extension-less HLS. Many CDN/hosted playlists carry the marker only
        // in the query (`.../stream?type=.m3u8`) or under an `/hls/` path
        // segment with no file extension at all. Those reached Media3 as an
        // unknown type, were handed to the progressive extractors, and the
        // playlist text was parsed as raw media -- the stream stalled on the
        // first frame even though the very same URL plays elsewhere.
        "m3u8" in lower -> MimeTypes.APPLICATION_M3U8
        "/hls/" in path -> MimeTypes.APPLICATION_M3U8
        ".mpd" in path -> MimeTypes.APPLICATION_MPD
        // Microsoft Smooth Streaming: the manifest lives at
        // ".../stream.ism/Manifest" (live publishing points use .isml, hence the
        // substring test), and there is no file extension anywhere in that URL
        // to key off - so without this it reached Media3 as an unknown type and
        // was handed to the progressive extractors. A bare "/manifest" is
        // deliberately NOT matched: HLS and DASH servers name their playlist
        // that too, and a wrong SS guess would break a stream that plays.
        ".ism" in path -> MimeTypes.APPLICATION_SS
        ".mp4" in path || ".m4v" in path -> MimeTypes.VIDEO_MP4
        ".mkv" in path || ".webm" in path -> MimeTypes.VIDEO_MATROSKA
        ".ts" in path -> MimeTypes.VIDEO_MP2T
        ".flv" in path -> MimeTypes.VIDEO_FLV
        ".mov" in path -> MimeTypes.VIDEO_MP4
        ".aac" in path -> MimeTypes.AUDIO_AAC
        ".mp3" in path -> MimeTypes.AUDIO_MPEG
        ".flac" in path -> MimeTypes.AUDIO_FLAC
        ".opus" in path -> MimeTypes.AUDIO_OPUS
        ".ogg" in path -> MimeTypes.AUDIO_OGG
        ".wav" in path -> MimeTypes.AUDIO_WAV
        else -> null
    }
}

/**
 * The one glyph for "there is no duration yet". Both players paint their clock
 * labels before a duration has arrived, and "00:00" there claimed the title was
 * zero seconds long until the first progress tick corrected it.
 */
internal const val UNKNOWN_DURATION_CLOCK = "--:--"

/**
 * Duration readout: an unknown or unset duration is [UNKNOWN_DURATION_CLOCK],
 * never a lying 00:00. Positions call [formatMillis] directly - a position of
 * zero really is zero.
 */
internal fun formatDurationMillis(ms: Long): String =
    if (ms <= 0L) UNKNOWN_DURATION_CLOCK else formatMillis(ms)

internal fun formatMillis(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

internal fun Stream.displayLabel(): String = listOfNotNull(
    name?.takeIf { it.isNotBlank() },
    title?.takeIf { it.isNotBlank() },
    description?.takeIf { it.isNotBlank() }
).distinct().joinToString(" • ").ifBlank {
    url?.substringAfterLast('/').orEmpty().substringBefore('?').takeIf { it.isNotBlank() }
        ?: "Current source"
}

/** Badge chips for one stream, from the sources_json payload. */
internal fun parseStreamBadges(array: org.json.JSONArray?): List<StreamBadge> {
    if (array == null || array.length() == 0) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name", "")
        val imageURL = obj.optString("imageURL", "")
        if (name.isBlank() && imageURL.isBlank()) return@mapNotNull null
        StreamBadge(
            name = name,
            imageURL = imageURL,
            tagColor = obj.optString("tagColor", ""),
            tagStyle = obj.optString("tagStyle", ""),
            textColor = obj.optString("textColor", ""),
            borderColor = obj.optString("borderColor", "")
        )
    }
}

internal fun resolveSubtitleMimeType(uri: Uri): String {
    val name = uri.lastPathSegment.orEmpty().lowercase()
    return when {
        name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/**
 * Cast member data class used by both native and compose player paths.
 */
data class PlayerCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

/**
 * How much of a stream must have PLAYED before a measured bitrate means
 * anything.
 *
 * The measurement divides bytes fetched by playback position, and in the first
 * seconds the buffer is being filled: a player that has pulled thirty seconds
 * of data in five seconds of playback is not describing a stream thirty times
 * its rate, it is describing a fast connection. Fifteen seconds of playback is
 * long enough for the fetches to average out to the file's own rate.
 */
internal const val MEASURED_BITRATE_MIN_POSITION_MS = 15_000L

/**
 * The bitrate of what is playing, measured from the bytes the player has
 * actually fetched for the video track.
 *
 * Many progressive and hoster links declare no bitrate at all - the container
 * carries no such field, which is why `Format.bitrate` arrives as media3's
 * NO_VALUE and the info panel's "Bitrate" line simply did not appear for them.
 * Bytes over played time is the same number the file itself would report, and
 * it exists for every source that plays: the video track's loaded bytes summed
 * over its loads, divided by the playback position.
 *
 * Zero means "not measurable yet" - no bytes, or less than
 * [MEASURED_BITRATE_MIN_POSITION_MS] of playback to average against - and
 * callers show nothing rather than a number they would have to take back.
 */
internal fun measuredBitrateBps(bytesLoaded: Long, positionMs: Long): Long =
    if (bytesLoaded <= 0L || positionMs < MEASURED_BITRATE_MIN_POSITION_MS) 0L
    else bytesLoaded * 8_000L / positionMs

/**
 * The info panel's bitrate line, or null when there is nothing honest to say.
 *
 * The container's declared rate wins when it has one: it is exact, and it is
 * what the file's own metadata says. Only when the source declares none is the
 * measured rate shown, and it says so - a viewer comparing two sources must be
 * able to tell a number read off the file from one this device worked out.
 * Bits are rendered as kbps, matching the panel's other rate rows.
 */
internal fun bitrateLabel(declaredBps: Int, measuredBps: Long): String? = when {
    declaredBps > 0 -> "Bitrate: ${declaredBps / 1_000} kbps"
    measuredBps > 0 -> "Bitrate: ${measuredBps / 1_000} kbps (measured)"
    else -> null
}

/**
 * Normalize raw pixel height into a human-friendly label:
 * 2160 → "4K", 1080 → "1080p", 720 → "720p", etc.
 */
internal fun normalizeResolution(width: Int, height: Int): String {
    if (width <= 0 && height <= 0) return "—"
    val maxDim = maxOf(width, height)
    return when {
        maxDim >= 3840 -> "4K"
        maxDim >= 2560 -> "1440p"
        maxDim >= 1920 -> "1080p"
        maxDim >= 1280 -> "720p"
        maxDim >= 854  -> "480p"
        maxDim >= 640  -> "360p"
        else           -> "${maxDim}p"
    }
}

/**
 * Whether a decoder failure on a Dolby Vision passthrough session is the
 * vendor DV decoder refusing the profile, rather than the box being out of
 * decoders.
 *
 * Some DV-capable boxes (TCL / Realtek: OMX.realtek.video.dvhe.st.decoder)
 * advertise video/dolby-vision, so Media3 reports the format as supported, and
 * then hard-fail the moment the first frame is submitted — reporting that
 * refusal with the platform's own out-of-resources code
 * (OMX_ErrorInsufficientResources, 0x80001000). By code alone it is
 * indistinguishable from genuine resource exhaustion, which every later
 * decoder on the process returns.
 *
 * The discriminator is the session: genuine exhaustion is a property of the
 * box that only shows up once a decoder has already failed, while the DV
 * refusal is the FIRST decode, with passthrough actually enabled and a
 * declared-DV track on screen. Treating that first failure as exhaustion is
 * what sent a DV-capable TV to "out of video decoder resources" and the
 * next-source hunt instead of the HDR10 strip that plays.
 */
internal fun dvPassthroughDecoderRefused(
    isDecoderFailure: Boolean,
    dvPassthroughActive: Boolean,
    declaredDvCodec: String?,
    alreadyStripped: Boolean
): Boolean = isDecoderFailure &&
    dvPassthroughActive &&
    !alreadyStripped &&
    dvLabelFromCodec(declaredDvCodec) != null

private val DV_PROFILE_CODEC = Regex("(?i)^(dvhe|dvh1|dvav|dva1)\\.(\\d+)")

/**
 * Extracts an exact Dolby Vision profile label from a codec string:
 * "dvhe.07.06" → "DV P7", "dvh1.05.06" → "DV P5", "dvhe.08.06" → "DV P8".
 * Non-DV strings return null so the generic family labels stay with normalizeCodec.
 */
internal fun dvLabelFromCodec(codec: String?): String? {
    if (codec.isNullOrBlank()) return null
    val profile = DV_PROFILE_CODEC.find(codec.trim())?.groupValues?.get(2)
    if (profile != null) {
        val number = profile.toIntOrNull() ?: profile.trimStart('0')
        return "DV P$number"
    }
    val lower = codec.trim().lowercase()
    return if (
        lower.startsWith("dvhe") || lower.startsWith("dvh1") ||
        lower.startsWith("dvav") || lower.startsWith("dva1") ||
        lower.startsWith("dv") || lower.contains("dolby vision")
    ) "Dolby Vision" else null
}

/**
 * Normalize raw codec string into a friendly label:
 * "hev1.1.6.L150" → "H.265", "avc1.640028" → "H.264",
 * "vp09.00" → "VP9", "av01" → "AV1".
 */
internal fun normalizeCodec(
    codec: String?,
    dvOriginalCodec: String? = null,
    convertedTo81: Boolean = false
): String {
    // Declared Dolby Vision that the DV → HDR10 strip rewrote to plain HEVC:
    // surface the original profile so the badge still shows what the file was.
    dvLabelFromCodec(dvOriginalCodec)?.let {
        return if (convertedTo81) "$it → 8.1" else "$it → HDR10"
    }
    if (codec.isNullOrBlank()) return "—"
    dvLabelFromCodec(codec)?.let { return it }
    val lower = codec.lowercase()
    return when {
        lower.contains("dolby vision") -> "Dolby Vision"
        lower.startsWith("avc") || lower.contains("h264") || lower.contains("h.264") -> "H.264"
        lower.startsWith("hev") || lower.startsWith("hvc") || lower.contains("h265") || lower.contains("h.265") -> "H.265"
        lower.startsWith("vp09") || lower.startsWith("vp9") -> "VP9"
        lower.startsWith("vp08") || lower.startsWith("vp8") -> "VP8"
        lower.startsWith("av01") || lower.startsWith("av1") -> "AV1"
        lower.contains("mp4a") || lower.startsWith("mp3") || lower.contains("aac") -> "AAC"
        // E-AC3 first: "audio/eac3" contains "ac3", so testing AC-3 here
        // first labeled every E-AC3 track as AC-3 — the one difference a
        // viewer choosing between two Dolby tracks is looking for.
        lower.contains("eac3") || lower.contains("ec-3") -> "EAC3"
        lower.contains("ac-3") || lower.contains("ac3") -> "AC-3"
        lower.contains("opus") -> "Opus"
        lower.contains("vorbis") -> "Vorbis"
        lower.contains("flac") -> "FLAC"
        // Audio reached through its MIME type rather than a codec string (see
        // audioTrackLabel): container audio tracks leave Format.codecs empty,
        // so these names come from "audio/<something>" instead.
        lower == "audio/mpeg" || lower == "audio/mp3" -> "MP3"
        lower == "audio/mpeg-l1" -> "MP1"
        lower == "audio/mpeg-l2" -> "MP2"
        lower == "audio/raw" -> "PCM"
        lower == "audio/alac" -> "ALAC"
        lower.contains("dts.hd") || lower.contains("dts-hd") || lower.contains("dtshd") -> "DTS-HD"
        lower.contains("dts") -> "DTS"
        lower.contains("true-hd") || lower.contains("truehd") || lower.contains("mlp") -> "TrueHD"
        lower.contains("ac-4") || lower.contains("ac4") -> "AC-4"
        lower.contains("video/h264") || lower.contains("video/avc") -> "H.264"
        lower.contains("video/hevc") || lower.contains("video/h265") -> "H.265"
        lower.contains("video/vp9") -> "VP9"
        lower.contains("video/av01") || lower.contains("video/av1") -> "AV1"
        else -> codec.uppercase()
    }
}
