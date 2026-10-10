package com.kennyb1201.kbstream.ui.player

import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Dolby Vision profile routing that the player and the compat
 * extractor depend on.
 *
 * The failure this guards is specific and was real: Profile 5 is single-layer
 * ICtCp, so *relabeling* it as Profile 8.1 hands the display ICtCp samples
 * interpreted as Rec.2020 PQ — the green/purple picture. P5 must therefore
 * never take the 8.1 rewrite; it is stripped and color-corrected on the GPU
 * (or played natively as Dolby Vision when the device has a DV decoder).
 * These tests pin every branch of that decision so a future edit cannot
 * silently route P5 back through the 8.1 relabel.
 */
class DolbyVisionCompatTest {

    // ── P5 detection (drives the GPU ICtCp color path) ────────────────────

    @Test
    fun `profile 5 codecs are detected in every spelling`() {
        assertTrue(DolbyVisionCompat.isP5Profile("dvhe.05.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("dvh1.05.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("dvhe.5.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("DVHE.05.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("  dvh1.05.09  "))
    }

    @Test
    fun `other profiles and plain HEVC are not P5`() {
        assertFalse(DolbyVisionCompat.isP5Profile("dvhe.07.06"))
        assertFalse(DolbyVisionCompat.isP5Profile("dvhe.08.06"))
        assertFalse(DolbyVisionCompat.isP5Profile("dvhe.04.06"))
        assertFalse(DolbyVisionCompat.isP5Profile("hvc1.2.4.L153.B0"))
        assertFalse(DolbyVisionCompat.isP5Profile(null))
        assertFalse(DolbyVisionCompat.isP5Profile(""))
        assertFalse(DolbyVisionCompat.isP5Profile("   "))
    }

    // ── HDR10-base-layer profiles (pass through / strip, never 8.1) ───────

    @Test
    fun `profiles 4 and 8 have an HDR10 base layer`() {
        assertTrue(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.04.06"))
        assertTrue(DolbyVisionCompat.isHdr10BaseLayerProfile("dvh1.08.06"))
        assertTrue(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.8.06"))
    }

    @Test
    fun `profiles 5 and 7 do not have an HDR10 base layer`() {
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.05.06"))
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.07.06"))
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile("hvc1.2.4"))
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile(null))
    }

    // ── The 8.1 relabel: P7 only, and P5 must be excluded ─────────────────

    @Test
    fun `P7 relabels to Profile 8_1 only when its conversion is on`() {
        assertEquals("dvhe.08.06", DolbyVisionCompat.to81Codec("dvhe.07.06", true, false))
        assertEquals("dvhe.08.06", DolbyVisionCompat.to81Codec("dvhe.7.06", true, false))
        assertNull(DolbyVisionCompat.to81Codec("dvhe.07.06", false, false))
    }

    @Test
    fun `P5 relabels to Profile 8_1 only via its own flag, never via the P7 flag`() {
        assertEquals("dvh1.08.06", DolbyVisionCompat.to81Codec("dvh1.05.06", false, true))
        // The P7 toggle must not drag P5 into the 8.1 relabel (the green/
        // purple regression).
        assertNull(DolbyVisionCompat.to81Codec("dvhe.05.06", true, false))
        // And with both toggles off nothing happens.
        assertNull(DolbyVisionCompat.to81Codec("dvh1.05.06", false, false))
    }

    @Test
    fun `profiles 4 and 8 and plain HEVC never relabel`() {
        assertNull(DolbyVisionCompat.to81Codec("dvhe.04.06", true, true))
        assertNull(DolbyVisionCompat.to81Codec("dvhe.08.06", true, true))
        assertNull(DolbyVisionCompat.to81Codec("hvc1.2.4.L153.B0", true, true))
        assertNull(DolbyVisionCompat.to81Codec(null, true, true))
    }

    // ── The strip-to-HDR10 target codec ───────────────────────────────────

    @Test
    fun `strip targets P7 alone unless Strip All is on`() {
        assertEquals(DolbyVisionCompat.HDR10_CODEC, DolbyVisionCompat.hdr10Codec("dvhe.07.06"))
        assertNull(DolbyVisionCompat.hdr10Codec("dvhe.08.06"))
        assertNull(DolbyVisionCompat.hdr10Codec("dvhe.05.06"))
        assertNull(DolbyVisionCompat.hdr10Codec("hvc1.2.4"))
    }

    @Test
    fun `Strip All strips every DV profile`() {
        for (codec in listOf("dvhe.04.06", "dvhe.05.06", "dvhe.07.06", "dvhe.08.06", "dvh1.08.06")) {
            assertEquals(
                "expected $codec to strip under Strip All",
                DolbyVisionCompat.HDR10_CODEC,
                DolbyVisionCompat.hdr10Codec(codec, convertAllProfiles = true)
            )
        }
        assertNull(DolbyVisionCompat.hdr10Codec("hvc1.2.4.L153.B0", convertAllProfiles = true))
    }

    // ── The label the player reads back to detect the source profile ───────

    /**
     * The player detects P5 from the extractor's `label` (the original declared
     * codec, preserved through the rewrite), because the rewritten codecs
     * string is plain HEVC. If this label extraction ever stops recognizing
     * P5, the GPU color path never engages and P5 plays green/purple again.
     */
    @Test
    fun `declared P5 survives the rewrite as a DV P5 label`() {
        assertEquals("DV P5", dvLabelFromCodec("dvhe.05.06"))
        assertEquals("DV P5", dvLabelFromCodec("dvh1.05.06"))
        assertEquals("DV P7", dvLabelFromCodec("dvhe.07.06"))
        assertEquals("DV P8", dvLabelFromCodec("dvh1.08.06"))
        assertEquals("DV P4", dvLabelFromCodec("dvhe.04.06"))
        assertNull(dvLabelFromCodec("hvc1.2.4.L153.B0"))
        assertNull(dvLabelFromCodec(null))
    }

    @Test
    fun `badges describe a stripped P5 as HDR10, not as 8_1`() {
        assertEquals("DV P5 → HDR10", normalizeCodec("hvc1.2.4.L153.B0", "dvhe.05.06"))
        assertEquals("DV P7 → 8.1", normalizeCodec("hvc1.2.4.L153.B0", "dvhe.07.06", convertedTo81 = true))
        assertEquals("H.265", normalizeCodec("hvc1.2.4.L153.B0"))
    }

    // Learned device capability: a box whose DV decoder hard-fails.

    @Test
    fun `a recorded DV decoder failure suppresses passthrough inside its TTL`() {
        val failedAt = 1_000_000L
        assertTrue(dvPassthroughSuppressed(failedAt, failedAt))
        assertTrue(
            dvPassthroughSuppressed(
                failedAt,
                failedAt + DV_PASSTHROUGH_FAILURE_TTL_MS - 1L
            )
        )
    }

    @Test
    fun `the suppression expires so Dolby Vision can come back on its own`() {
        val failedAt = 1_000_000L
        assertFalse(dvPassthroughSuppressed(failedAt, failedAt + DV_PASSTHROUGH_FAILURE_TTL_MS))
        assertFalse(
            dvPassthroughSuppressed(failedAt, failedAt + DV_PASSTHROUGH_FAILURE_TTL_MS * 10L)
        )
    }

    @Test
    fun `nothing recorded means passthrough is never suppressed`() {
        assertFalse(dvPassthroughSuppressed(0L, System.currentTimeMillis()))
    }

    // Vendor DV decoder refusal vs. genuine resource exhaustion.
    //
    // The TCL/Realtek DV decoder reports its refusal with the platform's own
    // out-of-resources code (0x80001000), so the player must tell the two
    // apart by the session. Getting this wrong sent a DV-capable TV to the
    // "out of video decoder resources" banner instead of the HDR10 strip.

    @Test
    fun `a DV passthrough session whose decoder failed is the vendor refusal`() {
        assertTrue(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = true,
                declaredDvCodec = "dvhe.08.06",
                alreadyStripped = false
            )
        )
    }

    @Test
    fun `a plain HEVC failure with passthrough on is not the DV refusal`() {
        // Passthrough is on, but the failing track is not Dolby Vision: this
        // is genuine exhaustion and must keep the next-source recovery.
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = true,
                declaredDvCodec = "hvc1.2.4.L153.B0",
                alreadyStripped = false
            )
        )
    }

    @Test
    fun `a DV failure without passthrough active is not the refusal`() {
        // Passthrough was already suppressed/stripped: re-stripping proves
        // nothing, so this must fall through to the resource recovery.
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = false,
                declaredDvCodec = "dvhe.07.06",
                alreadyStripped = false
            )
        )
    }

    @Test
    fun `an already-stripped session never re-takes the DV path`() {
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = true,
                declaredDvCodec = "dvhe.07.06",
                alreadyStripped = true
            )
        )
    }

    @Test
    fun `a non-decoder failure is never the DV refusal`() {
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = false,
                dvPassthroughActive = true,
                declaredDvCodec = "dvhe.08.06",
                alreadyStripped = false
            )
        )
    }

    // ── display probe (drives display-aware native DV) ────────────────────

    @Test
    fun `an unknown display capability fails open`() {
        // No platform answer must keep today's behavior, not force a strip.
        assertTrue(hdrTypesIncludeDv(null))
    }

    @Test
    fun `a DV-capable display is recognised and a non-DV one is not`() {
        assertTrue(
            hdrTypesIncludeDv(
                intArrayOf(
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
                )
            )
        )
        assertTrue(
            hdrTypesIncludeDv(intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION))
        )
        // HDR10 alone is the Fire TV Stick -> non-DV TV case: no DV sink.
        assertFalse(
            hdrTypesIncludeDv(
                intArrayOf(
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    Display.HdrCapabilities.HDR_TYPE_HLG
                )
            )
        )
        assertFalse(hdrTypesIncludeDv(intArrayOf()))
    }

    // ── P4/P8 handling (drives the extractor's branch selection) ──────────

    @Test
    fun `a DV box on a DV display passes P4 P8 through untouched`() {
        assertEquals(
            Hdr10BaseHandling.PASSTHROUGH,
            selectHdr10BaseHandling(
                nativeDvSupported = true,
                nonDvDisplayOnDvDevice = false,
                convertAllProfiles = false
            )
        )
    }

    @Test
    fun `a DV box on a non-DV display relabels without touching the bitstream`() {
        assertEquals(
            Hdr10BaseHandling.RELABEL_ONLY,
            selectHdr10BaseHandling(
                nativeDvSupported = false,
                nonDvDisplayOnDvDevice = true,
                convertAllProfiles = false
            )
        )
    }

    @Test
    fun `a box without a DV decoder keeps the existing strip path`() {
        assertEquals(
            Hdr10BaseHandling.STRIP,
            selectHdr10BaseHandling(
                nativeDvSupported = false,
                nonDvDisplayOnDvDevice = false,
                convertAllProfiles = false
            )
        )
    }

    @Test
    fun `Strip All always strips, whatever the box and display say`() {
        listOf(false, true).forEach { native ->
            listOf(false, true).forEach { mismatch ->
                assertEquals(
                    Hdr10BaseHandling.STRIP,
                    selectHdr10BaseHandling(
                        nativeDvSupported = native,
                        nonDvDisplayOnDvDevice = mismatch,
                        convertAllProfiles = true
                    )
                )
            }
        }
    }

    // ── Pure metadata-NAL removal (the relabel-only experiment) ───────────
    //
    // The relabel-only path (DV-decoder box, non-DV display, Auto) re-advertises
    // the HDR10 base layer as plain HEVC and must remove ONLY the DV RPU/EL and
    // HDR10+ SEI NALs, leaving the VPS/SPS/PPS bytes identical — rewriting the
    // parameter sets is what stalls MTK-class decoders (field: Strip All =
    // configure OK, zero frames). This pins that property on a synthetic
    // Annex-B access unit.

    @Test
    fun `stripAnnexB drops the RPU and HDR10 plus SEI but keeps the parameter sets`() {
        val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
        val sps = nal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))
        val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val sei = nal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
        val vcl = nal(19, byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte()))

        val au = vps + sps + pps + rpu + sei + vcl
        val buf = au.copyOf()
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = true, stripHdr10Plus = true
        )

        assertTrue("the RPU and SEI must have been removed", newLen >= 0)
        // The VPS/SPS/PPS/VCL bytes are carried through untouched; only the RPU
        // and the HDR10+ SEI are gone.
        val expected = vps + sps + pps + vcl
        assertArrayEquals("parameter sets and VCL must be byte-identical", expected, buf.copyOf(newLen))
    }

    /**
     * The relabel-only path calls `stripAnnexB(stripDv = false, stripHdr10Plus =
     * true)`: only the HDR10+ SEI goes and the DV RPU is KEPT, because the
     * decoder configured from the untouched DV VPS stalls waiting for RPUs that
     * never arrive. Pinning keep-RPU here is what keeps a future edit from
     * routing this path through the full strip.
     */
    @Test
    fun `stripAnnexB with the RPU kept drops only the HDR10 plus SEI`() {
        val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
        val sps = nal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))
        val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val sei = nal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
        val vcl = nal(19, byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte()))

        val au = vps + sps + pps + rpu + sei + vcl
        val buf = au.copyOf()
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true
        )

        assertTrue("the HDR10+ SEI must have been removed", newLen >= 0)
        // The DV RPU stays (dropping it stalls the decoder) and the parameter
        // sets are never rewritten; only the HDR10+ SEI is gone.
        val expected = vps + sps + pps + rpu + vcl
        assertArrayEquals(
            "RPU and parameter sets must be byte-identical",
            expected,
            buf.copyOf(newLen)
        )
    }

    // ── HDR10+ detection is a PREFIX match ────────────────────────────────
    //
    // Field 2026-10-10 (MULTi dvhe.08.06): the relabel + HDR10+-only strip
    // stalled — bitrate 0, no first frame — on a resume where the T35 SEI at
    // the seek point carried DV RPU data. The detector used to search the WHOLE
    // payload for the 6 marker bytes, so an RPU SEI that happened to contain
    // them anywhere was misread as HDR10+, deleted, and the MTK decoder stalled
    // waiting for the RPU its (untouched) VPS declares. The identifier is a
    // HEADER: it has to open the T35 payload. A match anywhere else is RPU data
    // and must be kept.

    /** ST 2094-40 T35 header: country 0xB5, provider 0x003C, orientation 0x0001, app 0x04. */
    private val HDR10_PLUS_HEADER = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04)

    /** A user-data-registered-ITU-T-T35 (payloadType 4) SEI NAL carrying [payload]. */
    private fun t35Sei(payload: ByteArray): ByteArray =
        nal(39, byteArrayOf(0x04, payload.size.toByte()) + payload)

    /** True when `stripHdr10Plus`-only stripping removes the SEI from a SEI+VCL unit. */
    private fun hdr10PlusSeiIsStripped(sei: ByteArray): Boolean {
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val newLen = DolbyVisionCompat.stripAnnexB(
            au.copyOf(), au.size, stripDv = false, stripHdr10Plus = true
        )
        return newLen == vcl.size
    }

    @Test
    fun `an SEI whose T35 payload opens with the HDR10 plus header is stripped`() {
        assertTrue(
            "a genuine HDR10+ SEI (header at payload start) must still be dropped",
            hdr10PlusSeiIsStripped(t35Sei(HDR10_PLUS_HEADER))
        )
    }

    @Test
    fun `an SEI whose T35 payload only CONTAINS those bytes mid-payload is kept`() {
        // The regression: an RPU-shaped payload with the marker bytes 10 bytes
        // in. Deleting this is what stalled the decoder in the field.
        val rpuShaped = ByteArray(10) { (0x10 + it).toByte() } + HDR10_PLUS_HEADER
        assertFalse(
            "RPU data that merely contains the marker bytes must be kept",
            hdr10PlusSeiIsStripped(t35Sei(rpuShaped))
        )
    }

    @Test
    fun `a header split by an emulation-prevention 0x03 is still the header`() {
        // The escape only ever follows a 0x00 run, so it is tolerated after a
        // matched 0x00 and nowhere else: B5 00 3C 00 [03] 01 04.
        val escaped = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x03, 0x01, 0x04)
        assertTrue(
            "an emulation-prevention byte must not hide the header",
            hdr10PlusSeiIsStripped(t35Sei(escaped))
        )
    }

    @Test
    fun `a 0x03 after a byte that is not a zero is a mismatch, not an escape`() {
        // The old matcher skipped a 0x03 after ANY partial match, which hid
        // this payload: it opens B5 03, not B5 00, so it is not the header.
        val notHeader = byteArrayOf(0xB5.toByte(), 0x03, 0x00, 0x3C, 0x00, 0x01, 0x04)
        assertFalse(
            "an escape byte cannot rescue a payload that does not open with the header",
            hdr10PlusSeiIsStripped(t35Sei(notHeader))
        )
    }

    // ── relabel construction hardening (the 2026-10-10 fallback) ──────────
    //
    // The relabel that re-advertises P4/P8 as plain HEVC is built in two steps:
    // the full relabel (declared codec on the label + HDR10 color info) first,
    // then a minimal codecs+mime-only relabel if that construction throws. The
    // field capture that motivated it had the full construction throw and fall
    // back to native DV, which black-screened with per-frame "Resolution change"
    // spam; the minimal attempt both recovers the working path and names the
    // failed step.

    private fun dvFormat(codecs: String) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs(codecs)
        .build()

    @Test
    fun `the full relabel builds for the P8 codecs and lands on video hevc`() {
        for (codecs in listOf("dvhe.08.06", "dvhe.08.10")) {
            val relabeled = relabelDvFormat(dvFormat(codecs), "hvc1.2.4.L153.B0")
            assertNotNull("the relabel must not throw for $codecs", relabeled)
            val r = relabeled!!
            assertEquals(RelabelAttempt.FULL, r.attempt)
            assertEquals(
                "the relabeled track must play as plain HEVC",
                MimeTypes.VIDEO_H265,
                r.format.sampleMimeType
            )
        }
    }

    @Test
    fun `the full relabel keeps the declared codec label and the HDR10 color info`() {
        val full = relabelDvFull(dvFormat("dvhe.08.06"), "hvc1.2.4.L153.B0")
        assertEquals("the declared DV codec stays on the label", "dvhe.08.06", full.label)
        assertEquals(
            "MTK needs the color info to emit a frame",
            DV_HDR10_COLOR_INFO,
            full.colorInfo
        )

        val minimal = relabelDvMinimal(dvFormat("dvhe.08.06"), "hvc1.2.4.L153.B0")
        assertNull("the minimal fallback ships without the badging label", minimal.label)
        assertNull("and without the color info", minimal.colorInfo)
        assertEquals(MimeTypes.VIDEO_H265, minimal.sampleMimeType)
    }

    @Test
    fun `a throwing color-info step falls back to the minimal relabel`() {
        val warnings = mutableListOf<String>()
        val relabeled = relabelDvFormat(
            dvFormat("dvhe.08.10"),
            "hvc1.2.4.L153.B0",
            applyColorInfo = { throw IllegalStateException("builder exploded") },
            warn = { message, _ -> warnings += message }
        )
        assertNotNull("the minimal fallback must still produce a format", relabeled)
        val r = relabeled!!
        assertEquals(RelabelAttempt.MINIMAL, r.attempt)
        assertEquals(MimeTypes.VIDEO_H265, r.format.sampleMimeType)
        assertTrue(
            "the failure log must name the step, the class AND the message",
            warnings.any {
                it.contains("full construction failed") &&
                    it.contains("IllegalStateException") &&
                    it.contains("builder exploded")
            }
        )
    }

    @Test
    fun `when both attempts throw the stream is played unchanged`() {
        val warnings = mutableListOf<String>()
        val relabeled = relabelDvFormat(
            dvFormat("dvhe.08.10"),
            "hvc1.2.4.L153.B0",
            applyColorInfo = { throw IllegalStateException("full exploded") },
            minimal = { _, _ -> throw IllegalStateException("minimal exploded") },
            warn = { message, _ -> warnings += message }
        )
        assertNull("nothing to ship when both relabels throw", relabeled)
        assertTrue(
            "the give-up log must carry the cause — a null message is not diagnosable",
            warnings.any {
                it.contains("minimal construction failed") &&
                    it.contains("playing the stream unchanged") &&
                    it.contains("minimal exploded")
            }
        )
    }

    /** A single Annex-B NAL unit: 4-byte start code, 2-byte HEVC header, payload. */
    private fun nal(type: Int, payload: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) +
        byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + payload
}
