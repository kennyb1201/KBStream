package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relabel path's VPS+SPS rewrite in codec-private data:
 * [DolbyVisionCompat.rewriteRelabelInitData] and the [relabelTargetFor] step
 * that hands its result to the relabel.
 *
 * Field problem, first half (6.36): the relabel (correct `hvc1.2.4.H150.B0`, RPU
 * dropped, HDR10+ SEI stripped) still stalled with zero decoder output. The
 * codec config still declared the Dolby Vision VPS ("VPS/SPS untouched"), so the
 * decoder configured from a DV-flagged parameter set and then waited for the
 * RPUs the relabel removes. KBStream's VPS rewrite existed, but it only reached
 * the MP4 path's framing, and the container here is the one that stalled — so
 * the rewrite now dispatches on the framing it is actually given
 * (length-delimited, hvcC, Annex-B).
 *
 * Second half (2026-10-16, NORViNE Matchbox 2160p `dvhe.08.06`, Strip All): with
 * the label, the config VPS and the in-sample sets all rewritten, the decoder
 * STILL produced zero output for 20s+, and the one DV-flagged byte left was the
 * config's SPS. The config is parsed before any sample, so a DV-flagged SPS in
 * it arms the same stall — and the rewrite now takes the SPS with it, re-emitting
 * it as its original 2-byte NAL header followed by the rewritten RBSP under a
 * fresh length (the shape 9b084af7 established; the bare RBSP under a fresh
 * length is the corrupt-config bug that commit fixed).
 *
 * The invariants pinned behaviorally: the PPS and every unparseable buffer stay
 * byte-identical (a guess here corrupts the track instead of failing loudly,
 * and this runs inside format negotiation), the failure arms keep the original
 * SPS, and the advertised codec string is derived from the ORIGINAL SPS before
 * this runs — it is never re-derived from the rewritten one.
 */
class DolbyVisionVpsInitDataRewriteTest {

    /** A 2-byte NAL header for [type] plus [payload] as the RBSP. */
    private fun nal(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + payload

    /** Length-delimited framing: a 4-byte big-endian length before every NAL. */
    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { unit ->
            out.write((unit.size ushr 24) and 0xFF)
            out.write((unit.size ushr 16) and 0xFF)
            out.write((unit.size ushr 8) and 0xFF)
            out.write(unit.size and 0xFF)
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    private fun readLengthPrefix(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)

    /** Annex-B framing: a 4-byte start code before every NAL. */
    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { unit ->
            out.write(0)
            out.write(0)
            out.write(0)
            out.write(1)
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    /**
     * A Dolby Vision VPS RBSP, bit by bit (ITU-T H.265 7.3.2.1): vps_id 0, both
     * base-layer flags set, one layer, one sub-layer, nesting flag set, the
     * 0xFFFF reserved field, then a profile_tier_level that declares Dolby
     * Vision's High tier on profile_idc 2 (Main10), zero compatibility flags,
     * level 150, ordering info present, one layer set, no timing info and
     * vps_extension_flag set. The extension flag is the DV signaling, and the
     * High tier is what [DolbyVisionCompat.rewriteVpsToHdr10] patches away.
     */
    private val dvVpsRbsp = byteArrayOf(
        0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x22, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x96.toByte(), 0xF0.toByte(), 0x28
    )
    private val dvVps = nal(32, dvVpsRbsp)

    /**
     * A Main10 High tier Level 5.0 SPS — the `dvhe.08.06` field case, and the
     * SPS the relabel's advertised `hvc1.2.4.H150.B0` is derived from.
     */
    private val sps = nal(
        33,
        byteArrayOf(
            0x01, 0x22, 0x20, 0x00, 0x00, 0x00,
            0xB0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x96.toByte()
        )
    )
    private val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))

    /**
     * The field file's own SPS shape: a FULL parseable single-layer P8 SPS —
     * clean Main10 general PTL on [tier] (1 = High, which is where the field's
     * `hvc1.2.4.H150.B0` comes from), compatibility flag[2] (Main10) and
     * constraint byte 0xB0 exactly as the 2026-10-16 capture read them,
     * 3840x1608, 10-bit, and the `sps_extension_present_flag` + all-zero
     * extension header Dolby Vision uses on single-layer encodes. That extension
     * is the DV signalling [DolbyVisionCompat.rewriteSpsToHdr10] removes, so this
     * is the config SPS the 2026-10-16 stall left behind DV-flagged.
     *
     * The layout is the H.265 one, which puts `general_profile_space` at RBSP bit
     * 8 (sps_video_parameter_set_id 4, sps_max_sub_layers_minus1 3,
     * sps_temporal_id_nesting_flag 1, then profile_tier_level — no reserved
     * field). The app's own [DolbyVisionCompat.hevcCodecFromInitData] reads it at
     * that offset and derives the field capture's string, which is what the
     * assertions below hold it to.
     */
    private fun fullP8Sps(tier: Int, dvExtension: Boolean, levelIdc: Int = 150): ByteArray {
        val bits = Bits()
            .u(0, 4).u(0, 3).u(1, 1) // sps_video_parameter_set_id, max_sub_layers_minus1, nesting
            .u(0, 2).u(tier, 1).u(2, 5) // profile_space, tier, profile_idc 2 (Main10)
            .u(0x20000000, 32) // compatibility flag[2] (Main10) — read back as hex 4
            .u(0xB, 4).u(0, 44) // constraint indicator flags, first byte 0xB0
            .u(levelIdc, 8) // general_level_idc
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
        if (dvExtension) {
            bits.u(1, 1) // sps_extension_present_flag
                .u(0, 1).u(0, 1).u(0, 1).u(0, 1).u(0, 4) // the DV extension itself
                .u(0xDEAD, 16) // its payload, dropped with the extension
        } else {
            bits.u(0, 1)
        }
        return nal(33, bits.u(1, 1).bytes())
    }

    /**
     * The DV-flagged SPS the 2026-10-16 field file carried: a High-tier
     * Main10 set whose extension signals Dolby Vision, so
     * [DolbyVisionCompat.hevcCodecFromInitData] derives the capture's own
     * `hvc1.2.4.H150.B0` from it.
     */
    private val dvSps: ByteArray = fullP8Sps(tier = 1, dvExtension = true)

    /** MSB-first bit writer, so the SPS above can be built field by field. */
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

    /** The rewrite's own bytes for a parameter set, so the expectation is production's. */
    private fun rewrittenNal(unit: ByteArray, vps: Boolean): ByteArray {
        val rbsp = if (vps) {
            when (val res = DolbyVisionCompat.rewriteVpsToHdr10(unit, 2, unit.size)) {
                is DolbyVisionCompat.VpsRewriteOutcome.Rewritten -> res.rbsp
                else -> throw AssertionError("the VPS fixture did not rewrite: $res")
            }
        } else {
            when (val res = DolbyVisionCompat.rewriteSpsToHdr10(unit, 2, unit.size)) {
                is DolbyVisionCompat.SpsRewriteOutcome.Rewritten -> res.rbsp
                else -> throw AssertionError("the SPS fixture did not rewrite: $res")
            }
        }
        return unit.copyOf(2) + rbsp
    }

    /** An hvcC (HEVCDecoderConfigurationRecord) holding [nals] as one array each. */
    private fun hvcCRecord(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(23)
        header[0] = 1 // configurationVersion
        header[10] = 0x55 // a general-profile byte the rewrite must copy verbatim
        header[22] = nals.size.toByte()
        out.write(header, 0, header.size)
        nals.forEach { unit ->
            val type = (unit[0].toInt() and 0xFF) ushr 1
            out.write(type)
            out.write(0)
            out.write(1) // one NAL in this array
            out.write((unit.size ushr 8) and 0xFF)
            out.write(unit.size and 0xFF)
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    // ── behavioral: the framing rewrite ───────────────────────────────────

    @Test
    fun `the length-delimited parameter sets are rewritten and re-emitted with new lengths`() {
        // MKV's shape: the parameter sets reach the player length-delimited, so
        // an Annex-B-only rewrite leaves the DV VPS (and, since 2026-10-16, the
        // DV SPS) in place and the decoder waits for metadata that was stripped.
        val init = lengthDelimited(dvVps, dvSps, pps)
        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(init))

        val out = result.initData.single()
        assertTrue("the config must have changed", out !== init)
        assertFalse("and not be the same bytes", out.contentEquals(init))
        assertTrue("the VPS was rewritten and the tally says so", result.vpsRewritten)
        assertEquals(
            "and the SPS too - the 2026-10-16 stall's one DV-flagged byte",
            "applied",
            spsRewriteOutcome(skippedForLibdovi = false, result.spsOutcome)
        )
        // Both sets are re-emitted as their original 2-byte NAL header plus the
        // rewritten RBSP, and each length prefix covers exactly that.
        val newVps = rewrittenNal(dvVps, vps = true)
        val newSps = rewrittenNal(dvSps, vps = false)
        val expected = lengthDelimited(newVps, newSps, pps)
        assertArrayEquals(expected, out)
        val vpsLen = readLengthPrefix(out, 0)
        assertEquals("the VPS length prefix counts header + rewritten RBSP", newVps.size, vpsLen)
        assertEquals("the NAL header is carried through", 0x40, out[4].toInt() and 0xFF)
        val spsAt = 4 + vpsLen
        assertEquals(
            "the SPS length prefix counts header + rewritten RBSP too; a bare RBSP under a " +
                "fresh length is the corrupt-config bug 9b084af7 fixed",
            newSps.size,
            readLengthPrefix(out, spsAt)
        )
        assertEquals(
            "and its NAL header is carried through as well",
            0x42,
            out[spsAt + 4].toInt() and 0xFF
        )
        assertEquals(
            "its second byte too - a payload byte here is the bug",
            0x01,
            out[spsAt + 5].toInt() and 0xFF
        )
        assertArrayEquals(
            "the PPS is the one set left bit-exact",
            pps,
            out.copyOfRange(out.size - pps.size, out.size)
        )
        val rbsp = out.copyOfRange(6, 6 + (vpsLen - 2))
        assertEquals(
            "the rewritten VPS must declare Main tier Main10 (profile_idc 2, tier_flag 0) — " +
                "the Dolby Vision High tier is what keeps the decoder waiting",
            0x02,
            rbsp[4].toInt() and 0x3F
        )
        val second = DolbyVisionCompat.rewriteRelabelInitData(result.initData)
        assertSame(
            "a second pass must find nothing left to rewrite, which is the clean " +
                "single-layer marker",
            result.initData,
            second.initData
        )
        assertEquals(
            "and it reports the clean SPS as needing nothing",
            "not-needed",
            spsRewriteOutcome(skippedForLibdovi = false, second.spsOutcome)
        )
        assertFalse(second.vpsRewritten)
    }

    /**
     * The mutating strip's config rewrite (the VPS-and-SPS variant, reached
     * through `rewriteInitDataVps`) answers with the rewritten RBSP ALONE, so
     * the NAL has to be re-emitted as its original 2-byte header plus that
     * payload, with the length prefix counting both. Writing the bare RBSP with
     * a fresh length and no header is what a decoder reads as a NAL header
     * sitting on payload bytes: the record then carries a parameter set
     * nothing can find, which is a corrupt codec config on every path that
     * mutates a declared-DV MP4/fMP4.
     */
    @Test
    fun `the VPS-and-SPS config path keeps the rewritten VPS's NAL header`() {
        val record = hvcCRecord(dvVps, sps)
        val out = DolbyVisionCompat.rewriteInitDataVps(listOf(record)).single()

        assertEquals(
            "this path patches the record's general profile to Main10 (profile_idc 2), " +
                "which is the one byte of the header it owns",
            0x02,
            out[1].toInt() and 0xFF
        )
        assertEquals(
            "and raises the Main10 compatibility flag (profile 2 is the top bit of the " +
                "second flag byte) - the fixture's flags start at zero",
            0x20,
            out[2].toInt() and 0xFF
        )
        assertArrayEquals(
            "while every header byte past the flags is copied verbatim",
            record.copyOfRange(6, 23),
            out.copyOfRange(6, 23)
        )
        assertEquals("the array header still says VPS", 32, out[23].toInt() and 0x3F)
        assertEquals(
            "and still holds one NAL",
            1,
            ((out[24].toInt() and 0xFF) shl 8) or (out[25].toInt() and 0xFF)
        )
        val vpsLen = ((out[26].toInt() and 0xFF) shl 8) or (out[27].toInt() and 0xFF)
        assertEquals(
            "the NAL header is carried through",
            0x40,
            out[28].toInt() and 0xFF
        )
        assertEquals(
            "its second byte too - a payload byte in this position is the bug",
            0x01,
            out[29].toInt() and 0xFF
        )
        assertEquals(
            "and the length counts that header with the rewritten RBSP",
            out.size - 30 - (sps.size + 5),
            vpsLen - 2
        )
        assertArrayEquals(
            "the SPS array is still byte-identical behind it",
            byteArrayOf(33, 0, 1, (sps.size shr 8).toByte(), sps.size.toByte()) + sps,
            out.copyOfRange(out.size - (sps.size + 5), out.size)
        )
    }

    /**
     * The two bits after vps_video_parameter_set_id are the base layer's
     * internal/available flags, not a reserved field, and H.265 ties neither of
     * them to the layer count. The rewrite used to demand both set ("base layer
     * present"), so a VPS declaring its base layer unavailable was left
     * DV-flagged in the codec config — the one outcome this path exists to
     * prevent, since the config is parsed before any sample. It now rewrites
     * such a set and says what it is sending: base layer present, one layer.
     */
    @Test
    fun `a VPS whose base-layer flags are clear is rewritten instead of kept`() {
        // The fixture with vps_base_layer_internal_flag and
        // vps_base_layer_available_flag cleared (bits 4 and 5 of the first RBSP
        // byte), layer count and everything else untouched: valid H.265, and
        // exactly the set the old gate refused.
        val noBaseLayer = dvVpsRbsp.copyOf().also { it[0] = 0x00 }
        val vps = nal(32, noBaseLayer)
        assertFalse("the variant must differ from the fixture", vps.contentEquals(dvVps))

        val outcome = DolbyVisionCompat.rewriteVpsToHdr10(vps, 2, vps.size)
        val rewritten = when (outcome) {
            is DolbyVisionCompat.VpsRewriteOutcome.Rewritten -> outcome.rbsp
            else -> throw AssertionError("the set was not rewritten: $outcome")
        }
        assertEquals(
            "vps_video_parameter_set_id is the source's own",
            0,
            (rewritten[0].toInt() ushr 4) and 0x0F
        )
        assertEquals(
            "but the base layer's two flags are FORCED set: the single layer this set now " +
                "declares is the base layer and it is present in the NALs the decoder is " +
                "handed, so inheriting a source's 0 here would describe a stream with no " +
                "base layer while shipping one",
            0b11,
            (rewritten[0].toInt() ushr 2) and 0b11
        )
        assertEquals(
            "and the sub-layer count / nesting flag / 0xFFFF reserved field are the source's",
            0x01,
            rewritten[1].toInt() and 0xFF
        )
        assertFalse(
            "the set really changed: the High tier is patched and the DV extension dropped",
            rewritten.contentEquals(noBaseLayer)
        )
        // The rewrite emits an emulation-prevention-escaped RBSP, so its length
        // is not comparable with this unescaped fixture's; the property that
        // matters is that a second pass finds nothing left. It parses the
        // emitted header, finds the patched Main10 PTL and a cleared extension
        // flag — i.e. the DV signaling is gone and no High tier survives.
        val emitted = nal(32, rewritten)
        assertEquals(
            "nothing DV-flagged is left in the emitted set",
            DolbyVisionCompat.VpsRewriteOutcome.NoDvExtension,
            DolbyVisionCompat.rewriteVpsToHdr10(emitted, 2, emitted.size)
        )

        // The claim the rebuild's comment makes, pinned rather than asserted in
        // prose: a source that ALREADY says "base layer present" - every real
        // single-layer encode, and the field file's own VPS - comes out
        // byte-identical to what this rewrite produced before the flags were
        // forced, so nothing that rewrote successfully changes bytes.
        val withBaseLayer = when (val o = DolbyVisionCompat.rewriteVpsToHdr10(dvVps, 2, dvVps.size)) {
            is DolbyVisionCompat.VpsRewriteOutcome.Rewritten -> o.rbsp
            else -> throw AssertionError("the fixture did not rewrite: $o")
        }
        assertEquals(
            "the source's own header, unchanged: id 0, both flags set, one layer",
            0x0C,
            withBaseLayer[0].toInt() and 0xFF
        )
        assertEquals(0x01, withBaseLayer[1].toInt() and 0xFF)

        // The field-relevant claim: the config this path ships is the rewrite,
        // never the source DV VPS.
        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(lengthDelimited(vps, pps)))
        assertTrue("the config's VPS verdict says rewritten", result.vpsRewritten)
        assertArrayEquals(
            "and the set that ships is the rewritten one, header preserved",
            lengthDelimited(vps.copyOf(2) + rewritten, pps),
            result.initData.single()
        )
    }

    @Test
    fun `a config with nothing rewritable comes back as the very same list`() {
        // The small fixture's SPS is a DV-flagged PTL the parser cannot walk end
        // to end, and this buffer carries no VPS: both sets keep their bytes, so
        // nothing is allocated and nothing is re-emitted.
        val init = listOf(lengthDelimited(sps, pps), ByteArray(0))
        val result = DolbyVisionCompat.rewriteRelabelInitData(init)
        assertSame(
            "nothing rewritten means nothing allocated and nothing re-emitted",
            init,
            result.initData
        )
        assertFalse(result.vpsRewritten)
        assertTrue(
            "and the SPS verdict is the parse failure, not a silent keep",
            result.spsOutcome is DolbyVisionCompat.SpsRewriteOutcome.Failed
        )
    }

    @Test
    fun `a truncated length-delimited run is left byte-exact`() {
        // The last NAL's prefix claims more bytes than the buffer holds. A
        // framing rewrite may not guess at that; the original bytes are the
        // only safe answer.
        val truncated = lengthDelimited(dvVps).copyOf(20)
        val init = listOf(truncated)
        assertSame(init, DolbyVisionCompat.rewriteRelabelInitData(init).initData)
    }

    @Test
    fun `an hvcC record has both parameter-set arrays rewritten and everything else copied`() {
        val record = hvcCRecord(dvVps, dvSps)
        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(record))
        val out = result.initData.single()
        val newVps = rewrittenNal(dvVps, vps = true)
        val newSps = rewrittenNal(dvSps, vps = false)

        assertArrayEquals(
            "the record header (including its general profile_tier_level) is copied verbatim",
            record.copyOfRange(0, 23),
            out.copyOfRange(0, 23)
        )
        assertEquals("the array header still says VPS", 32, out[23].toInt() and 0x3F)
        val vpsLen = ((out[26].toInt() and 0xFF) shl 8) or (out[27].toInt() and 0xFF)
        assertEquals(
            "and still holds one NAL",
            1,
            ((out[24].toInt() and 0xFF) shl 8) or (out[25].toInt() and 0xFF)
        )
        assertEquals("the NAL header is carried through", 0x40, out[28].toInt() and 0xFF)
        assertEquals(
            "and the length counts it with the rewritten RBSP, header included",
            newVps.size,
            vpsLen
        )
        assertArrayEquals(
            "so the VPS record NAL is the rewritten one, header and all",
            newVps,
            out.copyOfRange(28, 28 + newVps.size)
        )
        val spsArrayAt = 28 + newVps.size
        assertEquals(
            "the SPS array follows, still labelled SPS",
            33,
            out[spsArrayAt].toInt() and 0x3F
        )
        assertEquals(
            "still holding one NAL",
            1,
            ((out[spsArrayAt + 1].toInt() and 0xFF) shl 8) or (out[spsArrayAt + 2].toInt() and 0xFF)
        )
        assertEquals(
            "and its length counts the header with the rewritten SPS RBSP",
            newSps.size,
            ((out[spsArrayAt + 3].toInt() and 0xFF) shl 8) or (out[spsArrayAt + 4].toInt() and 0xFF)
        )
        assertArrayEquals(
            "the SPS record NAL is the rewritten one: this array used to ride through " +
                "untouched, which is the 2026-10-16 stall",
            newSps,
            out.copyOfRange(spsArrayAt + 5, out.size)
        )
        assertTrue("and the tally reports both sets", result.vpsRewritten)
        assertEquals("applied", spsRewriteOutcome(skippedForLibdovi = false, result.spsOutcome))
        assertFalse(
            "the VPS payload itself must not be the source bytes",
            out.copyOfRange(30, 30 + (vpsLen - 2)).contentEquals(dvVpsRbsp)
        )
    }

    @Test
    fun `the advertised codec string is read from the SPS before the rewrite`() {
        // The relabel advertises the string it reads from this very SPS, and the
        // field capture is what those bytes must keep saying (`hvc1.2.4.H150.B0`
        // — High tier Level 5.0, the 2026-10-16 run's own string). The config SPS
        // is rewritten underneath it, so the string has to be taken BEFORE the
        // rewrite: it is not re-derived from the rewritten set.
        val init = listOf(annexB(dvVps, dvSps, pps))
        val advertised = DolbyVisionCompat.hevcCodecFromInitData(init)
        assertEquals("the field case's own string", "hvc1.2.4.H150.B0", advertised)

        val result = DolbyVisionCompat.rewriteRelabelInitData(init)
        assertTrue("the VPS is rewritten", result.vpsRewritten)
        assertEquals("and the SPS with it", "applied", spsRewriteOutcome(false, result.spsOutcome))
        val rewrittenString = DolbyVisionCompat.hevcCodecFromInitData(result.initData)
        assertNotEquals(
            "the config the decoder is configured with is no longer the source's",
            advertised,
            rewrittenString
        )
        // The rewritten config SPS has to read back as the Main10 it was patched
        // to. It did not: with the rewrite consuming a two-bit `reserved` field
        // the H.265 SPS syntax does not have (general_profile_space starts at
        // RBSP bit 8; the two reserved bits belong to the VPS header), the whole
        // general PTL came back displaced by two bits — the field's own config
        // SPS read `hvc1.0.<compat>.L150.B0`, profile_idc 0, when the set it now
        // ships declares Main10. Only the tier is patched (High → Main); the
        // level and the constraint byte are the source's, bit-exact.
        assertEquals(
            "the rewritten config SPS must read back as Main10 Main tier at the " +
                "source's own level: $rewrittenString",
            "hvc1.2.4.L150.B0",
            rewrittenString
        )
        assertTrue(
            "the source's level and constraint byte survive the patch: $rewrittenString",
            rewrittenString != null && rewrittenString.endsWith(".L150.B0")
        )
    }

    @Test
    fun `the rewritten config SPS keeps a level other than the field capture's`() {
        // "At the same level" is not a property of the 150 fixture: the level and
        // the constraint byte sit AFTER the general PTL fields the patch
        // rewrites, so they are copied bit-exact whatever they say. A rewrite
        // offset that drifted into the level would advertise a level the source
        // never declared — the advertised-string-vs-SPS disagreement the whole
        // MTK stall class is made of.
        val spsMain = fullP8Sps(tier = 0, dvExtension = true, levelIdc = 93)
        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(annexB(dvVps, spsMain, pps)))

        assertEquals("the SPS is rewritten", "applied", spsRewriteOutcome(false, result.spsOutcome))
        assertEquals(
            "Main10 Main tier at the source's own Level 3.1",
            "hvc1.2.4.L93.B0",
            DolbyVisionCompat.hevcCodecFromInitData(result.initData)
        )
    }

    @Test
    fun `an SPS the parser cannot walk keeps the original bytes and says why`() {
        // The `Failed` arm on the relabel's own path: the 13-byte fixture is a
        // DV-flagged PTL with no walkable body, so the rewrite bails and the set
        // reaches the decoder exactly as it arrived rather than half-rewritten.
        val record = hvcCRecord(dvVps, sps)
        val advertised = DolbyVisionCompat.hevcCodecFromInitData(listOf(record))
        assertEquals("the field PTL's own string", "hvc1.2.4.H150.B0", advertised)

        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(record))

        assertTrue("the VPS is still rewritten", result.vpsRewritten)
        assertTrue(
            "and the field log can say the VPS was rewritten while the SPS failed",
            spsRewriteOutcome(false, result.spsOutcome).startsWith("failed(")
        )
        assertEquals(
            "while the source bytes it advertises from are untouched",
            advertised,
            DolbyVisionCompat.hevcCodecFromInitData(listOf(record))
        )
        assertTrue(
            "and the SPS array in that record is byte-identical to the source's",
            result.initData.single()
                .copyOfRange(
                    result.initData.single().size - (sps.size + 5),
                    result.initData.single().size
                )
                .contentEquals(byteArrayOf(33, 0, 1, (sps.size shr 8).toByte(), sps.size.toByte()) + sps)
        )
    }

    // ── behavioral: the config SPS (the 2026-10-16 field stall) ───────────

    @Test
    fun `the Annex-B config framing rewrites the SPS beside the VPS`() {
        // The third framing Media3 can hand over, and the one a TS/Annex-B
        // CodecPrivate arrives in. Both sets are re-emitted as start code +
        // original 2-byte header + rewritten RBSP; the PPS rides through.
        val init = annexB(dvVps, dvSps, pps)
        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(init))

        val out = result.initData.single()
        assertArrayEquals(
            "VPS and SPS both rewritten, PPS byte-exact",
            annexB(rewrittenNal(dvVps, vps = true), rewrittenNal(dvSps, vps = false), pps),
            out
        )
        assertTrue(result.vpsRewritten)
        assertEquals("applied", spsRewriteOutcome(skippedForLibdovi = false, result.spsOutcome))
    }

    @Test
    fun `a config the rewrite already patched is left alone on the next pass`() {
        // The `NoDvExtension` arm, reached the way it is reached in the field: a
        // retry ladder can hand the same config back, and a set that already
        // carries no DV extension must come back byte-exact rather than be
        // rewritten again. It is also what makes the rewrite safe to run more
        // than once - the second pass finds nothing to do and allocates nothing.
        val first = DolbyVisionCompat.rewriteRelabelInitData(
            listOf(lengthDelimited(dvVps, dvSps, pps))
        )
        assertTrue("the first pass is the applied one", first.vpsRewritten)
        assertEquals("applied", spsRewriteOutcome(false, first.spsOutcome))

        val second = DolbyVisionCompat.rewriteRelabelInitData(first.initData)

        assertSame(
            "a second pass changes nothing and re-emits nothing",
            first.initData,
            second.initData
        )
        assertEquals(
            "and the log says the SPS needed nothing, which is its not-needed arm",
            "not-needed",
            spsRewriteOutcome(skippedForLibdovi = false, second.spsOutcome)
        )
        assertFalse(second.vpsRewritten)
    }

    @Test
    fun `an SPS the rewrite cannot parse keeps its original bytes`() {
        // The `Failed` arm. The fixture's SPS is a DV-flagged PTL with no
        // walkable body, so the parser bails: the set must reach the decoder
        // exactly as it arrived rather than half-rewritten, and the log has to
        // be able to say the rewrite failed and why.
        val init = lengthDelimited(dvVps, sps, pps)
        val result = DolbyVisionCompat.rewriteRelabelInitData(listOf(init))

        val out = result.initData.single()
        assertArrayEquals(
            "the VPS alone is rewritten; the SPS keeps every byte",
            lengthDelimited(rewrittenNal(dvVps, vps = true), sps, pps),
            out
        )
        val outcome = result.spsOutcome
        assertTrue("the verdict is a failure, not silence: $outcome", outcome is DolbyVisionCompat.SpsRewriteOutcome.Failed)
        assertTrue(
            "and it carries the parser's reason for the field log",
            spsRewriteOutcome(skippedForLibdovi = false, outcome)
                .startsWith("failed(") && spsRewriteOutcome(skippedForLibdovi = false, outcome) != "failed()"
        )
    }

    @Test
    fun `the log's SPS verdict separates every arm`() {
        // One field, five readings: which one a capture shows decides what the
        // next step is, so none of them may collapse into another.
        assertEquals(
            "skipped(libdovi)",
            spsRewriteOutcome(skippedForLibdovi = true, outcome = null)
        )
        assertEquals(
            "the skip outranks whatever the rewrite would have said",
            "skipped(libdovi)",
            spsRewriteOutcome(
                skippedForLibdovi = true,
                outcome = DolbyVisionCompat.SpsRewriteOutcome.Rewritten(dvSps)
            )
        )
        assertEquals(
            "applied",
            spsRewriteOutcome(
                skippedForLibdovi = false,
                outcome = DolbyVisionCompat.SpsRewriteOutcome.Rewritten(dvSps)
            )
        )
        assertEquals(
            "not-needed",
            spsRewriteOutcome(
                skippedForLibdovi = false,
                outcome = DolbyVisionCompat.SpsRewriteOutcome.NoDvExtension
            )
        )
        assertEquals(
            "failed(short-rbsp)",
            spsRewriteOutcome(
                skippedForLibdovi = false,
                outcome = DolbyVisionCompat.SpsRewriteOutcome.Failed("short-rbsp")
            )
        )
        assertEquals(
            "a config that carried no SPS at all is its own answer",
            "absent",
            spsRewriteOutcome(skippedForLibdovi = false, outcome = null)
        )
    }

    // ── behavioral: what the relabel is handed ────────────────────────────

    private fun dvFormat(init: List<ByteArray>) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs("dvhe.08.06")
        .setInitializationData(init)
        .build()

    @Test
    fun `the relabel target carries the rewritten config and reports it`() {
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))

        val target = relabelTargetFor(format)

        assertTrue(
            "the field run reads vpsRewritten off the relabel log, so it has to be true here",
            target.vpsRewritten
        )
        assertFalse(
            "and the format the relabel is built from must carry the rewritten config",
            target.format.initializationData.single()
                .contentEquals(format.initializationData.single())
        )
        assertEquals(
            "the relabel is still built from the track's own declarations",
            "dvhe.08.06",
            target.format.codecs
        )
    }

    @Test
    fun `the relabel target reports the config SPS rewrite beside the VPS one`() {
        // The field log reads both verdicts off RelabelTarget, so the SPS one has
        // to survive the trip out of the rewrite: `spsRewrite=` on the relabel
        // line is the 2026-10-16 capture's own field, and this is what feeds it.
        val format = dvFormat(listOf(lengthDelimited(dvVps, dvSps, pps)))

        val target = relabelTargetFor(format)

        assertTrue(target.vpsRewritten)
        assertEquals(
            "the config SPS the decoder is configured with was rewritten too",
            "applied",
            spsRewriteOutcome(skippedForLibdovi = false, target.spsOutcome)
        )
        assertFalse(
            "and the format handed over carries those rewritten bytes, not the source ones",
            target.format.initializationData.single()
                .contentEquals(format.initializationData.single())
        )
        assertEquals(
            "the advertised string is still the source format's own declaration",
            "dvhe.08.06",
            target.format.codecs
        )
    }

    @Test
    fun `a config that needed no rewrite is handed over as the same format`() {
        val format = dvFormat(listOf(lengthDelimited(sps, pps)))

        val target = relabelTargetFor(format)

        assertFalse("no VPS, nothing rewritten, and the log says so", target.vpsRewritten)
        assertSame("and the very same format goes to the relabel", format, target.format)
    }

    @Test
    fun `a throwing rewrite degrades to the un-rewritten format`() {
        // format() runs inside the retry ladder, where a throw is a dead load
        // rather than a bad picture.
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))

        // Named, not a trailing lambda: the gate parameters are the last ones on
        // purpose (the gate is the call site's own decision), so the rewrite has
        // to be addressed by name.
        val target = relabelTargetFor(
            format,
            rewriteConfig = { throw IllegalStateException("boom") }
        )

        assertFalse(target.vpsRewritten)
        assertSame(format, target.format)
    }

    // ── behavioral: Nuvio parity (2026-10-13 green screen) ───────────────
    //
    // With libdovi stripping the samples, rewriting the codec config's VPS on
    // top of that strip green-screened the field run at 62 kbps — the decoder
    // rendered its first frame and then a solid green field. Nuvio's
    // stripRpuOnly never touches the codec config, so the relabel path hands
    // over the config byte-identical while the library is present, and keeps
    // the rewrite for the Kotlin stripper that needs it (the arm above).

    @Test
    fun `skipVpsRewrite hands the config over byte-identical and reports nothing rewritten`() {
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))
        val init = format.initializationData.single()

        val target = relabelTargetFor(format, skipVpsRewrite = true)

        assertFalse(
            "the field log reads this as skipped(libdovi), so it may not claim a rewrite",
            target.vpsRewritten
        )
        assertSame(
            "the relabel must be built from the source format itself, not a rebuilt copy",
            format,
            target.format
        )
        assertArrayEquals(
            "VPS, SPS and PPS all reach the relabel exactly as they arrived — that is " +
                "the Nuvio shape, and what the green-screen arm was missing",
            init,
            target.format.initializationData.single()
        )
        assertEquals(
            "the relabel still re-advertises the track's own declarations",
            "dvhe.08.06",
            target.format.codecs
        )
    }

    @Test
    fun `skipVpsRewrite never invokes the rewrite at all`() {
        // Not "the rewrite was called and returned the same bytes": the whole
        // point is that the library's strip is the only thing that ran.
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))
        var consulted = false

        val target = relabelTargetFor(
            format,
            rewriteConfig = { consulted = true; DolbyVisionCompat.rewriteRelabelInitData(it) },
            skipVpsRewrite = true
        )

        assertFalse("a skipped rewrite may not even be entered", consulted)
        assertSame(format, target.format)
    }

    @Test
    fun `the log's VPS verdict separates a skipped rewrite from one that was never needed`() {
        // "vpsRewritten=false" alone cannot be read off a field log: a P8 track
        // usually has no VPS extension to rewrite and a contained failure lands
        // on the same value, so the verdict is a separate field.
        assertEquals(
            "skipped(libdovi)",
            vpsRewriteOutcome(skippedForLibdovi = true, rewritten = false)
        )
        assertEquals("applied", vpsRewriteOutcome(skippedForLibdovi = false, rewritten = true))
        assertEquals(
            "not-needed",
            vpsRewriteOutcome(skippedForLibdovi = false, rewritten = false)
        )
        assertEquals(
            "skipping outranks whatever the rewrite would have reported",
            "skipped(libdovi)",
            vpsRewriteOutcome(skippedForLibdovi = true, rewritten = true)
        )
    }

    // ── behavioral: the retired 2026-10-14 no-relabel arm ────────────────
    //
    // The VPS skip landed first (0.6.71) and the field picture was still solid
    // green, which left the `hvc1` re-advertisement itself as the candidate.
    // The no-relabel arm tested that, and field-failed the same night (zero
    // output on a MediaTek DV decoder), so no call site takes it — but the
    // parameter is retained and its behaviour is still pinned here, because
    // [relabelTargetFor] is where that decision lives. The two call sites and
    // the gate they no longer read are pinned by
    // DolbyVisionRelabelNalsStripContractTest.

    @Test
    fun `skipRelabel hands the declared track over untouched and reports the skip`() {
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))

        val target = relabelTargetFor(format, skipRelabel = true)

        assertTrue(
            "the field log reads this as skipped(libdovi-test), so the target has to say so",
            target.relabelSkipped
        )
        assertFalse(
            "and a track left as declared cannot report a config rewrite",
            target.vpsRewritten
        )
        assertSame(
            "the source format itself - declared dvhe.08.06 and all - is what reaches the decoder",
            format,
            target.format
        )
        assertEquals("the declared codec string is the whole point", "dvhe.08.06", target.format.codecs)
        assertSame(
            "and the codec config is the container's own, byte for byte",
            format.initializationData,
            target.format.initializationData
        )
    }

    @Test
    fun `skipRelabel never invokes the rewrite at all`() {
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))
        var consulted = false

        val target = relabelTargetFor(
            format,
            rewriteConfig = { consulted = true; DolbyVisionCompat.rewriteRelabelInitData(it) },
            skipRelabel = true
        )

        assertFalse("a skipped relabel may not even enter the rewrite", consulted)
        assertSame(format, target.format)
    }

    @Test
    fun `skipRelabel outranks skipVpsRewrite so the two gates can never disagree`() {
        // The no-relabel arm is the stronger decision of the pair: leaving the
        // track declared and rewriting its config would be neither arm. No call
        // site passes it any more (the 2026-10-14 test was removed), but the
        // parameter is retained and still outranks skipVpsRewrite.
        val format = dvFormat(listOf(lengthDelimited(dvVps, sps, pps)))

        val target = relabelTargetFor(
            format,
            rewriteConfig = { error("the no-relabel arm may not rewrite anything") },
            skipVpsRewrite = false,
            skipRelabel = true
        )

        assertTrue(target.relabelSkipped)
        assertFalse(target.vpsRewritten)
        assertSame(format, target.format)
    }

    @Test
    fun `the log's relabel verdict names the trial arm`() {
        assertEquals(
            "the 2026-10-14 field run reads this to tell which path ran",
            "skipped(libdovi-test)",
            relabelOutcome(skippedForLibdovi = true)
        )
        assertEquals(
            "and the relabel the app has always shipped is still named applied",
            "applied",
            relabelOutcome(skippedForLibdovi = false)
        )
    }
}
