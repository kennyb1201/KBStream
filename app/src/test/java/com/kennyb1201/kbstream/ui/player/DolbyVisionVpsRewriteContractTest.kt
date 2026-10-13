package com.kennyb1201.kbstream.ui.player

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DolbyVisionCompat.rewriteVpsInSample]: the in-place, sample-level VPS
 * rewrite — DV extension dropped, PTL patched, every other byte of the sample
 * left where it was.
 *
 * This is the primitive the removed `diagVpsRewrite` experiment drove (the
 * "rewrite the VPS to HDR10 while keeping the declared dvhe.08.06 codec
 * string" arm, which the libdovi routing retired along with its toggle). The
 * rewrite itself is unchanged and is pinned here on its own terms: what it may
 * touch is the VPS and only the VPS, because the advertised codec string is
 * derived from the SPS beside it.
 */
class DolbyVisionVpsRewriteContractTest {

    /** A 2-byte NAL header for [type] plus [payload] as the RBSP. */
    private fun nal(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + payload

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
     * The Dolby Vision VPS RBSP the relabel fixtures use: High tier on
     * profile_idc 2 (Main10), zero compatibility flags, level 150, ordering
     * info present and vps_extension_flag set — the extension is the DV
     * signaling, the High tier is what [DolbyVisionCompat.rewriteVpsToHdr10]
     * patches away.
     */
    private val dvVpsRbsp = byteArrayOf(
        0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x22, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x96.toByte(), 0xF0.toByte(), 0x28
    )
    private val dvVps = nal(32, dvVpsRbsp)
    private val sps = nal(
        33,
        byteArrayOf(
            0x01, 0x22, 0x20, 0x00, 0x00, 0x00,
            0xB0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x96.toByte()
        )
    )
    private val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))

    @Test
    fun `an Annex-B sample gets a rewritten VPS and a byte-identical SPS and PPS`() {
        val sample = annexB(dvVps, sps, pps)
        val tail = sample.copyOfRange(4 + dvVps.size, sample.size)
        val rewritten = DolbyVisionCompat.rewriteVpsInSample(
            sample, sample.size, annexB = true, nalLengthFieldLength = 0
        )

        assertNotNull("the VPS had to need the rewrite", rewritten)
        val out = rewritten!!
        // Everything after the VPS arrives byte-identical: the advertised codec
        // string is derived from that SPS, so a run that moved it too would not
        // be attributable to the VPS at all.
        assertArrayEquals(tail, out.copyOfRange(out.size - tail.size, out.size))
        val rbspOut = out.copyOfRange(6, out.size - tail.size)
        assertFalse("the VPS payload itself must change", rbspOut.contentEquals(dvVpsRbsp))
        assertEquals("the start code stays a start code", 1, out[3].toInt())
        assertEquals("the NAL is still a VPS", 32, (out[4].toInt() and 0x7E) ushr 1)
        assertEquals(dvVps[1], out[5])
    }

    @Test
    fun `a length-delimited sample gets a rewritten VPS with its length prefix updated`() {
        // MKV's shape: the 4-byte length in front of the VPS has to describe the
        // rewritten NAL — header included — or the sample desynchronizes from
        // the VPS onward.
        val sample = lengthDelimited(dvVps, sps, pps)
        val oldVpsFrame = 4 + dvVps.size
        val tail = sample.copyOfRange(oldVpsFrame, sample.size)
        val rewritten = DolbyVisionCompat.rewriteVpsInSample(
            sample, sample.size, annexB = false, nalLengthFieldLength = 4
        )

        assertNotNull("the VPS had to need the rewrite", rewritten)
        val out = rewritten!!
        val newVpsLen = readLengthPrefix(out, 0)
        assertEquals(
            "the prefix must cover exactly what follows it",
            out.size - 4 - tail.size,
            newVpsLen
        )
        assertArrayEquals("the SPS and PPS are untouched", tail, out.copyOfRange(4 + newVpsLen, out.size))
        assertEquals("the NAL is still a VPS", 32, (out[4].toInt() and 0x7E) ushr 1)
        assertFalse(
            "and the payload itself changed",
            out.copyOfRange(6, 4 + newVpsLen).contentEquals(dvVpsRbsp)
        )
    }

    @Test
    fun `a sample with no VPS comes back as null and is never touched`() {
        // null is "nothing needed the rewrite", and it has to mean the sample
        // the strip above produced is the sample the caller emits: this runs on
        // every sample of the track.
        val annexBSample = annexB(sps, pps)
        val before = annexBSample.copyOf()
        assertNull(
            DolbyVisionCompat.rewriteVpsInSample(annexBSample, annexBSample.size, true, 0)
        )
        assertArrayEquals("nothing rewritten means nothing touched", before, annexBSample)

        val lengthSample = lengthDelimited(sps, pps)
        val lengthBefore = lengthSample.copyOf()
        assertNull(
            DolbyVisionCompat.rewriteVpsInSample(lengthSample, lengthSample.size, false, 4)
        )
        assertArrayEquals(lengthBefore, lengthSample)
    }
}
