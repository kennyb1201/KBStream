package com.kennyb1201.kbstream.ui.player

import java.net.URLDecoder
import kotlin.math.roundToInt

/**
 * The MPV engine's INFO panel, as text: every row the main player's panel
 * carries, built from one [MpvPlayerView.FileReport] instead of an ExoPlayer
 * track list.
 *
 * Why this is a separate, pure object rather than a build method on the
 * activity: the panel's whole value is that a viewer can put the two engines'
 * readouts side by side for the same file, and the numbers in it come from two
 * completely different sources (ExoPlayer's `Format`s versus mpv's own
 * properties). Keeping the mapping from "what mpv says" to "what the row reads"
 * here means that mapping is unit-tested - a `null` width, an audio-only
 * stream, a 6-channel track, an HDR10 file - on a plain JVM, without a device,
 * a socket or an mpv instance. The activity is then only wiring.
 *
 * Two conventions are deliberate and are pinned by [MpvInfoLinesTest]:
 *
 *  - No row is ever blank. mpv reports nothing at all for the properties of a
 *    stream that does not have that component (an audio-only file has no
 *    `video-format`), and a readout that says "Codec: null" or "Resolution: "
 *    is worse than one that says "—": the first is a crash report, the second
 *    is an answer ("this file has none").
 *  - Where the main player has a wording for a number, this uses it: the codec
 *    names come from the shared [normalizeCodec], the resolution suffix from
 *    [normalizeResolution], and the bitrate line from [bitrateLabel]. A file
 *    that reads "H.265 / 1080p / 8163 kbps" on one engine has to read the same
 *    on the other, or the side-by-side comparison this panel exists for is
 *    comparing two different vocabularies.
 */
internal object MpvInfoLines {

    /** The engine row: this is the one panel that has to say which engine it is. */
    const val ENGINE_LABEL = "MPV (libmpv)"

    /** What a row reads when mpv has nothing to report for it. */
    private const val NONE = "—"

    /** mpv's own values for "no hardware decoding in use" (see [decodeLine]). */
    private val SOFTWARE_HWDEC = setOf("no", "none")

    // ── the file row ──────────────────────────────────────────────────────

    /**
     * The file's name, from the URL's last path segment, falling back to the
     * source's own label.
     *
     * The same derivation the main player's panel uses, percent-decoding
     * included, because that is what a side-by-side comparison reads first: a
     * debrid link whose filename is URL-encoded must not come out looking like
     * a different file on the other engine.
     */
    fun fileLabel(url: String, fallback: String?): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val decoded = if (path.contains('%')) {
            runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        } else {
            path
        }
        return decoded.takeIf { it.isNotBlank() && it.contains('.') }
            ?: fallback?.trim()?.takeIf { it.isNotBlank() }
            ?: NONE
    }

    fun fileRow(url: String, fallback: String?): String = "File: ${fileLabel(url, fallback)}"

    // ── VIDEO ─────────────────────────────────────────────────────────────

    /** `Resolution: 1920×800 (1080p)`, or `Resolution: —` before mpv knows. */
    fun resolutionLine(width: Int, height: Int): String =
        if (width > 0 && height > 0) {
            "Resolution: ${width}×$height (${normalizeResolution(width, height)})"
        } else {
            "Resolution: $NONE"
        }

    /** `Codec: H.264` — mpv's `video-format` through the shared normalizer. */
    fun codecLine(videoFormat: String?): String = "Codec: ${normalizeCodec(videoFormat)}"

    /**
     * `Decode: mediacodec` — the decoder mpv is actually using
     * (`hwdec-current`).
     *
     * Named here rather than in the engine row because it is per-file, not
     * per-session: the same session can open one file on the MediaTek decoder
     * and the next in software, and "why is this one dropping frames" is
     * answered by this line. mpv reports "no" (or nothing) when hardware
     * decoding is off, which reads as the honest word for it: software.
     */
    fun decodeLine(hwdec: String?): String {
        val value = hwdec?.trim()?.lowercase().orEmpty()
        return "Decode: " + if (value.isEmpty() || value in SOFTWARE_HWDEC) "software" else value
    }

    /** `Pixel format: yuv420p10`, or null when mpv reports none. */
    fun pixelFormatLine(pixelFormat: String?): String? =
        pixelFormat?.trim()?.takeIf { it.isNotBlank() }?.let { "Pixel format: $it" }

    /**
     * `HDR: pq • bt.2020 • peak 1000 nits`, or null for an SDR file.
     *
     * HDR is decided by the transfer function and the colour primaries, not by
     * the peak: a plain SDR file with a `sig-peak` mpv happened to have is not
     * HDR, and printing "HDR" over one would be the panel lying about the one
     * thing it is being read for. The peak is only added once the file is
     * already known to be HDR, where it is the number that says whether the
     * panel is being fed 1000-nit or 4000-nit metadata.
     */
    fun hdrLine(gamma: String?, primaries: String?, sigPeak: Double?): String? {
        val transfer = gamma?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val colour = primaries?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val isHdr = transfer == "pq" || transfer == "hlg" ||
            colour?.startsWith("bt.2020") == true
        if (!isHdr) return null
        val peak = sigPeak?.takeIf { it > 0.0 }?.let { "peak ${it.roundToInt()} nits" }
        return "HDR: " + listOfNotNull(transfer, colour, peak).joinToString(" • ")
    }

    /** The whole VIDEO block: the resolution, codec, rate, decode and HDR rows. */
    fun videoBlock(report: MpvPlayerView.FileReport): String = listOfNotNull(
        resolutionLine(report.width, report.height),
        codecLine(report.videoFormat),
        // Null (not "Bitrate: 0 kbps") until mpv has one: the shared helper's
        // own contract, and the main player's wording with it.
        bitrateLabel(report.videoBitrate, 0L),
        decodeLine(report.hwdec),
        pixelFormatLine(report.pixelFormat),
        hdrLine(report.gamma, report.primaries, report.sigPeak)
    ).joinToString("\n")

    // ── AUDIO ─────────────────────────────────────────────────────────────

    /**
     * mpv's channel count as a layout: 6 → "5.1", 2 → "stereo", 1 → "mono" —
     * or null for a count with no layout of its own (3, 5), which is reported
     * by its number alone.
     */
    fun channelLayout(channels: Int?): String? = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> null
    }

    /** `Channels: 6 (5.1)` when the count has a layout, `Channels: 6` when not. */
    fun channelLine(channels: Int?): String {
        if (channels == null || channels <= 0) return "Channels: $NONE"
        val layout = channelLayout(channels)
        return if (layout != null) "Channels: $channels ($layout)" else "Channels: $channels"
    }

    /** `Sample rate: 48000 Hz`. */
    fun sampleRateLine(sampleRate: Int?): String =
        if (sampleRate != null && sampleRate > 0) {
            "Sample rate: $sampleRate Hz"
        } else {
            "Sample rate: $NONE"
        }

    /** The whole AUDIO block: codec, channel layout, sample rate and rate. */
    fun audioBlock(report: MpvPlayerView.FileReport): String = listOfNotNull(
        "Codec: ${normalizeCodec(report.audioCodec)}",
        channelLine(report.audioChannels),
        sampleRateLine(report.audioSampleRate),
        report.audioBitrate?.takeIf { it > 0 }?.let { "Bitrate: ${it / 1_000} kbps" }
    ).joinToString("\n")

    // ── SUBTITLE ──────────────────────────────────────────────────────────

    /**
     * `Subtitle: ENG • ASS`, or `Subtitle: None`.
     *
     * [embedded] is the selected track's label as mpv lists it; [sidecar] is a
     * subtitle file this app added on top of the container, which is not in
     * mpv's track list at all and therefore has to be named from the other
     * side. A sidecar wins: it is what is on screen, and it was added later
     * than the track it may have replaced.
     */
    fun subtitleLine(embedded: String?, sidecar: String?): String {
        val external = sidecar?.trim()?.takeIf { it.isNotBlank() }
        val value = external?.let { "$it (sidecar)" }
            ?: embedded?.trim()?.takeIf { it.isNotBlank() }
            ?: "None"
        return "Subtitle: $value"
    }
}
