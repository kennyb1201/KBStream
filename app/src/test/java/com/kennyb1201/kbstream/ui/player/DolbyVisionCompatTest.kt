package com.kennyb1201.kbstream.ui.player

import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
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
    fun `the DV strip drops the RPU but keeps the HDR10 plus SEI whole`() {
        // Nuvio's HevcDvRpuStripper drops only the RPU (62), the enhancement
        // layer (63) and layer-id > 0 NALs — a NAL 39 is never dropped for DV.
        // The 2026-10-14 field file had a 39:69 prefix-SEI dropped whole as
        // "HDR10+", and hdrStaticInfo came back absent: a prefix SEI can share
        // the HDR10+ message with the HDR10 static metadata (mastering display /
        // content light) the decoder needs once the RPU is gone. Here the SEI
        // rides through bit-exact; the RPU is the only thing that goes.
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

        assertTrue("the RPU must have been removed", newLen >= 0)
        // Everything but the RPU is carried through untouched, the SEI included.
        val expected = vps + sps + pps + sei + vcl
        assertArrayEquals(
            "the HDR10+ SEI, parameter sets and VCL must be byte-identical",
            expected,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `the DV strip records a kept all-HDR10-plus SEI on both framings`() {
        // The accounting half of the rule above: the field log has to say the
        // SEI was KEPT (not shrunk, not dropped) while the RPU is DROPPED, so
        // the "39:...->kept ... 62:...->dropped" line this pins is the one a
        // correct capture prints. An all-HDR10+ NAL is the shape Nuvio's
        // stripper leaves untouched.
        val sei = nal(39, t35Message(HDR10_PLUS_HEADER))
        val rpu = nal(62, ByteArray(16) { it.toByte() })
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + rpu + vcl
        val buf = au.copyOf()
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = true, stripHdr10Plus = true, accounting = accounting
        )

        assertTrue("the RPU is the only thing that goes", newLen >= 0)
        assertArrayEquals("the SEI rides bit-exact", sei + vcl, buf.copyOf(newLen))
        assertEquals(
            "the SEI is KEPT, the RPU DROPPED",
            listOf(
                DolbyVisionCompat.SeiStripAccounting.Action.KEPT,
                DolbyVisionCompat.SeiStripAccounting.Action.DROPPED
            ),
            accounting.entries.map { it.action }
        )
        assertTrue(
            "and the summary starts with the SEI kept at its own size",
            accounting.summary().startsWith("39:${sei.size - 4}->kept")
        )

        val sample = ldNal(39, t35Message(HDR10_PLUS_HEADER)) +
            ldNal(62, ByteArray(16) { it.toByte() }) + ldNal(19, byteArrayOf(0x55, 0x66))
        val sampleBuf = sample.copyOf()
        val ldAccounting = DolbyVisionCompat.SeiStripAccounting()
        val ldSei = ldNal(39, t35Message(HDR10_PLUS_HEADER))

        val ldLen = DolbyVisionCompat.stripLengthDelimited(
            sampleBuf, sample.size, 4, stripDv = true, stripHdr10Plus = true,
            accounting = ldAccounting
        )

        assertTrue("ld: the RPU is the only thing that goes", ldLen >= 0)
        assertArrayEquals(
            "ld: the SEI rides bit-exact",
            ldSei + ldNal(19, byteArrayOf(0x55, 0x66)),
            sampleBuf.copyOf(ldLen)
        )
        assertTrue(
            "ld: the summary starts with the SEI kept at its own size",
            ldAccounting.summary().startsWith("39:${ldSei.size - 4}->kept")
        )
    }

    // ── the enhancement layer is dropped by layer id, like Nuvio ──────────
    //
    // Nuvio's HevcDvRpuStripper drops every NAL with `layerId > 0` in addition
    // to types 62/63. The enhancement layer's own parameter sets and slices
    // carry a non-zero layer id and are not type 63, so the Kotlin parser,
    // which knew only 62/63, let them ride into an output now advertised as
    // single-layer HDR10. These pin the drop on both framings.

    /** Annex-B: 4-byte start code, a 2-byte HEVC header carrying [layerId], [payload]. */
    private fun nalOnLayer(type: Int, layerId: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1) +
            byteArrayOf(
                ((type shl 1) and 0x7E).toByte(),
                (((layerId and 0x1F) shl 3) or 0x01).toByte()
            ) + payload

    /** Length-delimited: a 4-byte length, a 2-byte HEVC header carrying [layerId], [payload]. */
    private fun ldNalOnLayer(type: Int, layerId: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(
            ((type shl 1) and 0x7E).toByte(),
            (((layerId and 0x1F) shl 3) or 0x01).toByte()
        ) + payload
        return byteArrayOf(
            ((body.size ushr 24) and 0xFF).toByte(),
            ((body.size ushr 16) and 0xFF).toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte()
        ) + body
    }

    @Test
    fun `stripAnnexB drops an enhancement-layer NAL by its layer id`() {
        val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
        val sps = nal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        // Layer 1 carries its own PPS (type 34) and slice (type 20); neither is
        // type 63, so only the layer-id rule removes them.
        val elPps = nalOnLayer(34, 1, byteArrayOf(0x0D, 0x0E, 0x0F))
        val elVcl = nalOnLayer(20, 1, byteArrayOf(0x55, 0x66))
        val baseVcl = nal(19, byteArrayOf(0x77, 0x88.toByte()))

        val au = vps + sps + elPps + rpu + elVcl + baseVcl
        val buf = au.copyOf()
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = true, stripHdr10Plus = false
        )

        assertTrue("the enhancement-layer NALs must have been removed", newLen >= 0)
        assertArrayEquals("only the base layer remains", vps + sps + baseVcl, buf.copyOf(newLen))
    }

    @Test
    fun `stripLengthDelimited drops an enhancement-layer NAL by its layer id`() {
        val vps = ldNal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
        val sps = ldNal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val rpu = ldNal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val elVcl = ldNalOnLayer(20, 1, byteArrayOf(0x55, 0x66))
        val baseVcl = ldNal(19, byteArrayOf(0x77, 0x88.toByte()))

        val sample = vps + sps + rpu + elVcl + baseVcl
        val buf = sample.copyOf()
        val newLen = DolbyVisionCompat.stripLengthDelimited(
            buf, sample.size, 4, stripDv = true, stripHdr10Plus = false
        )

        assertTrue("the enhancement-layer NAL must have been removed", newLen >= 0)
        assertArrayEquals("only the base layer remains", vps + sps + baseVcl, buf.copyOf(newLen))
    }

    /**
     * The keep-RPU shape of the strip — `stripAnnexB(stripDv = false,
     * stripHdr10Plus = true)`: only the HDR10+ SEI goes and the DV RPU rides
     * through. The relabel no longer calls it this way (it drops the RPU too,
     * see DolbyVisionRelabelNalsStripContractTest), but the shape is still what
     * the `dropRpuNal` arms and any caller that wants the SEI gone while the
     * RPU stays build on, so it stays pinned at the function level.
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

    // ── The RPU-drop arm and the relabel's color hook (permanent APIs) ────
    //
    // `dropRpuNal` is the Strip All button's future reference-config arm: the
    // RPU (type 62) goes while the HDR10+ SEI stays — the config the reference
    // player's "Always Strip DV" plays on MTK-class hardware. Default false, so every existing
    // caller's bytes are unchanged. The relabel's `applyColorInfo` hook defaults
    // to the forced DV_HDR10_COLOR_INFO, with the container's own hdrStaticInfo
    // carried onto it when the container declared one (see dvHdr10ColorInfoFor);
    // the identity form is what a caller can use to ship whatever the container
    // declared. These pin all three.

    @Test
    fun `dropRpuNal drops type 62 and forwards the HDR10+ SEI bit-exact`() {
        val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
        val sps = nal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))
        val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val el = nal(63, byteArrayOf(0x0A, 0x0B, 0x0C))
        val sei = nal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
        val vcl = nal(19, byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte()))

        val au = vps + sps + pps + rpu + el + sei + vcl
        val buf = au.copyOf()
        // The RPU-drop arm's call shape.
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = false, dropRpuNal = true
        )

        assertTrue("the RPU must have been removed", newLen >= 0)
        // Only type 62 goes: the EL (63), the parameter sets, the VCL and the
        // HDR10+ SEI all ride through bit-exact.
        val expected = vps + sps + pps + el + sei + vcl
        assertArrayEquals(
            "dropRpuNal must drop the RPU and keep the HDR10+ SEI",
            expected,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `the RPU flag still drops only type 62 when the SEI strip runs too`() {
        // The pin the earlier arms left behind: with the HDR10+ strip on as
        // well, dropRpuNal removes the RPU alone — never the EL (63), never a
        // parameter set.
        val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
        val sps = nal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))
        val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val el = nal(63, byteArrayOf(0x0A, 0x0B, 0x0C))
        val sei = nal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
        val vcl = nal(19, byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte()))

        val au = vps + sps + pps + rpu + el + sei + vcl
        val buf = au.copyOf()
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true, dropRpuNal = true
        )

        assertTrue("the RPU and the HDR10+ SEI must have been removed", newLen >= 0)
        val expected = vps + sps + pps + el + vcl
        assertArrayEquals(
            "dropRpuNal must drop the RPU and not the EL",
            expected,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `the RPU-drop arm is off by default, so the strip output is unchanged`() {
        val rpu = nal(62, byteArrayOf(0x11, 0x22))
        val sei = nal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = rpu + sei + vcl

        val implicit = au.copyOf()
        val implicitLen = DolbyVisionCompat.stripAnnexB(
            implicit, au.size, stripDv = false, stripHdr10Plus = true
        )
        val explicit = au.copyOf()
        val explicitLen = DolbyVisionCompat.stripAnnexB(
            explicit, au.size, stripDv = false, stripHdr10Plus = true, dropRpuNal = false
        )

        assertEquals("omitting the arm must mean the same as false", implicitLen, explicitLen)
        assertArrayEquals(
            "the default must be byte-identical to the shipping strip",
            implicit.copyOf(implicitLen),
            explicit.copyOf(explicitLen)
        )
        // The RPU is what stays: only the HDR10+ SEI went.
        assertEquals("the RPU must survive with the arm off", (rpu + vcl).size, implicitLen)
    }

    @Test
    fun `dropRpuNal drops type 62 and keeps the SEI on length-delimited samples too`() {
        val rpu = ldNal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val el = ldNal(63, byteArrayOf(0x0A, 0x0B))
        val sei = ldNal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
        val vcl = ldNal(19, byteArrayOf(0x55, 0x66))
        val sample = rpu + el + sei + vcl
        val buf = sample.copyOf()

        val newLen = DolbyVisionCompat.stripLengthDelimited(
            buf, sample.size, 4, stripDv = false, stripHdr10Plus = false, dropRpuNal = true
        )

        assertTrue("the RPU must have been removed", newLen >= 0)
        assertArrayEquals(
            "the EL and the HDR10+ SEI must survive in an MP4 sample as well",
            el + sei + vcl,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `the identity color-info lambda leaves the format's own color info alone`() {
        // The relabel's color-info hook must be able to leave whatever the
        // extractor produced - including NOTHING - and must not throw, so the
        // full->minimal fallback this file already guards is untouched.
        val source = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.06")
            .build()
        val outcome = relabelDvFormat(source, "hvc1.2.4.L153.B0", applyColorInfo = { it })

        assertNotNull("the identity step must still produce a relabel", outcome)
        val r = outcome!!
        assertEquals(
            "the identity step cannot fall back to the minimal relabel",
            RelabelAttempt.FULL,
            r.attempt
        )
        assertNull(
            "the identity hook ships the relabeled format without attached color info",
            r.format.colorInfo
        )
        assertEquals(MimeTypes.VIDEO_H265, r.format.sampleMimeType)
    }

    @Test
    fun `the identity color hook ships the container color and the default forces HDR10`() {
        // The relabel's color hook, behaviorally. With the identity step a
        // container that DID declare a color info reaches the decoder untouched.
        // The default replaces it with the forced HDR10 values — and, since the
        // container below declares no hdrStaticInfo, with nothing else: the
        // static-metadata case is its own test, right after this one.
        val containerColor = ColorInfo.Builder()
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_FULL)
            .build()
        val container = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.06")
            .setColorInfo(containerColor)
            .build()

        val identity = relabelDvFormat(
            container, "hvc1.2.4.L153.B0", applyColorInfo = { it }
        )
        assertNotNull("the identity step must still produce a relabel", identity)
        assertEquals(
            "the identity hook must hand the container's own color info to the decoder",
            containerColor,
            identity!!.format.colorInfo
        )

        val shipping = relabelDvFormat(container, "hvc1.2.4.L153.B0")
        assertNotNull(shipping)
        assertEquals(
            "in the default shape the forced HDR10 color info replaces the container's",
            DV_HDR10_COLOR_INFO,
            shipping!!.format.colorInfo
        )
    }

    @Test
    fun `the default relabel carries the container's hdrStaticInfo onto the forced HDR10 color info`() {
        // The 2026-10-11 field case: RPU dropped, the SPS-derived
        // hvc1.2.4.H150.B0 and the HDR10+ SEI stripped still stalled with zero
        // decoder output, and the container's HDR10 static metadata (ST 2086
        // mastering display colour volume / content light level) was the
        // remaining difference against the reference config. The relabel
        // REPLACES the whole color info, so the static metadata has to be
        // re-attached explicitly — under the forced transfer/space/range/
        // bitdepth, never the container's own.
        val static = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18
        )
        val containerColor = ColorInfo.Builder()
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setHdrStaticInfo(static)
            .build()
        val container = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.06")
            .setColorInfo(containerColor)
            .build()

        val relabeled = relabelDvFormat(container, "hvc1.2.4.H150.B0")
        assertNotNull("the default relabel must still build", relabeled)
        val color = relabeled!!.format.colorInfo
        assertNotNull("MTK needs the color info to emit a frame", color)
        assertArrayEquals(
            "the container's HDR10 static metadata must reach the decoder",
            static,
            color!!.hdrStaticInfo
        )
        assertEquals(
            "the transfer stays the forced ST.2084, not the container's HLG",
            C.COLOR_TRANSFER_ST2084,
            color.colorTransfer
        )
        assertEquals("the space stays the forced BT.2020", C.COLOR_SPACE_BT2020, color.colorSpace)
        assertEquals("the range stays the forced limited range", C.COLOR_RANGE_LIMITED, color.colorRange)
        assertEquals("and the bitdepths stay 10-bit", 10, color.lumaBitdepth)
        assertEquals(10, color.chromaBitdepth)

        // `relabelDvFull` — the other entry point — takes the same step.
        val full = relabelDvFull(container, "hvc1.2.4.H150.B0")
        assertArrayEquals(
            "the full relabel must carry the static metadata too",
            static,
            full.colorInfo!!.hdrStaticInfo
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

    // ── Surgical removal: only the HDR10+ MESSAGE goes ───────────────
    //
    // Field 2026-10-10 (AFTKRT, MULTi dvhe.08.06): the 39:69 prefix-SEI NAL was
    // dropped whole as "HDR10+" and playback stalled with the RPU-removal
    // signature. The NAL carried more than the HDR10+ message — data the
    // decoder needs alongside it — so the granularity of removal was wrong, not
    // the detector. Only a matching SEI message may go; the NAL and every other
    // message in it must survive, and a NAL with no HDR10+ message must be
    // forwarded bit-exact.

    /** A user-data-registered-ITU-T-T35 (payloadType 4) SEI message carrying [payload]. */
    private fun t35Message(payload: ByteArray): ByteArray =
        byteArrayOf(0x04, payload.size.toByte()) + payload

    /** A user_data_unregistered (payloadType 5) SEI message carrying [payload]. */
    private fun unregisteredMessage(payload: ByteArray): ByteArray =
        byteArrayOf(0x05, payload.size.toByte()) + payload

    /** A length-delimited (MP4/fMP4) NAL with a 4-byte length prefix. */
    private fun ldNal(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + payload
        val len = body.size
        return byteArrayOf(
            ((len ushr 24) and 0xFF).toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        ) + body
    }

    @Test
    fun `a shared SEI NAL keeps its other message and drops only the HDR10 plus one`() {
        val hdr10 = t35Message(HDR10_PLUS_HEADER)
        val other = unregisteredMessage(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val sei = nal(39, hdr10 + other + byteArrayOf(0x80.toByte()))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val buf = au.copyOf()

        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true
        )

        assertTrue("the HDR10+ message must have been removed", newLen >= 0)
        // The NAL survives, shrunk to the kept message plus its trailing bits.
        val expectedSei = nal(39, other + byteArrayOf(0x80.toByte()))
        assertArrayEquals(
            "the NAL and the non-HDR10+ message must be byte-identical",
            expectedSei + vcl,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `the Strip All surgery keeps a shared SEI NAL's HDR10 static metadata`() {
        // The 2026-10-14 green screen: libdovi's HDR10+ strip is coarse and took
        // the whole SEI NAL, including the HDR10 static metadata (a mastering
        // display message, payload 137) it shared with the HDR10+ message. The
        // Strip All call shape now runs the Kotlin surgery (`kotlinOnly`), which
        // removes only the HDR10+ message and keeps the NAL — Nuvio never strips
        // this NAL at all.
        val hdr10Plus = t35Message(HDR10_PLUS_HEADER)
        val static = byteArrayOf(0x89.toByte(), 0x03) + byteArrayOf(0x01, 0x02, 0x03)
        val sei = nal(39, hdr10Plus + static + byteArrayOf(0x80.toByte()))
        val vps = nal(32, byteArrayOf(0x0C, 0x01))
        val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val el = nalOnLayer(20, 1, byteArrayOf(0x0A, 0x0B))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))

        val au = vps + rpu + el + sei + vcl
        val buf = au.copyOf()
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = true, stripHdr10Plus = true, kotlinOnly = true
        )

        assertTrue("the RPU and the enhancement layer must have been removed", newLen >= 0)
        // The SEI NAL survives, shrunk to the static-metadata message plus its
        // trailing bits; the RPU and the enhancement layer are gone.
        val expectedSei = nal(39, static + byteArrayOf(0x80.toByte()))
        assertArrayEquals(
            "static metadata preserved, RPU/EL dropped",
            vps + expectedSei + vcl,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `a SEI with no HDR10 plus message passes through bit-exact`() {
        val sei = nal(
            39,
            unregisteredMessage(byteArrayOf(0x11, 0x22)) + byteArrayOf(0x80.toByte())
        )
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val buf = au.copyOf()

        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true
        )

        assertEquals("nothing to strip, so nothing may change", -1, newLen)
        assertArrayEquals("the sample must be untouched", au, buf)
    }

    @Test
    fun `a malformed SEI is forwarded untouched`() {
        // A payload type with no size byte (truncated varint) and a size that
        // overruns the NAL payload must both fail open: a parse quirk may never
        // delete or rewrite a NAL.
        val truncated = nal(39, byteArrayOf(0x04))
        val overrun = nal(39, byteArrayOf(0x04, 0x10, 0x00))
        listOf(truncated, overrun).forEachIndexed { i, sei ->
            val vcl = nal(19, byteArrayOf(0x55, 0x66))
            val au = sei + vcl
            val buf = au.copyOf()
            val newLen = DolbyVisionCompat.stripAnnexB(
                buf, au.size, stripDv = false, stripHdr10Plus = true
            )
            assertEquals("case $i must be untouched", -1, newLen)
            assertArrayEquals("case $i must be bit-exact", au, buf)
        }
    }

    @Test
    fun `the length-delimited path strips only the HDR10 plus message`() {
        val hdr10 = t35Message(HDR10_PLUS_HEADER)
        val other = unregisteredMessage(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val sei = ldNal(39, hdr10 + other + byteArrayOf(0x80.toByte()))
        val vcl = ldNal(19, byteArrayOf(0x55, 0x66))
        val sample = sei + vcl
        val buf = sample.copyOf()

        val newLen = DolbyVisionCompat.stripLengthDelimited(
            buf, sample.size, 4, stripDv = false, stripHdr10Plus = true
        )

        assertTrue("the HDR10+ message must have been removed", newLen >= 0)
        val expected = ldNal(39, other + byteArrayOf(0x80.toByte())) + vcl
        assertArrayEquals(
            "the NAL and the non-HDR10+ message must be byte-identical",
            expected,
            buf.copyOf(newLen)
        )
    }

    @Test
    fun `the length-delimited path drops a NAL whose only message is HDR10 plus`() {
        val sei = ldNal(39, t35Message(HDR10_PLUS_HEADER))
        val vcl = ldNal(19, byteArrayOf(0x55, 0x66))
        val sample = sei + vcl
        val buf = sample.copyOf()

        val newLen = DolbyVisionCompat.stripLengthDelimited(
            buf, sample.size, 4, stripDv = false, stripHdr10Plus = true
        )

        assertEquals("the whole SEI NAL must go", vcl.size, newLen)
        assertArrayEquals("only the VCL NAL remains", vcl, buf.copyOf(newLen))
    }

    // ── The first-sample SEI accounting (diagnostic) ────────────────
    //
    // The relabel path logged "first sample HDR10+ SEI dropped" whether or not
    // anything was removed, so a field stall could not be read off it: the 39:69
    // prefix-SEI NAL that sits next to the 216-byte RPU may have been dropped
    // whole, surgically shrunk, or forwarded bit-exact, and the log said the
    // same thing all three ways. The accounting records which - per SEI NAL,
    // with its size before and, for a shrink, after - and it is DIAGNOSTIC
    // ONLY: the bytes emitted for a given input are identical with or without
    // it, which the last test below pins directly.

    @Test
    fun `the accounting records a whole-SEI drop, with the NAL's own size`() {
        val sei = nal(39, t35Message(HDR10_PLUS_HEADER))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val buf = au.copyOf()
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true, accounting = accounting
        )

        assertEquals("a NAL whose only message is HDR10+ goes whole", vcl.size, newLen)
        val entry = accounting.entries.single()
        assertEquals(39, entry.nalType)
        // NAL bytes excluding the start code, the size describeNals prints.
        assertEquals(sei.size - 4, entry.originalSize)
        assertEquals(DolbyVisionCompat.SeiStripAccounting.Action.DROPPED, entry.action)
        assertEquals("39:${sei.size - 4}->dropped", accounting.summary())
    }

    @Test
    fun `the accounting records the RPU and EL the strip dropped`() {
        // The no-relabel arm's whole evidence is this line: the sample's own
        // declared dvhe.08.06 stays because nothing relabels it, and the RPU is
        // gone because the strip removed it. `modified=true` alone would not
        // say which of the two NALs went, so the RPU/EL are recorded exactly
        // like the SEI drops are - across BOTH framings, because the strip has
        // two call sites and only one of them used to record anything.
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val rpu = nal(62, ByteArray(16) { it.toByte() })
        val el = nal(63, ByteArray(8) { it.toByte() })
        val au = rpu + el + vcl
        val buf = au.copyOf()
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = true, stripHdr10Plus = true, accounting = accounting
        )

        assertEquals("only the VCL NAL survives", vcl.size, newLen)
        assertEquals(
            "both dropped NAL types, at the size describeNals prints",
            "62:18->dropped,63:10->dropped",
            accounting.summary()
        )

        val sample = ldNal(62, ByteArray(16) { it.toByte() }) +
            ldNal(63, ByteArray(8) { it.toByte() }) +
            ldNal(19, byteArrayOf(0x55, 0x66))
        val sampleBuf = sample.copyOf()
        val ldAccounting = DolbyVisionCompat.SeiStripAccounting()

        DolbyVisionCompat.stripLengthDelimited(
            sampleBuf, sample.size, 4, stripDv = true, stripHdr10Plus = true,
            accounting = ldAccounting
        )

        assertEquals("62:18->dropped,63:10->dropped", ldAccounting.summary())
    }

    @Test
    fun `the accounting records a shrunk SEI at the size it was left at`() {
        val other = unregisteredMessage(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val sei = nal(39, t35Message(HDR10_PLUS_HEADER) + other + byteArrayOf(0x80.toByte()))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val buf = au.copyOf()
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true, accounting = accounting
        )

        val kept = nal(39, other + byteArrayOf(0x80.toByte()))
        assertEquals("the NAL survives as the kept message", kept.size + vcl.size, newLen)
        val entry = accounting.entries.single()
        assertEquals(39, entry.nalType)
        assertEquals(sei.size - 4, entry.originalSize)
        assertEquals(DolbyVisionCompat.SeiStripAccounting.Action.SHRUNK, entry.action)
        // The recorded new size is the NAL's real new size (its framing
        // included), so the log can be read against what the decoder is handed.
        assertEquals(kept.size, entry.newSize)
        assertEquals("39:${sei.size - 4}->shrunk=${kept.size}", accounting.summary())
    }

    @Test
    fun `the accounting records a kept SEI, both ways the strip can leave one alone`() {
        // Two ways a SEI NAL is forwarded bit-exact, and the first-sample log
        // has to be able to tell them from a drop: the detector never fired (an
        // RPU-shaped T35 payload, the marker bytes only mid-payload), and the
        // detector fired but the surgical parse failed open (a matching message
        // followed by a malformed one). Neither removes anything. Both are also
        // exactly what the 14:04 picture build's subsequence detector WOULD have
        // dropped, so each carries the legacy-drop flag.
        val rpuShaped = ByteArray(10) { (0x10 + it).toByte() } + HDR10_PLUS_HEADER
        val cases = listOf(
            "the detector did not fire" to t35Message(rpuShaped),
            "the surgical parse failed open" to
                t35Message(HDR10_PLUS_HEADER) + byteArrayOf(0x04)
        )
        cases.forEach { (name, payload) ->
            val sei = nal(39, payload)
            val vcl = nal(19, byteArrayOf(0x55, 0x66))
            val au = sei + vcl
            val buf = au.copyOf()
            val accounting = DolbyVisionCompat.SeiStripAccounting()

            val newLen = DolbyVisionCompat.stripAnnexB(
                buf, au.size, stripDv = false, stripHdr10Plus = true,
                accounting = accounting
            )

            assertEquals("$name: nothing may be removed", -1, newLen)
            assertArrayEquals("$name: the sample must be bit-exact", au, buf)
            assertEquals(
                "$name: the NAL is recorded as kept, at its own size, flagged as a legacy drop",
                "39:${sei.size - 4}->kept,legacyDrop",
                accounting.summary()
            )
            assertTrue(
                "$name: the flag is what the field replay reads off the log line",
                accounting.entries.single().legacyDrop
            )
        }
    }

    @Test
    fun `only a kept SEI the legacy detector would have dropped carries the flag`() {
        // The 14:04 picture build's subsequence detector dropped the marker
        // wherever it appeared in the T35 payload. The prefix matcher keeps an
        // RPU-shaped payload whose marker sits mid-payload - and that kept NAL
        // is exactly the behavior delta the field replay is looking for, so the
        // accounting has to say so. A payload no detector would drop is kept
        // with no flag: absence is the answer, not a default-true.
        val rpuShaped = ByteArray(10) { (0x10 + it).toByte() } + HDR10_PLUS_HEADER
        val cases = listOf(
            Triple(
                "a kept NAL the subsequence detector would have dropped",
                t35Message(rpuShaped),
                true
            ),
            Triple(
                "a kept NAL no detector would drop",
                unregisteredMessage(byteArrayOf(0x11, 0x22)) + byteArrayOf(0x80.toByte()),
                false
            )
        )
        cases.forEach { (name, payload, flagged) ->
            val sei = nal(39, payload)
            val vcl = nal(19, byteArrayOf(0x55, 0x66))
            val au = sei + vcl
            val buf = au.copyOf()
            val accounting = DolbyVisionCompat.SeiStripAccounting()

            val newLen = DolbyVisionCompat.stripAnnexB(
                buf, au.size, stripDv = false, stripHdr10Plus = true,
                accounting = accounting
            )

            assertEquals("$name: nothing may be removed", -1, newLen)
            val entry = accounting.entries.single()
            assertEquals(
                "$name: the NAL is kept",
                DolbyVisionCompat.SeiStripAccounting.Action.KEPT,
                entry.action
            )
            assertEquals(
                "$name: the flag mirrors the 14:04 detector's verdict",
                flagged,
                entry.legacyDrop
            )
            assertEquals(
                "$name: the summary renders the flag only when it is set",
                "39:${sei.size - 4}->kept" + if (flagged) ",legacyDrop" else "",
                accounting.summary()
            )
        }
    }

    @Test
    fun `a whole-NAL drop carries no legacy flag, because the flag is only on KEPT`() {
        // A prefix match is also a subsequence match, so a dropped or shrunk NAL
        // is trivially "would have been dropped" by the legacy detector too. It
        // is not a delta between the two builds, so the flag is not recorded on
        // it - only on the KEPT NALs the prefix matcher rescues.
        val sei = nal(39, t35Message(HDR10_PLUS_HEADER))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val buf = au.copyOf()
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = false, stripHdr10Plus = true, accounting = accounting
        )

        val entry = accounting.entries.single()
        assertEquals(
            DolbyVisionCompat.SeiStripAccounting.Action.DROPPED,
            entry.action
        )
        assertFalse("a drop is not flagged", entry.legacyDrop)
        assertEquals("39:${sei.size - 4}->dropped", accounting.summary())
    }

    @Test
    fun `the summary renders a legacy-flagged kept NAL among a mixed entry list`() {
        val accounting = DolbyVisionCompat.SeiStripAccounting()
        accounting.record(39, 69, DolbyVisionCompat.SeiStripAccounting.Action.DROPPED)
        accounting.record(39, 30, DolbyVisionCompat.SeiStripAccounting.Action.KEPT)
        accounting.record(
            39, 30, DolbyVisionCompat.SeiStripAccounting.Action.KEPT, legacyDrop = true
        )
        accounting.record(40, 35, DolbyVisionCompat.SeiStripAccounting.Action.KEPT)
        accounting.record(
            39, 69, DolbyVisionCompat.SeiStripAccounting.Action.SHRUNK, newSize = 22
        )
        assertEquals(
            "39:69->dropped,39:30->kept,39:30->kept,legacyDrop,40:35->kept,39:69->shrunk=22",
            accounting.summary()
        )
    }

    @Test
    fun `the accounting changes nothing about the bytes emitted`() {
        // The diagnostic's whole contract: strip the same input twice, once with
        // and once without it, and the emitted bytes and the return value must be
        // equal - across every shape the strip can meet, because the record calls
        // sit right beside the in-place compaction. Every shape here runs with
        // stripDv = false, so the RPU NAL rides through untouched and is not
        // recorded; a stripDv = true call records the RPU/EL it drops (see
        // `the accounting records the RPU and EL the strip dropped`).
        val hdr10 = t35Message(HDR10_PLUS_HEADER)
        val other = unregisteredMessage(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val rpuShaped = ByteArray(10) { (0x10 + it).toByte() } + HDR10_PLUS_HEADER
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val rpu = nal(62, ByteArray(16) { it.toByte() })
        val annexB = listOf(
            "whole drop" to nal(39, hdr10) + vcl,
            "shrink" to nal(39, hdr10 + other + byteArrayOf(0x80.toByte())) + vcl,
            "detector silent" to nal(39, t35Message(rpuShaped)) + vcl,
            "fail open" to nal(39, hdr10 + byteArrayOf(0x04)) + vcl,
            "nothing to strip" to nal(39, other + byteArrayOf(0x80.toByte())) + vcl,
            "rpu kept" to rpu + vcl,
            "everything at once" to nal(39, hdr10 + other + byteArrayOf(0x80.toByte())) +
                nal(39, hdr10) + rpu + vcl
        )
        annexB.forEach { (name, au) ->
            val plain = au.copyOf()
            val counted = au.copyOf()
            val accounting = DolbyVisionCompat.SeiStripAccounting()

            val plainLen = DolbyVisionCompat.stripAnnexB(
                plain, au.size, stripDv = false, stripHdr10Plus = true
            )
            val countedLen = DolbyVisionCompat.stripAnnexB(
                counted, au.size, stripDv = false, stripHdr10Plus = true,
                accounting = accounting
            )

            assertEquals(
                "$name: the return value must not depend on the accounting",
                plainLen,
                countedLen
            )
            assertArrayEquals(
                "$name: the bytes emitted must be identical",
                if (plainLen < 0) plain else plain.copyOf(plainLen),
                if (countedLen < 0) counted else counted.copyOf(countedLen)
            )
            assertTrue(
                "$name: a non-SEI NAL is never recorded - the RPU is not a SEI",
                accounting.entries.all { it.nalType == 39 || it.nalType == 40 }
            )
        }
        // And the order is the order the NALs were met in, which is what lets
        // the log be read against the `nals=` inventory on the same line.
        val mixed = annexB.single { it.first == "everything at once" }.second.copyOf()
        val actions = DolbyVisionCompat.SeiStripAccounting()
        DolbyVisionCompat.stripAnnexB(
            mixed, mixed.size, stripDv = false, stripHdr10Plus = true, accounting = actions
        )
        assertEquals(
            "a shrunk NAL, then the one that goes whole, and nothing about the RPU",
            listOf(39, 39),
            actions.entries.map { it.nalType }
        )
        assertEquals(
            listOf(
                DolbyVisionCompat.SeiStripAccounting.Action.SHRUNK,
                DolbyVisionCompat.SeiStripAccounting.Action.DROPPED
            ),
            actions.entries.map { it.action }
        )

        val lengthDelimited = listOf(
            "whole drop" to ldNal(39, hdr10) + ldNal(19, byteArrayOf(0x55, 0x66)),
            "shrink" to
                ldNal(39, hdr10 + other + byteArrayOf(0x80.toByte())) + ldNal(19, byteArrayOf(0x55, 0x66)),
            "detector silent" to ldNal(39, t35Message(rpuShaped)) + ldNal(19, byteArrayOf(0x55, 0x66)),
            "nothing to strip" to ldNal(39, other + byteArrayOf(0x80.toByte())) + ldNal(19, byteArrayOf(0x55, 0x66)),
            "rpu kept" to ldNal(62, ByteArray(16) { it.toByte() }) + ldNal(19, byteArrayOf(0x55, 0x66))
        )
        lengthDelimited.forEach { (name, sample) ->
            val plain = sample.copyOf()
            val counted = sample.copyOf()
            val accounting = DolbyVisionCompat.SeiStripAccounting()

            val plainLen = DolbyVisionCompat.stripLengthDelimited(
                plain, sample.size, 4, stripDv = false, stripHdr10Plus = true
            )
            val countedLen = DolbyVisionCompat.stripLengthDelimited(
                counted, sample.size, 4, stripDv = false, stripHdr10Plus = true,
                accounting = accounting
            )

            assertEquals(
                "ld $name: the return value must not depend on the accounting",
                plainLen,
                countedLen
            )
            assertArrayEquals(
                "ld $name: the bytes emitted must be identical",
                if (plainLen < 0) plain else plain.copyOf(plainLen),
                if (countedLen < 0) counted else counted.copyOf(countedLen)
            )
            assertTrue(
                "ld $name: a non-SEI NAL is never recorded",
                accounting.entries.all { it.nalType == 39 || it.nalType == 40 }
            )
        }
    }

    @Test
    fun `a seeded sweep of SEI-shaped samples strips identically with and without accounting`() {
        // The with/without equality above, pushed past the hand-written shapes:
        // the record calls sit inside the compaction branches, so the guard has
        // to hold for inputs nobody thought of - truncated messages, 0xFF
        // varints, emulation-prevention bytes, a header that opens the payload
        // and one that only appears inside it. Seeded, so a failure repeats.
        val random = kotlin.random.Random(20261011)
        val alphabet = byteArrayOf(
            0x04, 0x05, 0xB5.toByte(), 0x00, 0x3C, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x03
        )
        val vclAnnexB = nal(19, byteArrayOf(0x55, 0x66))
        val rpuAnnexB = nal(62, ByteArray(8) { it.toByte() })
        val vclLd = ldNal(19, byteArrayOf(0x55, 0x66))
        val rpuLd = ldNal(62, ByteArray(8) { it.toByte() })
        val seen = mutableSetOf<DolbyVisionCompat.SeiStripAccounting.Action>()

        repeat(200) { index ->
            val payload = ByteArray(random.nextInt(40)) { alphabet[random.nextInt(alphabet.size)] }
            // The detector fires on the cases whose T35 message opens with the
            // real header, so the surgical parse is the path under test; the
            // others are the fail-open shapes. Prefix and suffix SEI alike.
            val sei = when (index % 4) {
                0 -> nal(39, t35Message(HDR10_PLUS_HEADER + payload))
                1 -> nal(40, t35Message(HDR10_PLUS_HEADER + payload) + unregisteredMessage(payload))
                2 -> nal(39, payload)
                else -> nal(40, t35Message(payload))
            }
            val au = sei + vclAnnexB + rpuAnnexB
            val plain = au.copyOf()
            val counted = au.copyOf()
            val accounting = DolbyVisionCompat.SeiStripAccounting()

            val plainLen = DolbyVisionCompat.stripAnnexB(
                plain, au.size, stripDv = false, stripHdr10Plus = true
            )
            val countedLen = DolbyVisionCompat.stripAnnexB(
                counted, au.size, stripDv = false, stripHdr10Plus = true,
                accounting = accounting
            )

            assertEquals("case $index: return value", plainLen, countedLen)
            assertArrayEquals(
                "case $index: emitted bytes",
                if (plainLen < 0) plain else plain.copyOf(plainLen),
                if (countedLen < 0) counted else counted.copyOf(countedLen)
            )
            assertTrue(
                "case $index: only SEI NALs are ever recorded",
                accounting.entries.all { it.nalType == 39 || it.nalType == 40 }
            )

            val sample = ldNal(
                39,
                t35Message(HDR10_PLUS_HEADER + payload) + unregisteredMessage(payload)
            ) + vclLd + rpuLd
            val ldPlain = sample.copyOf()
            val ldCounted = sample.copyOf()
            val ldAccounting = DolbyVisionCompat.SeiStripAccounting()
            val ldPlainLen = DolbyVisionCompat.stripLengthDelimited(
                ldPlain, sample.size, 4, stripDv = false, stripHdr10Plus = true
            )
            val ldCountedLen = DolbyVisionCompat.stripLengthDelimited(
                ldCounted, sample.size, 4, stripDv = false, stripHdr10Plus = true,
                accounting = ldAccounting
            )

            assertEquals("case $index: ld return value", ldPlainLen, ldCountedLen)
            assertArrayEquals(
                "case $index: ld emitted bytes",
                if (ldPlainLen < 0) ldPlain else ldPlain.copyOf(ldPlainLen),
                if (ldCountedLen < 0) ldCounted else ldCounted.copyOf(ldCountedLen)
            )
            assertTrue(
                "case $index: ld records only SEI NALs",
                ldAccounting.entries.all { it.nalType == 39 || it.nalType == 40 }
            )
            seen += accounting.entries.map { it.action }
            seen += ldAccounting.entries.map { it.action }
        }

        assertEquals(
            "the sweep has to have met all three fates, or it is not a guard on any of them",
            setOf(
                DolbyVisionCompat.SeiStripAccounting.Action.KEPT,
                DolbyVisionCompat.SeiStripAccounting.Action.SHRUNK,
                DolbyVisionCompat.SeiStripAccounting.Action.DROPPED
            ),
            seen
        )
    }

    @Test
    fun `the length-delimited strip accounts for its SEI NALs the same way`() {
        val sei = ldNal(39, t35Message(HDR10_PLUS_HEADER))
        val vcl = ldNal(19, byteArrayOf(0x55, 0x66))
        val sample = sei + vcl
        val buf = sample.copyOf()
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripLengthDelimited(
            buf, sample.size, 4, stripDv = false, stripHdr10Plus = true, accounting = accounting
        )

        assertEquals("the whole SEI NAL goes", vcl.size, newLen)
        val entry = accounting.entries.single()
        assertEquals(39, entry.nalType)
        // NAL bytes excluding the 4-byte length field.
        assertEquals(sei.size - 4, entry.originalSize)
        assertEquals(DolbyVisionCompat.SeiStripAccounting.Action.DROPPED, entry.action)
        assertEquals("39:${sei.size - 4}->dropped", accounting.summary())
    }

    @Test
    fun `the mutating strip keeps a shared SEI NAL and removes only the HDR10 plus message`() {
        // The Strip All / no-DV-decoder path has the same bug and must go
        // through the same surgical removal, not drop the whole NAL.
        val hdr10 = t35Message(HDR10_PLUS_HEADER)
        val other = unregisteredMessage(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val sei = nal(39, hdr10 + other + byteArrayOf(0x80.toByte()))
        val vcl = nal(19, byteArrayOf(0x55, 0x66))
        val au = sei + vcl
        val buf = au.copyOf()

        val newLen = DolbyVisionCompat.transformAnnexB(
            buf, au.size, stripHdr10Plus = true, stats = null
        )

        assertTrue("the HDR10+ message must have been removed", newLen >= 0)
        val expected = nal(39, other + byteArrayOf(0x80.toByte())) + vcl
        assertArrayEquals(
            "the NAL and the non-HDR10+ message must be byte-identical",
            expected,
            buf.copyOf(newLen)
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
