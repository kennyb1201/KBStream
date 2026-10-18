package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.DataReader
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared-DV "Nuvio-style" strip: mode 3 on a box whose DV decoder drives a
 * display that cannot show Dolby Vision.
 *
 * Every relabel-and-rewrite variant of this stream is field-dead on the Fire TV
 * Stick 4K Plus (declared `dvhe.08.06`, `OMX.MTK.VIDEO.DECODER.HEVC`:
 * relabel-only, relabel + config VPS rewrite, in-sample VPS/SPS rewrite with the
 * NAL-40 drop, and the config SPS rewrite all configure OK and emit nothing),
 * while the reference player plays the same files on the same box by stripping
 * LESS than we do: it removes the RPU/EL NALs and leaves the track declared as
 * Dolby Vision, the codec config byte-identical and every SEI NAL in place.
 *
 * So this file pins the new mode's whole contract behaviorally — the factory is
 * driven end to end through [TrackOutput] with a fake delegate, which is the
 * only way to see the bytes a decoder would actually be handed:
 *
 *  - the sample keeps its parameter sets and every SEI NAL, and loses the RPU
 *    (62) and the enhancement layer (63);
 *  - the advertised format stays `video/dolby-vision` with the container's own
 *    `dvhe.08.06`, and the codec config is the container's, byte for byte;
 *  - and the decision that selects the mode flips back to the previous behavior
 *    on every one of its conditions.
 */
class DolbyVisionStripRpuKeepDvTest {

    // ── behavioral: the sample the decoder is handed ───────────────────────

    private val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
    private val sps = nal(33, byteArrayOf(0x01, 0x22, 0x20, 0x00, 0x00, 0x96.toByte()))

    /**
     * A prefix SEI (39) whose only message is payload type 5 — the vendor /
     * user-data message a Dolby Vision RPU's own SEI rides in. It is the NAL
     * `Mode.STRIP_NALS_ONLY` drops (`declaredDv = true`) and this mode must keep.
     */
    private val dvSei = nal(39, byteArrayOf(0x05, 0x08, 0x01, 0x02, 0x03, 0x04, 0x80.toByte()))

    /**
     * An ST 2094-40 (HDR10+) message: payload type 4 whose payload opens with the
     * T35 marker the detector requires (`B5 00 3C 00 01 04`).
     */
    private val hdr10PlusMessage = byteArrayOf(
        0x04, 0x0A,
        0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x02, 0x03, 0x04
    )

    /** A vendor (DV-style) message, payload type 5, in its own SEI message. */
    private val dvMessage = byteArrayOf(0x05, 0x03, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

    private val hdr10PlusSei = nal(39, hdr10PlusMessage + byteArrayOf(0x80.toByte()))

    /**
     * One SEI NAL carrying BOTH an HDR10+ message and a vendor message — the
     * shape the surgical HDR10+ strip exists for: removing the message, keeping
     * the NAL and everything else in it.
     */
    private val mixedSei =
        nal(39, hdr10PlusMessage + dvMessage + byteArrayOf(0x80.toByte()))
    private val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))
    private val rpu = nal(62, byteArrayOf(0x19, 0x00, 0x00, 0x00, 0x00))
    private val el = nal(63, byteArrayOf(0x21, 0x30, 0x40))
    private val idr = nal(19, byteArrayOf(0x51, 0x62, 0x73, 0x84.toByte()))

    @Test
    fun `the keep-DV strip drops the RPU and the EL and keeps every SEI and the parameter sets`() {
        val harness = drive(
            sample = annexB(vps, sps, pps, dvSei, hdr10PlusSei, rpu, el, idr)
        )

        val emitted = harness.emittedSamples.single()
        assertArrayEquals(
            "the sample must lose the RPU (62) and the EL (63) and nothing else: " +
                "parameter sets, both SEI NALs and the slice all stay bit-exact",
            annexB(vps, sps, pps, dvSei, hdr10PlusSei, idr),
            emitted
        )
        val types = nalTypes(emitted)
        assertFalse("no RPU may survive: types=$types", types.contains(62))
        assertFalse("no enhancement layer may survive: types=$types", types.contains(63))
        assertTrue(
            "and the DV vendor SEI (39, pt=5) must survive — it is the half the " +
                "relabel modes deliberately drop",
            types.contains(39)
        )
        assertEquals(
            "the parameter sets are the container's, untouched",
            listOf(32, 33, 34),
            types.filter { it in setOf(32, 33, 34) }
        )
    }

    @Test
    fun `the HDR10+ SEI follows the user toggle, not this mode`() {
        // The stream is still presented as Dolby Vision, so this arm has no
        // business deciding HDR10+ policy of its own: with the toggle off (the
        // default) the HDR10+ message rides through, exactly as the reference
        // player leaves it.
        val off = drive(
            sample = annexB(vps, sps, pps, mixedSei, rpu, idr),
            stripHdr10Plus = false
        )
        assertArrayEquals(
            "toggle off: the HDR10+ message is kept",
            annexB(vps, sps, pps, mixedSei, idr),
            off.emittedSamples.single()
        )

        // Toggle ON: the HDR10+ message is surgically removed from the NAL that
        // shares it with a vendor message. The NAL itself stays, which is the
        // difference from libdovi's coarse whole-NAL drop.
        val on = drive(
            sample = annexB(vps, sps, pps, mixedSei, rpu, idr),
            stripHdr10Plus = true
        )
        val stripped = on.emittedSamples.single()
        assertArrayEquals(
            "toggle on: the HDR10+ message goes, the vendor message and the NAL stay",
            annexB(vps, sps, pps, nal(39, dvMessage + byteArrayOf(0x80.toByte())), idr),
            stripped
        )
    }

    @Test
    fun `an all-HDR10+ SEI NAL is never dropped on this path`() {
        // The reference player never removes an SEI NAL, and this mode follows it
        // even with the HDR10+ toggle on: the message inside an all-HDR10+ NAL is
        // KEPT whole (`keepNalWhenAllHdr10Plus` on the stripper), because dropping
        // the NAL is
        // what green-screened the 2026-10-14 field file — that NAL also carried
        // the HDR10 static metadata the picture needs.
        val on = drive(
            sample = annexB(vps, sps, pps, hdr10PlusSei, rpu, idr),
            stripHdr10Plus = true
        )
        assertArrayEquals(
            "the RPU goes, the all-HDR10+ SEI NAL stays bit-exact",
            annexB(vps, sps, pps, hdr10PlusSei, idr),
            on.emittedSamples.single()
        )
    }

    @Test
    fun `the advertised format keeps the declared Dolby Vision mime and codec string`() {
        val source = declaredDvFormat()
        val harness = drive(sample = annexB(vps, sps, pps, dvSei, rpu, idr), format = source)

        val presented = harness.emittedFormats.single()
        assertEquals(
            "the track stays declared as Dolby Vision, which is what makes this box's own " +
                "DV decoder take it instead of the HEVC decoder that emits nothing",
            MimeTypes.VIDEO_DOLBY_VISION,
            presented.sampleMimeType
        )
        assertEquals(
            "with the container's own codec string — no hvc1 relabel",
            "dvhe.08.06",
            presented.codecs
        )
        assertNull(
            "and no badging label was added (the relabel puts the DV codec there)",
            presented.label
        )
        assertEquals(
            "the codec config reaches the decoder in exactly one list, in order",
            1,
            presented.initializationData.size
        )
        assertArrayEquals(
            "and byte-identical to the container's: no VPS/SPS rewrite on this path",
            source.initializationData.single(),
            presented.initializationData.single()
        )
        assertArrayEquals(
            "which is the same buffer the sample's parameter sets came out of",
            annexB(vps, sps, pps),
            presented.initializationData.single()
        )
    }

    @Test
    fun `the keep-DV strip is removal only and never rewrites the stream`() {
        // The whole point of the mode is what it does NOT do. A sample whose
        // parameter sets the mutating strip would have rewritten comes out
        // identical because this mode never reaches transformAnnexB.
        val sample = annexB(vps, sps, pps, idr, rpu)
        val harness = drive(sample = sample)

        assertArrayEquals(
            "no in-sample VPS/SPS rewrite, no color-info rebuild — removal only",
            annexB(vps, sps, pps, idr),
            harness.emittedSamples.single()
        )
        val types = nalTypes(harness.emittedSamples.single())
        assertEquals(
            "and every NAL that is not the RPU/EL is still there in its original order",
            listOf(32, 33, 34, 19),
            types
        )
    }

    // ── decision: who selects the mode ─────────────────────────────────────

    @Test
    fun `mode 3 on a DV decoder with a non-DV display selects the keep-DV strip`() {
        assertEquals(
            "declared P8, DV decoder, display that cannot show Dolby Vision, Strip All",
            Hdr10BaseHandling.STRIP_RPU_KEEP_DV,
            selectHdr10BaseHandling(
                nativeDvSupported = true,
                nonDvDisplayOnDvDevice = false, // an AUTO-only fact, false in mode 3
                convertAllProfiles = true,
                displaySupportsDv = false
            )
        )
    }

    @Test
    fun `flipping any one condition falls back to the previous behavior`() {
        // No DV decoder: the mutating strip, which is the tested working path on
        // those boxes.
        assertEquals(
            "no DV decoder",
            Hdr10BaseHandling.STRIP,
            selectHdr10BaseHandling(
                nativeDvSupported = false,
                nonDvDisplayOnDvDevice = false,
                convertAllProfiles = true,
                displaySupportsDv = false
            )
        )
        // A DV-capable display: "Strip All" there is the user asking for HDR10,
        // and the relabel arm is what mode 3 has always used.
        assertEquals(
            "DV display",
            Hdr10BaseHandling.STRIP,
            selectHdr10BaseHandling(
                nativeDvSupported = true,
                nonDvDisplayOnDvDevice = false,
                convertAllProfiles = true,
                displaySupportsDv = true
            )
        )
        // An unknown display is not a non-DV display: the mode may not be
        // inferred from the absence of the AUTO-mode flag.
        assertEquals(
            "display capability not supplied",
            Hdr10BaseHandling.STRIP,
            selectHdr10BaseHandling(
                nativeDvSupported = true,
                nonDvDisplayOnDvDevice = false,
                convertAllProfiles = true,
                displaySupportsDv = null
            )
        )
        // Auto, both display states: untouched.
        assertEquals(
            "Auto, DV display",
            Hdr10BaseHandling.PASSTHROUGH,
            selectHdr10BaseHandling(true, nonDvDisplayOnDvDevice = false, convertAllProfiles = false, displaySupportsDv = true)
        )
        // Auto on a DV decoder with a non-DV display: the call site withholds
        // native passthrough itself (`nativeDvSupported` is false there — it is
        // gated on the display in Auto) and reports the mismatch instead, which
        // is the combination that picks the relabel-only experiment.
        assertEquals(
            "Auto, non-DV display (the relabel-only experiment)",
            Hdr10BaseHandling.RELABEL_ONLY,
            selectHdr10BaseHandling(
                nativeDvSupported = false,
                nonDvDisplayOnDvDevice = true,
                convertAllProfiles = false,
                displaySupportsDv = false
            )
        )
    }

    @Test
    fun `an in-band-only track never reaches the keep-DV strip`() {
        // A plain-HEVC remux whose DV signalling is in-band has no declared codec
        // string to keep: the sniff path is the previous behavior for it, and this
        // mode's format (video/dolby-vision) must never be what it is advertised as.
        val plainHevc = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L120.B0")
            .setInitializationData(listOf(annexB(vps, sps, pps)))
            .build()

        val harness = drive(
            sample = annexB(vps, sps, pps, rpu, idr),
            format = plainHevc
        )

        assertTrue(
            "the track must reach the decoder",
            harness.emittedFormats.isNotEmpty()
        )
        assertTrue(
            "every format it is advertised under stays HEVC: the keep-DV arm keeps " +
                "video/dolby-vision and is unreachable without a declared codec string",
            harness.emittedFormats.all { it.sampleMimeType == MimeTypes.VIDEO_H265 }
        )
        assertTrue(
            "and no DV codec string is ever advertised for it",
            harness.emittedFormats.none {
                val codecs = it.codecs ?: ""
                codecs.startsWith("dvhe") || codecs.startsWith("dvh1") || codecs.startsWith("dvav")
            }
        )
        assertFalse(
            "the sample takes the sniff path's existing RPU drop — previous behavior, " +
                "which this mode must not have moved: types=" +
                nalTypes(harness.emittedSamples.single()),
            nalTypes(harness.emittedSamples.single()).contains(62)
        )
    }

    // ── wiring ─────────────────────────────────────────────────────────────

    @Test
    fun `the new mode is engaged by the decision and names itself in the field log`() {
        val source = source()
        assertTrue(
            "the mode must exist so the arm cannot be silently routed through the " +
                "mutating transform",
            source.contains("STRIP_RPU_KEEP_DV")
        )
        assertTrue(
            "the arm must engage it",
            source.contains("mode = Mode.STRIP_RPU_KEEP_DV")
        )
        assertTrue(
            "and the log has to name the strip, beside the fields a capture already greps",
            source.contains("stripRpuKeepDv=applied") &&
                source.contains("relabel=skipped(keepDv)") &&
                source.contains("vpsRewrite=skipped(keepDv)") &&
                source.contains("spsRewrite=skipped(keepDv)")
        )
        val handler = handler()
        assertEquals(
            "both framings hand the stripper the RPU/EL drop",
            2,
            Regex("stripDv = true,").findAll(handler).count()
        )
        assertEquals(
            "and both keep the HDR10+ decision on the user's toggle",
            2,
            Regex("stripHdr10Plus = stripHdr10Plus,").findAll(handler).count()
        )
        assertEquals(
            "the Kotlin surgery, not libdovi's coarse whole-SEI drop",
            2,
            Regex("kotlinOnly = true,").findAll(handler).count()
        )
        assertFalse(
            "and neither framing may arm the declared-DV suffix-SEI drop: that is the " +
                "2026-10-14 trial's half, and it removes DV signalling from a track that " +
                "still declares Dolby Vision",
            handler.contains("declaredDv =")
        )
        assertFalse(
            "nor rewrite the in-sample parameter sets the codec config was forwarded with",
            handler.contains("rewriteParamSets =")
        )
        assertFalse(
            "the arm is removal only",
            handler.contains("transformAnnexB") || handler.contains("transformLengthDelimited")
        )
    }

    @Test
    fun `the display capability is passed in every mode, not inferred from the AUTO flag`() {
        val activity = read("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
        assertTrue(
            "the call site hands the factory the display's own capability",
            activity.contains("displaySupportsDv = displaySupportsDv")
        )
        assertTrue(
            "which is the raw capability read, not the AUTO-only mismatch flag",
            activity.contains("val displaySupportsDv = DolbyVisionCompat.displaySupportsDolbyVision(this)")
        )
        // The factory's own reason for the extra parameter: `nonDvDisplayOnDvDevice`
        // cannot answer the mode-3 question, because it is false there by
        // construction.
        val source = source()
        assertTrue(
            "the factory must document that distinction",
            source.contains("nonDvDisplayOnDvDevice") &&
                source.contains("displaySupportsDv == false")
        )
    }

    // ── harness ────────────────────────────────────────────────────────────

    /** What the delegate TrackOutput was handed, in order. */
    private class Harness {
        val emittedFormats = mutableListOf<Format>()
        val emittedSamples = mutableListOf<ByteArray>()
    }

    private class RecordingTrackOutput(private val harness: Harness) : TrackOutput {
        override fun durationUs(durationUs: Long) = Unit

        override fun format(format: Format) {
            harness.emittedFormats += format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean) = 0

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ) = 0

        override fun sampleData(data: ParsableByteArray, length: Int) {
            harness.emittedSamples += copy(data, length)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            harness.emittedSamples += copy(data, length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) = Unit

        private fun copy(data: ParsableByteArray, length: Int): ByteArray {
            val from = data.position
            return data.data.copyOfRange(from, from + length)
        }
    }

    private class HarnessExtractor : Extractor {
        var videoTrack: TrackOutput? = null

        override fun init(output: ExtractorOutput) {
            videoTrack = output.track(0, C.TRACK_TYPE_VIDEO)
        }

        override fun sniff(input: ExtractorInput): Boolean = false

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun release() = Unit

        override fun getUnderlyingImplementation(): Extractor = this
    }

    private class SingleExtractorFactory(private val extractor: Extractor) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = arrayOf(extractor)

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>
        ): Array<Extractor> = arrayOf(extractor)
    }

    private class HarnessOutput(private val harness: Harness) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = RecordingTrackOutput(harness)

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    /** A declared-`dvhe.08.06` track, as the container hands it over. */
    private fun declaredDvFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs("dvhe.08.06")
        .setInitializationData(listOf(annexB(vps, sps, pps)))
        .build()

    /**
     * Runs one sample through the REAL factory in mode 3 with a DV decoder and a
     * non-DV display, and returns what the delegate TrackOutput received.
     *
     * The delegate is a fake Extractor, so nothing here is a stub of the path
     * under test: the wrapper's own format() and sampleMetadata() decide, strip
     * and emit exactly as they do for a real container.
     */
    private fun drive(
        sample: ByteArray,
        format: Format = declaredDvFormat(),
        stripHdr10Plus: Boolean = false
    ): Harness {
        val harness = Harness()
        val delegate = HarnessExtractor()
        val factory = DolbyVisionCompatExtractorsFactory(
            delegate = SingleExtractorFactory(delegate),
            stripHdr10Plus = stripHdr10Plus,
            convertAllProfiles = true,
            dvRewriteEnabled = true,
            nativeDvSupported = true,
            // False on purpose: mode 3 must not need the AUTO-only flag.
            nonDvDisplayOnDvDevice = false,
            displaySupportsDv = false
        )
        // The wrapper hands the delegate a wrapping track output during init, so
        // the track the delegate recorded is the one under test.
        factory.createExtractors().single().init(HarnessOutput(harness))
        val track = delegate.videoTrack ?: error("the delegate never received a track output")

        track.format(format)
        // The DataReader overload, which is the path a real ExtractorInput takes
        // and the only one that does not route through
        // ParsableByteArray.readBytes (whose media3 internals need a system
        // property this JVM test worker does not carry).
        track.sampleData(SampleReader(sample), sample.size, false)
        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        return harness
    }

    /** An in-memory [DataReader], which is what an ExtractorInput hands over. */
    private class SampleReader(private val bytes: ByteArray) : DataReader {
        private var position = 0

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            val remaining = bytes.size - position
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            val take = minOf(readLength, remaining)
            System.arraycopy(bytes, position, buffer, offset, take)
            position += take
            return take
        }
    }

    // ── fixture ────────────────────────────────────────────────────────────

    /** A 2-byte NAL header for [type] plus [payload] as the RBSP. */
    private fun nal(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(((type shl 1) and 0xFF).toByte(), 0x01) + payload

    /** Annex-B framing: a 4-byte start code before every NAL. */
    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        nals.forEach { nal ->
            out.add(0); out.add(0); out.add(0); out.add(1)
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    /** The NAL unit types in an Annex-B sample, in order. */
    private fun nalTypes(sample: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i + 4 <= sample.size) {
            val startCodeLen = when {
                sample[i] == 0.toByte() && sample[i + 1] == 0.toByte() &&
                    sample[i + 2] == 0.toByte() && sample[i + 3] == 1.toByte() -> 4
                sample[i] == 0.toByte() && sample[i + 1] == 0.toByte() &&
                    sample[i + 2] == 1.toByte() -> 3
                else -> {
                    i++
                    continue
                }
            }
            val header = i + startCodeLen
            if (header + 1 < sample.size) {
                types += (sample[header].toInt() and 0xFF) ushr 1 and 0x3F
            }
            i = header
        }
        return types
    }

    /** The new mode's `sampleMetadata` handler, from its branch to the next one. */
    private fun handler(): String {
        val source = source()
        val start = source.indexOf("if (mode == Mode.STRIP_RPU_KEEP_DV) {")
        assertTrue("the keep-DV sample handler not found", start >= 0)
        val end = source.indexOf("if (mode == Mode.STRIP_NALS_ONLY) {", start)
        assertTrue("the handler end not found", end > start)
        return source.substring(start, end)
    }

    private fun source(): String = read(EXTRACTOR)

    private fun read(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
    }
}
