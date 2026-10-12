package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relabel path's VPS rewrite in codec-private data: [DolbyVisionCompat.rewriteVpsInInitData]
 * and the [relabelTargetFor] step that hands its result to the relabel.
 *
 * Field problem: the 6.36 relabel (correct `hvc1.2.4.H150.B0`, RPU dropped,
 * HDR10+ SEI stripped) still stalled with zero decoder output. The codec
 * config still declared the Dolby Vision VPS ("VPS/SPS untouched"), so the
 * decoder configured from a DV-flagged parameter set and then waited for the
 * RPUs the relabel removes. KBStream's VPS rewrite existed, but it only reached
 * the MP4 path's framing, and the container here is the one that stalled — so
 * the rewrite now dispatches on the framing it is actually given
 * (length-delimited, hvcC, Annex-B) and rewrites the VPS alone.
 *
 * The two invariants worth pinning behaviorally are that the SPS/PPS stay
 * byte-identical (the advertised codec string is read from that SPS, and the
 * field result has to be attributable to the VPS) and that every unparseable
 * buffer is returned exactly as it arrived, because this runs inside format
 * negotiation where a guess corrupts the track instead of failing loudly.
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
    fun `the length-delimited VPS is rewritten and re-emitted with its new length`() {
        // MKV's shape: the parameter sets reach the player length-delimited, so
        // an Annex-B-only rewrite leaves the DV VPS in place and the decoder
        // waits for metadata that was stripped.
        val init = lengthDelimited(dvVps, sps, pps)
        val rewritten = DolbyVisionCompat.rewriteVpsInInitData(listOf(init))

        val out = rewritten.single()
        assertFalse("the config must have changed", out.contentEquals(init))
        val tail = lengthDelimited(sps, pps)
        val vpsLen = readLengthPrefix(out, 0)
        assertEquals(
            "the VPS is the first NAL and its prefix must cover exactly what follows it",
            out.size - 4 - tail.size,
            vpsLen
        )
        assertTrue(
            "the rewritten NAL is longer than the original (emulation prevention is " +
                "re-applied to the rewritten RBSP), so a stale length would desync the decoder",
            vpsLen > dvVps.size
        )
        assertArrayEquals(
            "the SPS and the PPS must ride through byte-identical: the advertised codec " +
                "string is read from that SPS",
            tail,
            out.copyOfRange(4 + vpsLen, out.size)
        )
        assertEquals(
            "the NAL header is carried through",
            0x40,
            out[4].toInt() and 0xFF
        )
        val rbsp = out.copyOfRange(6, 6 + (vpsLen - 2))
        assertEquals(
            "the rewritten VPS must declare Main tier Main10 (profile_idc 2, tier_flag 0) — " +
                "the Dolby Vision High tier is what keeps the decoder waiting",
            0x02,
            rbsp[4].toInt() and 0x3F
        )
        assertSame(
            "a second pass must find nothing left to rewrite, which is the clean " +
                "single-layer marker",
            rewritten,
            DolbyVisionCompat.rewriteVpsInInitData(rewritten)
        )
    }

    @Test
    fun `a config with no VPS comes back as the very same list`() {
        val init = listOf(lengthDelimited(sps, pps), ByteArray(0))
        assertSame(
            "nothing rewritten means nothing allocated and nothing re-emitted",
            init,
            DolbyVisionCompat.rewriteVpsInInitData(init)
        )
    }

    @Test
    fun `a truncated length-delimited run is left byte-exact`() {
        // The last NAL's prefix claims more bytes than the buffer holds. A
        // framing rewrite may not guess at that; the original bytes are the
        // only safe answer.
        val truncated = lengthDelimited(dvVps).copyOf(20)
        val init = listOf(truncated)
        assertSame(init, DolbyVisionCompat.rewriteVpsInInitData(init))
    }

    @Test
    fun `an hvcC record has its VPS array rewritten and everything else copied`() {
        val record = hvcCRecord(dvVps, sps)
        val out = DolbyVisionCompat.rewriteVpsInInitData(listOf(record)).single()

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
        assertEquals(
            "the VPS the length describes must be exactly what is left before the SPS array",
            2 + (out.size - 30 - (sps.size + 5)),
            vpsLen
        )
        assertEquals("the NAL header is carried through", 0x40, out[28].toInt() and 0xFF)
        assertArrayEquals(
            "the SPS array must be copied byte for byte",
            byteArrayOf(33, 0, 1, (sps.size shr 8).toByte(), sps.size.toByte()) + sps,
            out.copyOfRange(out.size - (sps.size + 5), out.size)
        )
        assertFalse(
            "and the VPS payload itself must not be the source bytes",
            out.copyOfRange(30, 30 + (vpsLen - 2)).contentEquals(dvVpsRbsp)
        )
    }

    @Test
    fun `the SPS-derived codec string survives the config rewrite`() {
        // The relabel advertises the string it reads from this very SPS, so a
        // config rewrite that moved an SPS bit would advertise a profile the
        // stream no longer is.
        val record = hvcCRecord(dvVps, sps)
        val before = DolbyVisionCompat.hevcCodecFromInitData(listOf(record))
        assertEquals("the field case's own string", "hvc1.2.4.H150.B0", before)

        val rewritten = DolbyVisionCompat.rewriteVpsInInitData(listOf(record))
        assertNotNull(rewritten)
        assertEquals(
            "rewriting the VPS may not change what the SPS says the track is",
            before,
            DolbyVisionCompat.hevcCodecFromInitData(rewritten)
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

        val target = relabelTargetFor(format) { throw IllegalStateException("boom") }

        assertFalse(target.vpsRewritten)
        assertSame(format, target.format)
    }
}
