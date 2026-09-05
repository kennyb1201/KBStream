@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ColorInfo
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
 * — 4/5/7/8 — is rewritten for displays without Dolby Vision (P5 has no HDR10
 * base, so that one is a best-effort plain-HEVC fallback). Tracks reported as
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
    private val dvRewriteEnabled: Boolean = true
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
                "stripHdr10Plus=$stripHdr10Plus"
        )
        return VideoCompatExtractor(extractor, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled)
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
    private val dvRewriteEnabled: Boolean
) : Extractor {

    override fun init(output: ExtractorOutput) {
        Log.i("PLAYER_DV", "Compat extractor initialized=${delegate.javaClass.simpleName}")
        delegate.init(
            VideoCompatExtractorOutput(output, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled)
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
    private val dvRewriteEnabled: Boolean
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            VideoCompatTrackOutput(track, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled)
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
    private val dvRewriteEnabled: Boolean
) : TrackOutput {

    private enum class Mode { NORMAL, SNIFFING, STRIPPING }

    private var mode = Mode.NORMAL
    private var sniffRemaining = 0
    private var currentCodecs: String? = null
    private var nalLengthFieldLength = 4
    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private val scratch = ParsableByteArray()

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        currentCodecs = format.codecs
        Log.i(
            "PLAYER_DV",
            "Compat video format mime=${format.sampleMimeType} codecs=${format.codecs ?: "?"}"
        )
        // A (re)emitted format starts a fresh sample window (seek / re-init).
        pendingLen = 0
        // When DV conversion is disabled (the DV setting is Off and only the
        // HDR10+ strip toggle is on), declared DV tracks must pass through
        // untouched — no codec rewrite, no RPU strip.
        val dvRewrite =
            if (dvRewriteEnabled) {
                DolbyVisionCompat.hdr10Codec(format.codecs, convertAllProfiles)
            } else {
                null
            }
        mode = when {
            // Declared Dolby Vision (dvcc present) with DV conversion enabled:
            // Auto rewrites only Profile 7, "All DV" rewrites every profile
            // (4/5/7/8). Strip from the first sample and rewrite the codec
            // string so Media3 never queries a DV decoder.
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
            val filteredInit = format.initializationData.filter { buf ->
                !isDvNalUnit(buf)
            }
            builder = builder.setInitializationData(filteredInit)
            val rewritten = builder.build()
            if (rewritten.colorInfo == null) {
                val hdr10ColorInfo = ColorInfo(
                    colorTransfer = C.COLOR_TRANSFER_2084,
                    colorPrimaries = C.COLOR_PRIMARIES_BT2020,
                    colorRange = C.COLOR_RANGE_LIMITED,
                    lumaBitdepth = 10,
                    chromaBitdepth = 10
                )
                delegate.format(rewritten.buildUpon().setColorInfo(hdr10ColorInfo).build())
            } else {
                delegate.format(rewritten)
            }
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
                Log.i(
                    "PLAYER_DV",
                    "In-band Dolby Vision RPU detected (no dvcc marker, codecs=${currentCodecs ?: "?"}) — stripping to HDR10"
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

        val stripped = when (framing) {
            NalFraming.ANNEX_B ->
                DolbyVisionCompat.stripAnnexB(
                    pendingBuf, sampleEnd, stripDv = dvRewriteEnabled, stripHdr10Plus = stripHdr10Plus
                )
            NalFraming.LENGTH_DELIMITED ->
                DolbyVisionCompat.stripLengthDelimited(
                    pendingBuf, sampleEnd, nalLengthFieldLength,
                    stripDv = dvRewriteEnabled, stripHdr10Plus = stripHdr10Plus
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

    private fun isDvNalUnit(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        val b2 = data[2].toInt() and 0xFF
        val startCodeLen: Int
        val isFourByte = b0 == 0 && b1 == 0 && b2 == 0
        if (isFourByte && data.size >= 5 && data[3].toInt() and 0xFF == 1) {
            startCodeLen = 4
        } else if (!isFourByte && b2 == 1) {
            startCodeLen = 3
        } else {
            return false
        }
        if (data.size < startCodeLen + 1) return false
        val nalHeader = data[startCodeLen].toInt() and 0xFF
        val nalType = (nalHeader ushr 1) and 0x3F
        return nalType == 62 || nalType == 63
    }

    private companion object {
        private val PLAIN_HEVC_CODEC = Regex("(?i)^(hvc1|hev1)\\.")
        private const val SNIFF_BUDGET_SAMPLES = 24
    }
}
