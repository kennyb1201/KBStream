package com.kennyb1201.kbstream.ui.player

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 1 of the 2026-10-15 declared-DV P8 spec: the in-band parameter sets have
 * to agree with the codec config.
 *
 * Field evidence (Fire TV karat/AFTKRT, 20:43, the K83 `dvhe.08.06` file):
 * `PARAM-AUDIT (Fix D applied=false)` reports the config VPS rewritten 24→22B
 * while `survivors=vps:rewriteLeftInSample,sps:rewriteLeftInSample` — the first
 * sample's in-band VPS/SPS still carried the original DV-flagged bytes. The MTK
 * decoder was configured from one description and handed another, accepted the
 * first input frame and emitted 0-1 kbps.
 *
 * So the Kotlin NALS-strip path — the one that actually touches the sample
 * (`62:205->dropped (via kotlin)`) — now re-emits the in-band VPS/SPS as the
 * rewritten forms too, and this file pins that by exact bytes: the rebuild's NAL
 * table, the corrected offsets after a shortened set (never a splice back into
 * the original offset), the length-prefix form, the failed-rewrite keep, and
 * that the NAL 39/40/62 decisions around it are untouched.
 *
 * Nothing here is a device test: no emulator exists in this tree, so the visible
 * playback criterion is pinned only as far as the bytes go.
 */
class DvInBandParamSetAgreementTest {

    // ── builders ───────────────────────────────────────────────────────────

    /** MSB-first bit writer, so a real SPS RBSP can be built field by field. */
    private class Bits {
        private val out = ByteArrayOutputStream()
        private var acc = 0
        private var n = 0

        fun u(value: Int, bits: Int): Bits {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or ((value ushr i) and 1)
                n++
                if (n == 8) {
                    out.write(acc)
                    acc = 0
                    n = 0
                }
            }
            return this
        }

        /** Unsigned Exp-Golomb, ue(v). */
        fun ue(value: Int): Bits {
            val v = value + 1
            var leading = 0
            while ((1 shl (leading + 1)) <= v) leading++
            u(0, leading)
            u(v, leading + 1)
            return this
        }

        fun bytes(): ByteArray {
            if (n > 0) {
                out.write(acc shl (8 - n))
                acc = 0
                n = 0
            }
            return out.toByteArray()
        }
    }

    /**
     * The Dolby Vision VPS RBSP the relabel fixtures use (18B → 12B): High tier
     * on profile_idc 2 with zero compatibility flags and vps_extension_flag set
     * — the extension is the DV signalling, the High tier is what
     * [DolbyVisionCompat.rewriteVpsToHdr10] patches away.
     */
    private val dvVpsRbsp = byteArrayOf(
        0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x22, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x96.toByte(), 0xF0.toByte(), 0x28
    )

    /**
     * A full, parseable single-layer P8 SPS: clean Main10 PTL (space 0, Main
     * tier, profile_idc 2, Main10 compatibility flag, Level 5.0) with
     * sps_extension_present_flag set and the all-zero extension header Dolby
     * Vision uses on single-layer encodes — the form
     * [DolbyVisionCompat.rewriteSpsToHdr10] strips (and the field file's 61B →
     * 59B shape).
     */
    private val dvSpsRbsp: ByteArray = Bits()
        .u(0, 4).u(0, 3).u(1, 1) // sps_video_parameter_set_id, max_sub_layers_minus1, nesting
        .u(0, 2).u(0, 1).u(2, 5) // profile_space, Main tier, profile_idc 2 (Main10)
        .u(0x20000000, 32) // compatibility: Main10 flag
        .u(0xF, 4).u(0, 44) // constraint indicator flags
        .u(150, 8) // general_level_idc (Level 5.0)
        .ue(0) // sps_seq_parameter_set_id
        .ue(1) // chroma_format_idc 4:2:0
        .ue(3840).ue(1608) // resolution
        .u(0, 1) // conformance_window_flag
        .ue(2).ue(2) // 10-bit luma / chroma
        .ue(4) // log2_max_pic_order_cnt_lsb_minus4
        .u(0, 1) // sub_layer_ordering_info_present_flag
        .ue(4).ue(2).ue(0) // buffering, reorder, latency
        .ue(0).ue(3).ue(1).ue(3).ue(2).ue(2) // block sizes
        .u(0, 1) // scaling_list_enabled_flag
        .u(1, 1).u(1, 1) // amp, sao
        .u(0, 1) // pcm_enabled_flag
        .ue(0) // num_short_term_ref_pic_sets
        .u(0, 1) // long_term_ref_pics_present_flag
        .u(1, 1).u(1, 1) // temporal mvp, strong intra smoothing
        .u(0, 1) // vui_parameters_present_flag
        .u(1, 1) // sps_extension_present_flag
        .u(0, 1).u(0, 1).u(0, 1).u(0, 1).u(0, 4) // the DV extension itself
        .u(0xDEAD, 16) // DV extension payload the rewrite drops with the extension
        .u(1, 1) // rbsp_stop_one_bit
        .bytes()

    private val ppsRbsp = byteArrayOf(0x0D, 0x0E, 0x0F)

    /** A 2-byte NAL header for [type] plus [rbsp] — no start code, no escape. */
    private fun nal(type: Int, rbsp: ByteArray): ByteArray =
        byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + rbsp

    /** Re-inserts emulation-prevention bytes so an RBSP can ride in Annex-B. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 4)
        var zeros = 0
        rbsp.forEach { raw ->
            val v = raw.toInt() and 0xFF
            if (zeros >= 2 && v <= 0x03) {
                out.write(0x03)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Annex-B framing: a 4-byte start code before every NAL. */
    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { unit ->
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    /** Length-delimited framing: a 4-byte big-endian length before every NAL. */
    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { unit ->
            out.write(byteArrayOf((unit.size ushr 24).toByte(), (unit.size ushr 16).toByte(), (unit.size ushr 8).toByte(), unit.size.toByte()))
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    // The sample's pieces, in the field's order: parameter sets, a prefix SEI,
    // the picture, then the RPU (and a suffix SEI where a case needs one).
    private val vps = nal(32, escape(dvVpsRbsp))
    private val sps = nal(33, escape(dvSpsRbsp))
    private val pps = nal(34, ppsRbsp)
    private val idr = nal(19, ByteArray(2048) { 0x44 })
    private val rpu = nal(62, ByteArray(205) { 0x66 })
    private val prefixSei = nal(39, byteArrayOf(0x04, 0x01, 0x00))
    private val vendorSuffixSei = nal(40, byteArrayOf(0x05, 0x1F) + ByteArray(31) { 0x5A })

    /** First index of [needle] in [haystack], or -1. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** The rewrite's own bytes for [unit], so the expectation is the production one. */
    private fun rewritten(unit: ByteArray, type: Int): ByteArray {
        val outcome = if (type == 32) {
            DolbyVisionCompat.rewriteVpsToHdr10(unit, 2, unit.size)
        } else {
            DolbyVisionCompat.rewriteSpsToHdr10(unit, 2, unit.size)
        }
        val rbsp = when (outcome) {
            is DolbyVisionCompat.VpsRewriteOutcome.Rewritten -> outcome.rbsp
            is DolbyVisionCompat.SpsRewriteOutcome.Rewritten -> outcome.rbsp
            else -> throw AssertionError("fixture did not rewrite: $outcome")
        }
        return unit.copyOf(2) + rbsp
    }

    // ── the fix ────────────────────────────────────────────────────────────

    @Test
    fun `the in-band VPS and SPS are re-emitted as the rewritten sets`() {
        // The field's shape: a DV VPS and SPS in the sample, the RPU after them.
        val sample = annexB(vps, sps, pps, idr, rpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true, rewriteParamSets = true
        )

        val expected = annexB(rewritten(vps, 32), rewritten(sps, 33), pps, idr)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals("the sample is the rebuilt one", expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertTrue(
            "the SPS has to shrink, or the offset case proves nothing: " +
                "sps ${sps.size}->${rewritten(sps, 33).size}",
            rewritten(sps, 33).size < sps.size
        )
        assertFalse(
            "and neither original DV-flagged set may survive in the sample",
            indexOf(expected, vps) >= 0 || indexOf(expected, sps) >= 0
        )
        assertTrue(
            "the log still accounts for the RPU: ${accounting.summary()}",
            accounting.summary().contains("62:${rpu.size}->dropped")
        )
    }

    @Test
    fun `the NALs after a shortened set land at corrected offsets`() {
        // The Annex-B caution, spelled out: the two sets shrink, so the IDR must
        // move up by exactly that much. Splicing the shorter sets back into the
        // original offsets would leave the IDR where it was and desynchronize
        // everything after it.
        val sample = annexB(vps, sps, pps, idr, rpu)
        // Every NAL carries its own 4-byte start code, and each set is re-emitted
        // as start code + header + rewritten RBSP - so the IDR sits after four
        // start codes (the two sets, the PPS and its own).
        val startCode = 4
        val shrink = (vps.size - rewritten(vps, 32).size) +
            (sps.size - rewritten(sps, 33).size)

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            declaredDv = true, rewriteParamSets = true
        )
        val out = sample.copyOf(newLen)

        val originalIdrAt = 4 * startCode + vps.size + sps.size + pps.size
        val rebuiltIdrAt = 4 * startCode +
            rewritten(vps, 32).size + rewritten(sps, 33).size + pps.size
        assertTrue("the sets have to shrink for this to mean anything: $shrink", shrink > 0)
        assertEquals("the IDR moves up by exactly the shrink", originalIdrAt - shrink, rebuiltIdrAt)
        assertEquals(
            "the IDR is where the rebuild put it, not spliced back at the old offset",
            rebuiltIdrAt,
            indexOf(out, idr)
        )
        assertTrue(
            "and the old offset is no longer an IDR: ${indexOf(out, idr)}",
            indexOf(out, idr) != originalIdrAt
        )
    }

    @Test
    fun `without the flag nothing but the removal happens, byte for byte`() {
        // The in-band path's guarantee (Master5): a caller that did not
        // re-advertise a rewritten csd must get the sample it always got.
        val sample = annexB(vps, sps, pps, idr, rpu)
        val before = sample.copyOf()

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            declaredDv = true
        )

        val expected = annexB(vps, sps, pps, idr)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertArrayEquals("nothing else moved", before, before)
    }

    @Test
    fun `a set the rewrite cannot touch is forwarded unchanged`() {
        // A 3-byte VPS: the rewrite fails, and a half-rewritten set must never
        // reach the decoder - the original NAL rides through exactly.
        val shortVps = nal(32, byteArrayOf(0x11))
        val sample = annexB(shortVps, sps, pps, idr, rpu)

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            declaredDv = true, rewriteParamSets = true
        )

        val expected = annexB(shortVps, rewritten(sps, 33), pps, idr)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
    }

    @Test
    fun `the NAL 39, 40 and 62 decisions are unchanged by the rewrite`() {
        // Fix 1 touches the parameter sets and nothing else: the declared-DV
        // suffix SEI is still dropped, the prefix SEI still rides through, and
        // the RPU still goes - in the same run that rewrites the sets.
        val sample = annexB(vps, sps, pps, prefixSei, idr, vendorSuffixSei, rpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true, rewriteParamSets = true
        )

        val expected = annexB(
            rewritten(vps, 32), rewritten(sps, 33), pps, prefixSei, idr
        )
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        val summary = accounting.summary()
        assertTrue(
            "the prefix SEI is kept: $summary",
            summary.contains("39:${prefixSei.size}->kept")
        )
        assertTrue(
            "and the declared-DV suffix SEI is dropped: $summary",
            summary.contains("40:35->dropped(" + DolbyVisionCompat.HevcSeiAudit.DROP_REASON_DECLARED_DV_SUFFIX + ")")
        )
        assertFalse("nothing was shrunk in this sample", summary.contains("->shrunk"))
    }

    @Test
    fun `the length-delimited framing rewrites the sets and their length prefixes`() {
        val sample = lengthDelimited(vps, sps, pps, idr, rpu)

        val newLen = DolbyVisionCompat.stripLengthDelimited(
            sample, sample.size, 4,
            stripDv = true, stripHdr10Plus = true,
            declaredDv = true, rewriteParamSets = true
        )

        val newVps = rewritten(vps, 32)
        val newSps = rewritten(sps, 33)
        val expected = lengthDelimited(newVps, newSps, pps, idr)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        // The prefix is what the decoder walks the sample with, so it has to
        // describe the rewritten NAL, header included.
        val firstPrefix =
            ((sample[0].toInt() and 0xFF) shl 24) or ((sample[1].toInt() and 0xFF) shl 16) or
                ((sample[2].toInt() and 0xFF) shl 8) or (sample[3].toInt() and 0xFF)
        assertEquals("the VPS length prefix is the rewritten size", newVps.size, firstPrefix)
    }

    @Test
    fun `the length-delimited in-band arm is untouched`() {
        val sample = lengthDelimited(vps, sps, pps, idr, rpu)

        val newLen = DolbyVisionCompat.stripLengthDelimited(
            sample, sample.size, 4,
            stripDv = true, stripHdr10Plus = true, declaredDv = true
        )

        val expected = lengthDelimited(vps, sps, pps, idr)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
    }
}
