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
 * [dvRewriteEnabled] is set (DV mode Auto or "All DV"). In Auto mode only
 * Profile 7 qualifies (every other DV profile is passed through so DV displays
 * get the real thing); with [convertAllProfiles] set ("All DV") every profile
 * — 4/5/7/8 — is converted for displays without Dolby Vision. Profiles 4/8
 * already carry a standard HDR10 base layer, so they are re-advertised as hvc1,
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
 * touched. Verified-clean samples are forwarded untouched, so a false
 * negative only ever costs a few buffered samples, never picture data.
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
    private val convertTo81: Boolean = false
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
                "stripHdr10Plus=$stripHdr10Plus convertTo81=$convertTo81"
        )
        return VideoCompatExtractor(
            extractor, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled, convertTo81
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
    private val convertTo81: Boolean
) : Extractor {

    override fun init(output: ExtractorOutput) {
        Log.i("PLAYER_DV", "Compat extractor initialized=${delegate.javaClass.simpleName}")
        delegate.init(
            VideoCompatExtractorOutput(
                output, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled, convertTo81
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
    private val convertTo81: Boolean
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            VideoCompatTrackOutput(
                track, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled, convertTo81
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
    private val convertTo81: Boolean
) : TrackOutput {

    private enum class Mode { NORMAL, SNIFFING, STRIPPING }

    private var mode = Mode.NORMAL
    private var sniffRemaining = 0
    private var currentCodecs: String? = null
    private var isP5Content = false
    private var nalLengthFieldLength = 4
    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var stripReported = false
    private val scratch = ParsableByteArray()

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        currentCodecs = format.codecs
        isP5Content = DolbyVisionCompat.isP5Profile(format.codecs)
        Log.i(
            "PLAYER_DV",
            "Compat video format mime=${format.sampleMimeType} codecs=${format.codecs ?: "?"} P5=${isP5Content}"
        )
        // A (re)emitted format starts a fresh sample window (seek / re-init).
        pendingLen = 0
        // When DV conversion is disabled (the DV setting is Off and only the
        // HDR10+ strip toggle is on), declared DV tracks must pass through
        // untouched — no codec rewrite, no RPU strip.
        val dvRewrite =
            if (dvRewriteEnabled && !convertTo81) {
                DolbyVisionCompat.hdr10Codec(format.codecs, convertAllProfiles)
            } else {
                null
            }
        // "8.1" mode: P5 / P7 declared streams are rewritten to Profile 8.1 —
        // the codec stays in the dvhe/dvh1 family (so the Dolby Vision
        // pipeline still engages) with the profile digits changed to 08, the
        // VPS is forced to a single layer, the enhancement layer NALs are
        // dropped, and every RPU is rewritten to 8.1 metadata with a fresh
        // CRC. P4 / P8 declared streams have no matching to81Codec and pass
        // through untouched as native Dolby Vision.
        val to81Rewrite = if (convertTo81) DolbyVisionCompat.to81Codec(format.codecs) else null
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
                DolbyVisionCompat.rewriteInitDataVps(format.initializationData)
            )
            delegate.format(builder.build())
            mode = Mode.STRIPPING
            return
        }
        // Profiles 4/8 are single-layer streams whose base layer is already
        // standard HDR10 HEVC. Forwarding the in-band DV RPU / HDR10+ metadata
        // NALs untouched makes MTK-class decoders re-emit their output format on
        // every frame ("Resolution change XxX to XxX" at video fps) and the
        // compositor drops the frames — black screen with audio. But stripping
        // the metadata while leaving the Dolby Vision VPS in place is worse:
        // the decoder sits in DV mode waiting for RPUs that never arrive and
        // stalls completely (input frames in, zero output). So for these
        // profiles: re-advertise as plain HEVC, rewrite the VPS to a clean
        // single-layer parameter set, and strip the metadata NALs (62/63 +
        // layerId>0 + HDR10+ SEI per toggle) — every other byte bit-exact.
        if (dvRewrite != null && DolbyVisionCompat.isHdr10BaseLayerProfile(format.codecs)) {
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — HDR10 base layer: " +
                    "re-advertising as hvc1, rewriting VPS to single-layer HDR10, " +
                    "stripping DV RPU/EL + HDR10+ metadata NALs"
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
                DolbyVisionCompat.rewriteInitDataVps(format.initializationData)
            )
            delegate.format(builder.build())
            mode = Mode.STRIPPING
            return
        }
        mode = when {
            // Declared Dolby Vision (dvcc present) with DV conversion enabled:
            // Auto rewrites only Profile 7, "All DV" rewrites every profile
            // (4/5/7/8). Strip from the first sample and rewrite the codec
            // string so Media3 never queries a DV decoder. P5 (ICtCp) keeps
            // this path: its pixels are not HDR10, so the downstream GLES
            // shader / FFmpeg conversion needs the rewritten stream.
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
                DolbyVisionCompat.rewriteInitDataVps(format.initializationData)
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
            val hdr10ColorInfo = ColorInfo.Builder()
                .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .setLumaBitdepth(10)
                .setChromaBitdepth(10)
                .build()
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
            if (dvFound && dvRewriteEnabled) {
                mode = Mode.STRIPPING
                val action = if (convertTo81) "converting to Profile 8.1" else "stripping to HDR10"
                Log.i(
                    "PLAYER_DV",
                    "In-band Dolby Vision RPU detected (no dvcc marker, codecs=${currentCodecs ?: "?"}) — $action"
                )
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
        val stripped = if (convertTo81) {
            when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.transformAnnexBTo81(
                        pendingBuf, sampleEnd, stripHdr10Plus = stripHdr10Plus, stats = stats
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.transformLengthDelimitedTo81(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripHdr10Plus = stripHdr10Plus, stats = stats
                    )
            }
        } else {
            when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.transformAnnexB(
                        pendingBuf, sampleEnd, stripHdr10Plus = stripHdr10Plus, stats = stats
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.transformLengthDelimited(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripHdr10Plus = stripHdr10Plus, stats = stats
                    )
            }
        }
        if (stripped >= 0 && !stripReported) {
            stripReported = true
            Log.i(
                "PLAYER_DV",
                if (convertTo81) {
                    "First 8.1-converted sample (codecs=${currentCodecs ?: "?"}) — " +
                        "RPU rewritten=${stats.rpuRewritten} EL=${stats.elBytes}B " +
                        "HDR10+SEI=${stats.hdr10PlusBytes}B vpsRewritten=${stats.vpsRewritten} nals=$inventory"
                } else {
                    "First stripped sample (codecs=${currentCodecs ?: "?"}) — " +
                        "dropped RPU=${stats.rpuBytes}B EL=${stats.elBytes}B HDR10+SEI=${stats.hdr10PlusBytes}B " +
                        "vpsRewritten=${stats.vpsRewritten} nals=$inventory"
                }
            )
        }
        emit(if (stripped >= 0) stripped else sampleEnd)

        if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
        pendingLen = carrySize
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
