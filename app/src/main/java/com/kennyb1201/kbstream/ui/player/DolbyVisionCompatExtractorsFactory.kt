@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * Wraps the stock Media3 [ExtractorsFactory] and, for video tracks, intercepts
 * the sample stream to strip Dolby Vision Profile 7 RPU / enhancement-layer NAL
 * units on the fly (see [DolbyVisionCompat]). Non-DV content is a strict
 * pass-through that never touches the buffering path.
 *
 * Framing per container:
 *  - Media3's MP4 / fragmented MP4 extractors convert length-prefixed HEVC NAL
 *    units to Annex-B start codes before calling TrackOutput, so their samples
 *    are handled as Annex-B here too.
 *  - TS and Matroska reach TrackOutput as Annex-B as well. This single framing
 *    path covers MP4, fMP4, TS and the common single-track MKV remuxes where
 *    the DV RPU rides in-band.
 *
 * Detection happens in two stages. Tracks whose codec string declares Dolby
 * Vision (dvhe/dvh1 via a dvcc box) strip immediately and have their codec
 * rewritten so Media3 never queries a Dolby Vision decoder — but only when
 * [dvRewriteEnabled] is set (DV mode "P7 → 8.1" or "Strip All"). In the
 * "P7 → 8.1" mode only Profile 7 qualifies (every other DV profile is passed
 * through so DV displays get the real thing); with [convertAllProfiles] set
 * ("Strip All") every profile
 * — 4/5/7/8 — is converted for displays without Dolby Vision. On devices with
 * a native Dolby Vision decoder ([nativeDvSupported]) the HDR10-base profiles
 * (4/8) pass through as real Dolby Vision — which those devices decode
 * natively (mutating them is what makes MTK-class HEVC decoders stall with
 * zero output frames) — EXCEPT in the explicit "Strip All" mode, where the
 * user has told us the display has no Dolby Vision at all and P4/P8 are
 * always stripped to HDR10 even on DV-capable devices (a DV decoder on the
 * box does not mean the TV can show DV; on such combos the platform DV
 * pipeline black-screens the non-DV display, so Strip All must win). Profiles 4/8
 * already carry a standard HDR10 base layer, so on non-DV devices they are
 * re-advertised as hvc1,
 * their VPS is rewritten to a clean single-layer parameter set (a DV VPS left
 * behind after the strip makes some decoders stall waiting for RPUs), and only
 * the DV RPU / EL and (per toggle) HDR10+ SEI NALs are dropped — every other
 * byte stays bit-exact; P5 has no HDR10 base, so it stays a best-effort
 * plain-HEVC fallback that the GLES shader / FFmpeg path color-corrects. Tracks reported as
 * plain HEVC (hvc1/hev1 — muxers that omitted the dvcc marker) are sniffed
 * over the first samples: in-band RPU NALs engage the same strip (DV remuxes,
 * when DV conversion is enabled), and with [stripHdr10Plus] set, ST 2094-40
 * SEIs are removed from plain-HDR10+ releases so HDR10+-intolerant TVs don't
 * black-screen. With DV conversion disabled but the HDR10+ strip on (DV mode
 * Off + toggle), only the HDR10+ SEIs are removed and DV streams are never
 * touched. DV→HDR10 conversions themselves (Strip All) always drop
 * HDR10+ SEI NALs from the samples regardless of the toggle — the converted
 * stream is static HDR10, and HDR10+ dynamic metadata black-screens the same
 * HDR10+-intolerant TVs these modes exist for (common on P8 WEB-DL encodes
 * that carry DV + HDR10+ in one track). Media3 1.9 has no Format-level HDR10+
 * passthrough, so the sample-level strip is the whole story. Verified-clean
 * samples are forwarded untouched, so a false negative only ever costs a few
 * buffered samples, never picture data.
 *
 * MKV variants where the RPU only exists as BlockAdditional side data are not
 * reachable here (stock Media3 discards that data before TrackOutput); on those
 * files the base layer already reaches the decoder clean and the codec rewrite
 * above is what fixes playback.
 */
internal class DolbyVisionCompatExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val stripHdr10Plus: Boolean = false,
    private val convertAllProfiles: Boolean = false,
    private val dvRewriteEnabled: Boolean = true,
    private val convertP7To81: Boolean = false,
    private val convertP5To81: Boolean = false,
    private val nativeDvSupported: Boolean = false
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> {
        Log.i("PLAYER_DV", "Compat extractor factory invoked (default)")
        return delegate.createExtractors().map { wrap(it) }.toTypedArray()
    }

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> {
        Log.i(
            "PLAYER_DV",
            "Compat extractor factory invoked uri=${uri.lastPathSegment ?: uri} " +
                "headers=${responseHeaders.keys.joinToString(",")}"
        )
        return delegate.createExtractors(uri, responseHeaders).map { wrap(it) }.toTypedArray()
    }

    private fun wrap(extractor: Extractor): Extractor {
        val name = extractor.javaClass.name
        val simpleName = name.substringAfterLast('.')
        // Keep the wrapper in front of every candidate returned by
        // DefaultExtractorsFactory. Media3 may return a container-specific
        // extractor whose implementation is decorated/proxied, so relying
        // only on the concrete class name can silently bypass DV rewriting.
        // VideoCompatExtractor only intercepts video tracks; audio and other
        // track types remain direct pass-throughs. Media3 emits the HEVC
        // access units seen here as Annex-B for the progressive containers
        // handled by this factory.
        val framing = NalFraming.ANNEX_B
        Log.i(
            "PLAYER_DV",
            "Wrapping extractor=$simpleName framing=$framing " +
                "allProfiles=$convertAllProfiles rewriteEnabled=$dvRewriteEnabled " +
                "stripHdr10Plus=$stripHdr10Plus convertP7To81=$convertP7To81 convertP5To81=$convertP5To81 " +
                "nativeDv=$nativeDvSupported"
        )
        return VideoCompatExtractor(
            extractor, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled,
            convertP7To81, convertP5To81, nativeDvSupported
        )
    }
}

/** How HEVC NAL units are framed in the sample stream for a given container. */
internal enum class NalFraming { ANNEX_B, LENGTH_DELIMITED }

/** Forwards to a delegate extractor but routes video tracks through a stripping TrackOutput. */
private class VideoCompatExtractor(
    private val delegate: Extractor,
    private val framing: NalFraming,
    private val stripHdr10Plus: Boolean,
    private val convertAllProfiles: Boolean,
    private val dvRewriteEnabled: Boolean,
    private val convertP7To81: Boolean,
    private val convertP5To81: Boolean,
    private val nativeDvSupported: Boolean
) : Extractor {

    override fun init(output: ExtractorOutput) {
        Log.i("PLAYER_DV", "Compat extractor initialized=${delegate.javaClass.simpleName}")
        delegate.init(
            VideoCompatExtractorOutput(
                output, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled,
                convertP7To81, convertP5To81, nativeDvSupported
            )
        )
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

private class VideoCompatExtractorOutput(
    private val delegate: ExtractorOutput,
    private val framing: NalFraming,
    private val stripHdr10Plus: Boolean,
    private val convertAllProfiles: Boolean,
    private val dvRewriteEnabled: Boolean,
    private val convertP7To81: Boolean,
    private val convertP5To81: Boolean,
    private val nativeDvSupported: Boolean
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            VideoCompatTrackOutput(
                track, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled,
                convertP7To81, convertP5To81, nativeDvSupported
            )
        } else {
            track
        }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * TrackOutput that buffers each video sample while active and either passes it
 * through untouched, sniffs it for in-band Dolby Vision RPU NALs, or strips
 * them (rewriting the codec string to plain HEVC for declared DV tracks).
 */
private class VideoCompatTrackOutput(
    private val delegate: TrackOutput,
    private val framing: NalFraming,
    private val stripHdr10Plus: Boolean,
    private val convertAllProfiles: Boolean,
    private val dvRewriteEnabled: Boolean,
    private val convertP7To81: Boolean,
    private val convertP5To81: Boolean,
    private val nativeDvSupported: Boolean
) : TrackOutput {

    /** True when either per-profile 8.1 conversion (P5/P7) is active. */
    private val convertTo81 = convertP7To81 || convertP5To81

    // HDR10 color metadata (ST.2084 PQ / BT.2020, 10-bit) injected on every
    // DV->HDR10 rewrite path below. Declared DV tracks don't reliably carry a
    // populated Format.colorInfo through the wrapping extractor chain, and
    // without KEY_COLOR_TRANSFER/STANDARD/RANGE on the negotiated MediaFormat,
    // some hardware decoders (MTK-class OMX components in particular) accept
    // 10-bit input samples but never emit an output frame — black screen with
    // audio still playing, indistinguishable from the VPS-stall failure this
    // file already guards against. Every rewrite branch must attach this.
    private val hdr10ColorInfo = ColorInfo.Builder()
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .build()

    private enum class Mode { NORMAL, SNIFFING, STRIPPING }

    private var mode = Mode.NORMAL
    private var sniffRemaining = 0
    private var currentCodecs: String? = null
    // Original format (untouched init data) of the current track, kept so the
    // sniff path can re-emit the format with rewritten VPS/SPS once in-band
    // Dolby Vision is confirmed on a track whose codec config was forwarded
    // verbatim (plain hvc1/hev1 remuxes).
    private var lastFormat: Format? = null
    private var isP5Content = false
    private var nalLengthFieldLength = 4
    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var stripReported = false
    // True once this track's codec config (hvcC record / Annex-B parameter
    // sets) was rewritten to single-layer HDR10 at format time. MP4/fMP4
    // samples carry no VPS/SPS, so the per-sample stats below never see them;
    // this flag makes the first-sample log report the config rewrite that
    // actually happened.
    private var initDataRewritten = false
    private val scratch = ParsableByteArray()

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        currentCodecs = format.codecs
        lastFormat = format
        isP5Content = DolbyVisionCompat.isP5Profile(format.codecs)
        Log.i(
            "PLAYER_DV",
            "Compat video format mime=${format.sampleMimeType} codecs=${format.codecs ?: "?"} P5=${isP5Content}"
        )
        // A (re)emitted format starts a fresh sample window (seek / re-init).
        pendingLen = 0
        // 8.1 conversion is per-profile: only declared P5/P7 streams whose
        // toggle is on are rewritten — the codec stays in the dvhe/dvh1 family
        // (so the Dolby Vision pipeline still engages) with the profile digits
        // changed to 08, the VPS is forced to a single layer, the enhancement
        // layer NALs are dropped, and every RPU is rewritten to 8.1 metadata
        // with a fresh CRC. P4 / P8 declared streams have no matching to81Codec
        // and follow the mode's default handling below.
        val to81Rewrite =
            if (convertTo81) DolbyVisionCompat.to81Codec(format.codecs, convertP7To81, convertP5To81)
            else null
        if (to81Rewrite != null) {
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — converting to " +
                    "Profile 8.1 ($to81Rewrite): single-layer VPS, EL dropped, RPUs rewritten"
            )
            var builder = format.buildUpon().setCodecs(to81Rewrite)
            // Keep the original declared DV codec (e.g. "dvhe.07.06") on the
            // label so the player UI / P5 detection can badge the source profile.
            if (!format.codecs.isNullOrBlank()) {
                builder = builder.setLabel(format.codecs)
            }
            builder = builder.setInitializationData(
                rewriteInitData(format.initializationData)
            )
            delegate.format(builder.build())
            mode = Mode.STRIPPING
            return
        }
        // When DV conversion is disabled (the DV setting is Off and only the
        // HDR10+ strip / 8.1 toggles are on), declared DV tracks whose profile
        // is not 8.1-converted must pass through untouched — no codec rewrite,
        // no RPU strip.
        val dvRewrite =
            if (dvRewriteEnabled) DolbyVisionCompat.hdr10Codec(format.codecs, convertAllProfiles)
            else null
        // Profiles 4/8 are single-layer streams whose base layer is already
        // standard HDR10 HEVC. On a device with a native Dolby Vision decoder
        // they play untouched through the platform DV pipeline — the DV decoder
        // consumes the RPU NALs and the platform downconverts for non-DV sinks —
        // so no mutation is needed. Mutating them is exactly what MTK-class DV
        // hardware chokes on: re-advertising as hvc1 and stripping the RPUs
        // leaves the HEVC decoder configured OK but stalling with zero output
        // frames. The strip path below is only for devices without a DV decoder
        // (where the base layer must be played as plain HDR10) — except in the
        // explicit "Strip All" mode, which is the user telling us the display
        // has NO Dolby Vision at all and every profile must become HDR10.
        // A DV-capable device (e.g. a Fire TV Stick advertising video/dolby-
        // vision) does NOT imply a DV-capable display: on such combos the
        // platform DV pipeline does not reliably downconvert for the non-DV TV
        // (black screen with audio), so Strip All must override the passthrough
        // and always strip P4/P8 to HDR10. On those devices without a DV
        // decoder, forwarding the in-band DV RPU / HDR10+ metadata NALs
        // untouched makes some decoders re-emit their output format on every
        // frame ("Resolution change XxX to XxX" at video fps) and the compositor
        // drops the frames — black screen with audio. But stripping the
        // metadata while leaving the Dolby Vision VPS in place is worse: the
        // decoder sits in DV mode waiting for RPUs that never arrive and stalls
        // completely (input frames in, zero output). So for these profiles:
        // re-advertise as plain HEVC, rewrite the VPS to a clean single-layer
        // parameter set, and strip the metadata NALs (62/63 + layerId>0 +
        // HDR10+ SEI per toggle) — every other byte bit-exact.
        if (dvRewrite != null && DolbyVisionCompat.isHdr10BaseLayerProfile(format.codecs)) {
            if (nativeDvSupported && !convertAllProfiles) {
                Log.i(
                    "PLAYER_DV",
                    "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — device has a " +
                        "native Dolby Vision decoder: passing through as Dolby Vision " +
                        "(no strip — HDR10-base profiles play natively)"
                )
                mode = Mode.NORMAL
                delegate.format(format)
                return
            }
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — HDR10 base layer: " +
                    "re-advertising as hvc1, rewriting VPS to single-layer HDR10, " +
                    "stripping DV RPU/EL + HDR10+ metadata NALs " +
                    "(nativeDv=$nativeDvSupported stripAll=$convertAllProfiles)"
            )
            var builder = format.buildUpon().setCodecs(dvRewrite)
            // Keep the original declared DV codec (e.g. "dvhe.08.06") on the
            // label so the player UI can badge the profile playing as HDR10.
            if (!format.codecs.isNullOrBlank()) {
                builder = builder.setLabel(format.codecs)
            }
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                builder = builder.setSampleMimeType(MimeTypes.VIDEO_H265)
            }
            builder = builder.setInitializationData(
                rewriteInitData(format.initializationData)
            )
            // The rewritten format must carry explicit HDR10 color metadata,
            // same as the general strip path below — declared DV tracks don't
            // reliably have Format.colorInfo populated coming out of the
            // wrapping extractor chain, and buildUpon() only carries over
            // whatever was already there. Without KEY_COLOR_TRANSFER /
            // STANDARD / RANGE on the negotiated MediaFormat, MTK-class OMX
            // decoders can accept 10-bit input samples and never emit an
            // output frame — this is what was actually happening on P4/P8
            // sources, not a VPS problem (P8/P4 usually carry no VPS
            // extension to begin with, so vpsRewritten=false here is normal).
            delegate.format(builder.build().buildUpon().setColorInfo(hdr10ColorInfo).build())
            mode = Mode.STRIPPING
            return
        }
        mode = when {
            // Declared Dolby Vision (dvcc present) with DV conversion enabled:
            // the "P7 → 8.1" mode rewrites only Profile 7 (via to81Codec above),
            // "Strip All" rewrites every profile (4/5/7/8). Strip from the
            // first sample and rewrite the codec string so Media3 never queries
            // a DV decoder. P5 (ICtCp) keeps this path: its pixels are not
            // HDR10, so the downstream GLES shader / FFmpeg conversion needs
            // the rewritten stream.
            dvRewrite != null -> Mode.STRIPPING
            // No DV marker in the codec string — plain HEVC might still carry
            // in-band RPU when the muxer omitted the dvcc box (remuxes). Sniff
            // the first samples for NAL type 62/63 — and for HDR10+ SEIs when
            // that toggle is on.
            isPlainHevc(format) -> {
                sniffRemaining = SNIFF_BUDGET_SAMPLES
                Mode.SNIFFING
            }
            else -> Mode.NORMAL
        }
        if (framing == NalFraming.LENGTH_DELIMITED) {
            nalLengthFieldLength = nalLengthFieldLength(format)
        }
        if (dvRewrite != null) {
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — rewriting to HEVC HDR10"
            )
            // Profile 5 (dvhe.05/dvh1.05) is single-layer ICtCp with no HDR10
            // base layer, so the codec rewrite yields plain HEVC whose pixel
            // data is still ICtCp, not Rec.2020 PQ. The injected HDR10 color
            // metadata will at least make the display treat it as HDR rather
            // than falling back to washed-out SDR.
            //
            // For true P5→HDR10 color conversion, the ICtCp pixel data must be
            // converted to Rec.2020 PQ. This requires software decoding (FFmpeg)
            // with pixel-level color space conversion, since the hardware decoder
            // outputs ICtCp pixel values that the display interprets as Rec.2020
            // PQ (giving wrong colors). The FFmpeg path handles this via its
            // internal color space conversion when fed the correct input/output
            // colorspace parameters.
            //
            // The pixel-level conversion is applied by the FFmpeg renderer when
            // P5 content is detected — see NativePlayerActivity for the decoder
            // selection logic that forces FFmpeg for P5 streams.
            var builder = format.buildUpon().setCodecs(dvRewrite)
            // Keep the original declared DV codec (e.g. "dvhe.07.06") on the
            // rewritten format so the player UI can badge the exact profile
            // that was converted ("DV P7 → HDR10"). The decoder selection
            // only reads codecs / mime, so the label is safe metadata here.
            if (!format.codecs.isNullOrBlank()) {
                builder = builder.setLabel(format.codecs)
            }
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                builder = builder.setSampleMimeType(MimeTypes.VIDEO_H265)
            }
            builder = builder.setInitializationData(
                rewriteInitData(format.initializationData)
            )
            val rewritten = builder.build()
            // When DV is stripped, the resulting stream is plain HDR10 HEVC.
            // Inject correct HDR10 color metadata (ST.2084 PQ / BT.2020) so
            // the display treats it as HDR. For Profile 7 this matches the
            // original base layer; for Profile 5 (single-layer ICtCp) there is
            // no true HDR10 base, so this is best-effort — colors may be off
            // because the pixel data is still ICtCp, not Rec.2020 PQ, but
            // forcing HDR10 metadata at least avoids an SDR fallback with
            // fully washed-out colors.
            delegate.format(rewritten.buildUpon().setColorInfo(hdr10ColorInfo).build())
        } else {
            delegate.format(format)
        }
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean
    ): Int = sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (mode == Mode.NORMAL || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensurePendingCapacity(pendingLen + length)
        val read = input.read(pendingBuf, pendingLen, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read > 0) pendingLen += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int): Unit =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (mode == Mode.NORMAL || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensurePendingCapacity(pendingLen + length)
        data.readBytes(pendingBuf, pendingLen, length)
        pendingLen += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (mode == Mode.NORMAL || pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val carrySize = offset.coerceIn(0, pendingLen)
        val sampleEnd = pendingLen - carrySize

        fun emit(dataLen: Int) {
            scratch.reset(pendingBuf, dataLen)
            delegate.sampleData(scratch, dataLen)
            delegate.sampleMetadata(timeUs, flags, dataLen, 0, cryptoData)
        }

        if (mode == Mode.SNIFFING) {
            val dvFound = when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.sampleHasDvNalsAnnexB(pendingBuf, sampleEnd)
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.sampleHasDvNalsLengthDelimited(
                        pendingBuf, sampleEnd, nalLengthFieldLength
                    )
            }
            if (dvFound && (dvRewriteEnabled || convertTo81)) {
                mode = Mode.STRIPPING
                val action = if (convertTo81) "converting to Profile 8.1" else "stripping to HDR10"
                Log.i(
                    "PLAYER_DV",
                    "In-band Dolby Vision RPU detected (no dvcc marker, codecs=${currentCodecs ?: "?"}) — $action"
                )
                // This track's codec config was emitted untouched (it looked
                // like plain HEVC), so its VPS/SPS still declare the original
                // Dolby Vision structure. Rewrite them to single-layer HDR10
                // and re-emit the format now that in-band DV is confirmed — a
                // decoder that configured from a DV VPS but never receives RPUs
                // is exactly the stall that black-screens with audio.
                val fmt = lastFormat
                if (fmt != null && fmt.initializationData.isNotEmpty()) {
                    val rewrittenInit = rewriteInitData(fmt.initializationData)
                    if (rewrittenInit !== fmt.initializationData) {
                        Log.i(
                            "PLAYER_DV",
                            "Re-emitting format with rewritten single-layer HDR10 codec config " +
                                "(codecs=${fmt.codecs ?: "?"})"
                        )
                        delegate.format(fmt.buildUpon().setInitializationData(rewrittenInit).build())
                    }
                }
                // Fall through and strip this very sample: samples that contain
                // DV NALs are never forwarded untouched to the decoder.
            } else if (dvFound) {
                // DV conversion is off for this session (DV = Off) — the sniff
                // only exists to find HDR10+ SEIs. A DV remux has nothing to
                // do here: forward it untouched and stop sniffing.
                mode = Mode.NORMAL
                sniffRemaining = 0
                emit(sampleEnd)
                if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
                pendingLen = carrySize
                return
            } else {
                // No DV NALs in this sample. When HDR10+ stripping is enabled,
                // remove ST 2094-40 SEIs here too: plain-HDR10+ HEVC releases
                // (codecs hvc1/hev1, no DV at all) black-screen on HDR10+-
                // intolerant TVs the same way DV7 does on non-DV sets. Once an
                // HDR10+ SEI is seen, lock into stripping for the stream's life.
                if (stripHdr10Plus) {
                    val hdr10Cleaned = when (framing) {
                        NalFraming.ANNEX_B ->
                            DolbyVisionCompat.stripAnnexB(
                                pendingBuf, sampleEnd, stripDv = false, stripHdr10Plus = true
                            )
                        NalFraming.LENGTH_DELIMITED ->
                            DolbyVisionCompat.stripLengthDelimited(
                                pendingBuf, sampleEnd, nalLengthFieldLength,
                                stripDv = false, stripHdr10Plus = true
                            )
                    }
                    if (hdr10Cleaned >= 0) {
                        mode = Mode.STRIPPING
                        Log.i(
                            "PLAYER_DV",
                            "HDR10+ SEI detected on plain HEVC track (codecs=${currentCodecs ?: "?"}) — stripping to static HDR10"
                        )
                        emit(hdr10Cleaned)
                        if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
                        pendingLen = carrySize
                        return
                    }
                }
                // Verified clean (or budget exhausted) — forward untouched.
                sniffRemaining--
                if (sniffRemaining <= 0) mode = Mode.NORMAL
                emit(sampleEnd)
                if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
                pendingLen = carrySize
                return
            }
        }

        val stats = DolbyVisionCompat.StripStats()
        // Inventory BEFORE the in-place transform so the log shows what the
        // decoder would have seen untouched.
        val inventory =
            if (!stripReported) DolbyVisionCompat.describeNals(pendingBuf, sampleEnd) else ""
        // Every DV→HDR10 conversion (all non-8.1 strip paths) outputs static
        // HDR10, so HDR10+ SEI NALs are dropped unconditionally — an
        // HDR10+-intolerant TV black-screens on them just like DV. The separate
        // "Strip HDR10+" toggle still governs plain-HDR10+ (non-DV) streams via
        // the sniff path, and 8.1 conversion keeps toggle-driven behavior.
        val effectiveStripHdr10Plus = stripHdr10Plus || !convertTo81
        if (!stripHdr10Plus && !convertTo81 && !stripReported) {
            Log.i(
                "PLAYER_DV",
                "DV→HDR10 conversion — stripping HDR10+ SEI from samples (toggle off: " +
                    "HDR10+ survives only in 8.1 mode)"
            )
        }
        val stripped = if (convertTo81) {
            when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.transformAnnexBTo81(
                        pendingBuf, sampleEnd, stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.transformLengthDelimitedTo81(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
            }
        } else {
            when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.transformAnnexB(
                        pendingBuf, sampleEnd, stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.transformLengthDelimited(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
            }
        }
        if (stripped >= 0 && !stripReported) {
            stripReported = true
            val vpsFail = stats.vpsRewriteFailedReason?.let { " vpsFail=$it" } ?: ""
            val spsFail = stats.spsRewriteFailedReason?.let { " spsFail=$it" } ?: ""
            Log.i(
                "PLAYER_DV",
                if (convertTo81) {
                    "First 8.1-converted sample (codecs=${currentCodecs ?: "?"}) — " +
                        "RPU rewritten=${stats.rpuRewritten} EL=${stats.elBytes}B " +
                        "HDR10+SEI=${stats.hdr10PlusBytes}B vpsRewritten=${stats.vpsRewritten}$vpsFail " +
                        "initDataRewritten=$initDataRewritten spsRewritten=${stats.spsRewritten}$spsFail " +
                        "nals=$inventory"
                } else {
                    "First stripped sample (codecs=${currentCodecs ?: "?"}) — " +
                        "dropped RPU=${stats.rpuBytes}B EL=${stats.elBytes}B HDR10+SEI=${stats.hdr10PlusBytes}B " +
                        "vpsRewritten=${stats.vpsRewritten}$vpsFail initDataRewritten=$initDataRewritten " +
                        "spsRewritten=${stats.spsRewritten}$spsFail nals=$inventory"
                }
            )
        }
        emit(if (stripped >= 0) stripped else sampleEnd)

        if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
        pendingLen = carrySize
    }

    /** Rewrites codec-private VPS/SPS to single-layer HDR10, tracking whether
     * the config actually changed so the first-sample log reports it even for
     * containers whose samples carry no parameter sets (MP4/fMP4). */
    private fun rewriteInitData(initData: List<ByteArray>): List<ByteArray> {
        val rewritten = DolbyVisionCompat.rewriteInitDataVps(initData)
        if (rewritten !== initData) initDataRewritten = true
        return rewritten
    }

    private fun isPlainHevc(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_H265) return true
        val codecs = format.codecs
        return !codecs.isNullOrBlank() && PLAIN_HEVC_CODEC.containsMatchIn(codecs.trim())
    }

    private fun ensurePendingCapacity(need: Int) {
        if (pendingBuf.size >= need) return
        var newSize = if (pendingBuf.isEmpty()) 16 * 1024 else pendingBuf.size
        while (newSize < need) newSize = newSize shl 1
        pendingBuf = pendingBuf.copyOf(newSize)
    }

    private fun nalLengthFieldLength(format: Format): Int {
        val csd = format.initializationData.firstOrNull() ?: return 4
        if (csd.size <= 21) return 4
        if (csd[0].toInt() != 1) return 4
        return (csd[21].toInt() and 0x03) + 1
    }

    private companion object {
        private val PLAIN_HEVC_CODEC = Regex("(?i)^(hvc1|hev1)\\.")
        private const val SNIFF_BUDGET_SAMPLES = 24
    }
}
