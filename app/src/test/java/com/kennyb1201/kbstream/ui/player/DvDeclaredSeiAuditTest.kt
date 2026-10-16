package com.kennyb1201.kbstream.ui.player

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared-DV P8 stall's instrumentation and its one bitstream change
 * (2026-10-15, `dvhe.08.06` Strip All on a MediaTek box): the stalled field file
 * carries a suffix SEI (NAL 40) the playing in-band file does not, while the RPU
 * (62) is removed on both.
 *
 * So this file pins three things:
 *  - the SEI payload-type walk and the vendor-only rule it feeds (Fix A's
 *    premise, still the mutating strip's rule),
 *  - that the NALS-strip drops that NAL outright on a declared-DV stream -
 *    2026-10-16 field evidence, after the 20:43 K83 run kept it as
 *    `40:35->kept(seiWalkTrunc)[pt=5]` - and ONLY on a suffix SEI of a
 *    declared-DV track, which is what keeps NAL 40 handling on the in-band path
 *    byte-for-byte what it was,
 *  - that the first-sample audits (Fix B's payload types, Fix C's boundaries,
 *    Fix D's parameter-set bytes) report rather than rewrite,
 *  - that the same suffix-SEI rule is armed on the MUTATING strip (Fix F8, the
 *    2026-10-15 K83 / NORViNE arrival: a declared `dvhe.08.06` track on a box
 *    with no DV decoder) and only for a track whose container declared Dolby
 *    Vision, and
 *  - that the parameter-set audit answers the F2 question - does a DV-flagged
 *    VPS/SPS survive into the bytes the decoder is handed, and so re-arm DV
 *    mode mid-stream?
 *
 * The strip assertions are exact-byte on purpose: a NAL is either in the output
 * or it is not, and a "kept" NAL has to ride through unmodified.
 */
class DvDeclaredSeiAuditTest {

    // ── builders ───────────────────────────────────────────────────────────

    /** An Annex-B NAL: start code, 2-byte header (layerId 0), body. */
    private fun nal(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 0x01) + body

    /** A length-delimited (MP4/fMP4) sample: 4-byte lengths, NALs back to back. */
    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { unit ->
            val len = unit.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            out.write(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    /** One SEI message: payload type, size, body, with 0xFF continuation on both. */
    private fun seiMessage(type: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var t = type
        while (t >= 0xFF) {
            out.write(0xFF)
            t -= 0xFF
        }
        out.write(t)
        var s = body.size
        while (s >= 0xFF) {
            out.write(0xFF)
            s -= 0xFF
        }
        out.write(s)
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    /** A SEI NAL (39/40) carrying [messages] in order. */
    private fun seiNal(type: Int, vararg messages: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        messages.forEach { body.write(it, 0, it.size) }
        return nal(type, body.toByteArray())
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it, 0, it.size) }
        return out.toByteArray()
    }

    /**
     * A NAL for the length-delimited framing: header plus body, NO start code.
     * The length prefix is the boundary there, so an Annex-B NAL handed to it
     * would have its start code read as the NAL header (and its type read as 0).
     */
    private fun ldNal(type: Int, body: ByteArray): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + body

    /**
     * The size the accounting and the audits record for an Annex-B NAL: the
     * payload only (its 2-byte header included, its 4-byte start code not).
     */
    private fun payloadSize(annexB: ByteArray): Int = annexB.size - 4

    /** A payload that parses as a message and is not decoder-relevant (type 5 body). */
    private fun vendorBody(size: Int): ByteArray = ByteArray(size) { 0x5A }

    // ── the walk and the vendor-only rule (Fix A's premise) ────────────────

    @Test
    fun `the walk reads every message's payload type in order`() {
        val rbsp = concat(
            seiMessage(137, vendorBody(3)),
            seiMessage(5, vendorBody(4)),
            seiMessage(144, vendorBody(2))
        )
        val walk = DolbyVisionCompat.HevcSeiAudit.walk(rbsp, 0, rbsp.size)

        assertTrue("a fully parsed NAL completes its walk", walk.complete)
        assertEquals(listOf(137, 5, 144), walk.types)
    }

    @Test
    fun `an extended payload type accumulates across the 0xFF continuation`() {
        // 255 + 45 = 300: the header is written as 0xFF 0x2D, and the parser has
        // to add the continuation byte rather than read it as the type.
        val rbsp = seiMessage(300, vendorBody(2))
        assertEquals(0xFF.toByte(), rbsp[0])
        assertEquals(0x2D.toByte(), rbsp[1])

        val walk = DolbyVisionCompat.HevcSeiAudit.walk(rbsp, 0, rbsp.size)

        assertTrue(walk.complete)
        assertEquals(listOf(300), walk.types)
    }

    @Test
    fun `a truncated message makes the walk incomplete instead of guessing`() {
        // Type 5, size 32, one byte of body: the parse cannot finish, and an
        // unfinished parse must never decide a drop.
        val rbsp = byteArrayOf(0x05, 0x20, 0x5A)
        val walk = DolbyVisionCompat.HevcSeiAudit.walk(rbsp, 0, rbsp.size)

        assertFalse("the walk has to know it did not finish", walk.complete)
        assertFalse(
            "and an incomplete walk is not vendor-only, whatever it saw first",
            DolbyVisionCompat.HevcSeiAudit.isVendorOnly(walk.types)
        )
    }

    @Test
    fun `only a NAL with no decoder-relevant message is vendor only`() {
        val audit = DolbyVisionCompat.HevcSeiAudit

        assertTrue("user-data unregistered is where a vendor message rides", audit.isVendorOnly(listOf(5)))
        assertTrue("registered ITU-T T35 too", audit.isVendorOnly(listOf(4)))
        assertTrue("and any type outside the set", audit.isVendorOnly(listOf(5, 203)))
        assertFalse("mastering display is read by the decoder", audit.isVendorOnly(listOf(137)))
        assertFalse("film grain changes the picture", audit.isVendorOnly(listOf(19)))
        assertFalse("and one benign message in a NAL keeps the whole NAL", audit.isVendorOnly(listOf(5, 144)))
        assertFalse("an empty parse decides nothing", audit.isVendorOnly(emptyList()))
    }

    @Test
    fun `the vendor type list names exactly the types outside the decoder set`() {
        val audit = DolbyVisionCompat.HevcSeiAudit

        assertEquals("5,203", audit.vendorTypes(listOf(137, 5, 203, 144)))
        assertEquals("none", audit.vendorTypes(listOf(137, 144)))
        assertEquals("none", audit.vendorTypes(emptyList()))
        assertEquals("137,144", audit.describe(listOf(137, 144)))
        assertEquals("none", audit.describe(emptyList()))
    }

    @Test
    fun `hex caps the dump and reports how much was left`() {
        val audit = DolbyVisionCompat.HevcSeiAudit
        val bytes = ByteArray(80) { 0xAB.toByte() }

        assertEquals("ABABAB", audit.hex(bytes, 0, 3))
        val capped = audit.hex(bytes, 0, 80, cap = 4)
        assertTrue("the cap has to say so: $capped", capped == "ABABABAB+76")
        assertEquals("-", audit.hex(bytes, 0, 0))
    }

    // ── Fix A: the declared-DV suffix SEI ──────────────────────────────────

    private val vps = nal(32, ByteArray(18) { 0x11 })
    private val sps = nal(33, ByteArray(11) { 0x22 })
    private val pps = nal(34, ByteArray(4) { 0x33 })
    private val prefSei = seiNal(39, seiMessage(137, vendorBody(6)))
    private val idr = nal(19, ByteArray(2048) { 0x44 })
    private val sliceBody = ByteArray(9) { 0x55 }
    private val vendorSuffix = seiNal(40, seiMessage(5, vendorBody(31)))
    private val rpu = nal(62, ByteArray(60) { 0x66 })

    // The same pieces in the length-delimited framing (see [ldNal]).
    private val ldVps = ldNal(32, ByteArray(18) { 0x11 })
    private val ldSps = ldNal(33, ByteArray(11) { 0x22 })
    private val ldPps = ldNal(34, ByteArray(4) { 0x33 })
    private val ldPrefSei = ldNal(39, seiMessage(137, vendorBody(6)))
    private val ldIdr = ldNal(19, ByteArray(2048) { 0x44 })
    private val ldVendorSuffix = ldNal(40, seiMessage(5, vendorBody(31)))
    private val ldRpu = ldNal(62, ByteArray(60) { 0x66 })

    @Test
    fun `a declared-DV suffix SEI with only vendor data is dropped`() {
        // The rule does not read the messages any more, but the payload types it
        // prints are still what the field side compares between files.
        val sample = concat(vps, sps, pps, prefSei, idr, vendorSuffix, rpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true
        )

        // The RPU and the vendor-only suffix SEI go; everything else rides through.
        val expected = concat(vps, sps, pps, prefSei, idr)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertTrue(
            "the log has to attribute this removal: ${accounting.summary()}",
            accounting.summary().contains(
                "40:${payloadSize(vendorSuffix)}->dropped(" +
                    "${DolbyVisionCompat.HevcSeiAudit.DROP_REASON_DECLARED_DV_SUFFIX})[pt=5]"
            )
        )
    }

    @Test
    fun `a declared-DV suffix SEI goes even when it carries the mastering display`() {
        // The field fix, spelled out: the drop is the RPU's, not an audit's, so
        // the message content cannot save the NAL. A decoder that needs static
        // metadata gets it from the format (hdrStaticInfo), not from a NAL that
        // sits after the slice data.
        val mastering = seiNal(40, seiMessage(137, vendorBody(20)))
        val sample = concat(vps, sps, pps, prefSei, idr, mastering, rpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true
        )

        // Both the RPU and the suffix SEI go; the prefix SEI rides through.
        val expected = concat(vps, sps, pps, prefSei, idr)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertTrue(
            "and the log still names what it was carrying: ${accounting.summary()}",
            accounting.summary().contains(
                "40:${payloadSize(mastering)}->dropped(" +
                    "${DolbyVisionCompat.HevcSeiAudit.DROP_REASON_DECLARED_DV_SUFFIX})[pt=137]"
            )
        )
    }

    @Test
    fun `an in-band sample keeps the same suffix SEI byte for byte`() {
        // declaredDv defaults to false: this is the working in-band path, and the
        // rule must not be able to reach it.
        val sample = concat(vps, sps, pps, prefSei, idr, vendorSuffix, rpu)

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true
        )

        val expected = concat(vps, sps, pps, prefSei, idr, vendorSuffix)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
    }

    @Test
    fun `a truncated suffix SEI is dropped, which is the 20-43 K83 fix`() {
        // Type 5, size 32, one byte of body: the walk cannot finish. Under the
        // 2026-10-15 audit this NAL stayed (`40:35->kept(seiWalkTrunc)[pt=5]` in
        // the field log) and the file kept buffering at 0-1 kbps; a truncated
        // walk must no longer save the one NAL that distinguishes the stalled
        // file from the playing one.
        val truncated = seiNal(40, byteArrayOf(0x05, 0x20, 0x5A))
        val sample = concat(vps, sps, pps, idr, truncated, rpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true
        )

        val expected = concat(vps, sps, pps, idr)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertTrue(
            "and the field line reads `dropped` with no walk qualifier: ${accounting.summary()}",
            accounting.summary().contains(
                "40:${payloadSize(truncated)}->dropped(" +
                    "${DolbyVisionCompat.HevcSeiAudit.DROP_REASON_DECLARED_DV_SUFFIX})"
            )
        )
    }

    @Test
    fun `the prefix SEI is never dropped by the suffix rule`() {
        // NAL 39 with vendor-only data, on a declared-DV stream: the rule is
        // about the suffix SEI, and a prefix SEI can carry a decoder-relevant
        // message after the vendor one.
        val vendorPrefix = seiNal(39, seiMessage(5, vendorBody(10)))
        val sample = concat(vps, sps, pps, vendorPrefix, idr, rpu)

        val newLen = DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            declaredDv = true
        )

        val expected = concat(vps, sps, pps, vendorPrefix, idr)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
    }

    @Test
    fun `the length-delimited framing follows the same rule`() {
        val plain = lengthDelimited(ldVps, ldSps, ldPps, ldPrefSei, ldIdr, ldRpu)
        val withVendorSei = lengthDelimited(ldVps, ldSps, ldPps, ldPrefSei, ldIdr, ldVendorSuffix, ldRpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        val droppedBuf = withVendorSei.copyOf()
        val dropped = DolbyVisionCompat.stripLengthDelimited(
            droppedBuf, droppedBuf.size, 4,
            stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true
        )
        val inBandBuf = withVendorSei.copyOf()
        val inBand = DolbyVisionCompat.stripLengthDelimited(
            inBandBuf, inBandBuf.size, 4,
            stripDv = true, stripHdr10Plus = true
        )

        // The RPU goes on both arms; only the declared-DV one also loses the
        // vendor-only suffix SEI, which is the whole rule.
        val expectedDropped = lengthDelimited(ldVps, ldSps, ldPps, ldPrefSei, ldIdr)
        val expectedInBand = lengthDelimited(ldVps, ldSps, ldPps, ldPrefSei, ldIdr, ldVendorSuffix)
        assertEquals(
            "the same sample with the SEI and the RPU gone",
            plain.size - 4 - ldRpu.size,
            dropped
        )
        assertEquals(expectedDropped.size, dropped)
        assertArrayEquals(expectedDropped, droppedBuf.copyOf(dropped))
        assertEquals(
            "and the in-band arm keeps the SEI, as it did before the rule existed",
            plain.size + 4 + ldVendorSuffix.size - 4 - ldRpu.size,
            inBand
        )
        assertArrayEquals(expectedInBand, inBandBuf.copyOf(inBand))
        assertTrue(
            accounting.summary().contains(
                "40:${ldVendorSuffix.size}->dropped(" +
                    "${DolbyVisionCompat.HevcSeiAudit.DROP_REASON_DECLARED_DV_SUFFIX})[pt=5]"
            )
        )
    }

    @Test
    fun `the accounting prints the payload types of a NAL it kept`() {
        val sample = concat(vps, idr, prefSei, rpu)
        val accounting = DolbyVisionCompat.SeiStripAccounting()

        DolbyVisionCompat.stripAnnexB(
            sample, sample.size, stripDv = true, stripHdr10Plus = true,
            accounting = accounting, declaredDv = true
        )

        assertTrue(
            "the field needs the types a file carries, not just the verdicts: " +
                accounting.summary(),
            accounting.summary().contains("39:${payloadSize(prefSei)}->kept[pt=137]")
        )
    }

    // ── Fix B: the SEI inventory the field compares ────────────────────────

    @Test
    fun `the SEI inventory reports the types and the suspect set`() {
        val twoUp = seiNal(39, seiMessage(137, vendorBody(2)), seiMessage(144, vendorBody(2)))
        val sample = concat(vps, twoUp, idr, vendorSuffix)

        val line = DolbyVisionCompat.seiPayloadInventory(sample, sample.size, annexB = true, nalLengthFieldLength = 4)

        assertTrue("every SEI NAL is listed: $line", line.contains("39:[137,144]"))
        assertTrue("including the suffix one: $line", line.contains("40:[5]"))
        assertTrue(
            "and the comparison quantity is the set of types no decoder reads: $line",
            line.contains("suspect=5")
        )
        assertTrue(
            "and the suffix SEI's actual bytes ride along (Fix A step 1), payload from its " +
                "2-byte header: $line",
            line.contains("suffixHex=5001051F")
        )
    }

    @Test
    fun `the suffix SEI byte dump is capped and says how much was left`() {
        val big = seiNal(40, seiMessage(5, vendorBody(150)))
        val sample = concat(vps, idr, big)

        val line = DolbyVisionCompat.seiPayloadInventory(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4
        )

        // 2 header + 1 type + 1 size + 150 body = 154 bytes, 96 of them dumped.
        assertTrue("the dump starts at the NAL header: $line", line.contains("suffixHex=50010596"))
        assertTrue("and the cap is declared, not silent: $line", line.contains("+58"))
    }

    @Test
    fun `the SEI inventory says none when a sample carries no SEI`() {
        val sample = concat(vps, sps, idr)
        val line = DolbyVisionCompat.seiPayloadInventory(sample, sample.size, annexB = true, nalLengthFieldLength = 4)
        assertTrue(line, line.contains("sei=none"))
        assertTrue(line, line.contains("suspect=none"))
        assertTrue(line, line.contains("suffixHex=none"))
    }

    // ── Fix C: the boundary audit ──────────────────────────────────────────

    @Test
    fun `the boundary audit reports offsets, sizes and a short picture NAL`() {
        val small = nal(19, ByteArray(100) { 0x44 })
        val sample = concat(nal(35, byteArrayOf(1)), prefSei, small)

        val line = DolbyVisionCompat.auditNalBounds(sample, sample.size, annexB = true, nalLengthFieldLength = 4)

        assertTrue("the framing is named: $line", line.contains("framing=annexb"))
        assertTrue("every NAL is counted: $line", line.contains("count=3"))
        assertTrue("with its offset and size: $line", line.contains("35@0+3"))
        assertTrue("including the SEI's: $line", line.contains("39@7+${payloadSize(prefSei)}"))
        assertTrue(
            "and a picture NAL a 4K frame cannot be is called out: $line",
            line.contains("shortPicture=19:102")
        )
    }

    @Test
    fun `the boundary audit reports no short picture when the IDR is a real frame`() {
        val sample = concat(vps, idr)
        val line = DolbyVisionCompat.auditNalBounds(sample, sample.size, annexB = true, nalLengthFieldLength = 4)
        assertTrue(line, line.contains("shortPicture=none"))
    }

    @Test
    fun `the boundary audit calls out a malformed length-delimited sample`() {
        // A length prefix longer than what is left: the answer to "is the picture
        // NAL a truncation?" has to be in the line itself.
        val malformed = byteArrayOf(0, 0, 1, 0) + ByteArray(6) { 0x44 }

        val line = DolbyVisionCompat.auditNalBounds(malformed, malformed.size, annexB = false, nalLengthFieldLength = 4)
        assertTrue(line, line.contains("MALFORMED"))
        assertTrue(line, line.contains("consumed=0/10"))

        val good = lengthDelimited(ldNal(32, ByteArray(18) { 0x11 }), ldNal(19, ByteArray(2048) { 0x44 }))
        val ok = DolbyVisionCompat.auditNalBounds(good, good.size, annexB = false, nalLengthFieldLength = 4)
        assertTrue(ok, ok.contains("framingOk"))
        assertTrue(ok, ok.contains("count=2"))
    }

    @Test
    fun `the boundary audit changes no byte of the sample`() {
        val sample = concat(vps, sps, prefSei, idr)
        val copy = sample.copyOf()

        DolbyVisionCompat.auditNalBounds(sample, sample.size, annexB = true, nalLengthFieldLength = 4)
        DolbyVisionCompat.seiPayloadInventory(sample, sample.size, annexB = true, nalLengthFieldLength = 4)
        DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = true
        )

        assertArrayEquals("the audits only read", copy, sample)
    }

    // ── Fix D: the parameter-set audit ─────────────────────────────────────

    /** The Main10 High tier / DV-extension VPS the other suites use. */
    private val dvVpsRbsp = byteArrayOf(
        0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x22, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x96.toByte(), 0xF0.toByte(), 0x28
    )

    @Test
    fun `the parameter-set audit reports the VPS rewrite outcome and its bytes`() {
        val dvVps = nal(32, dvVpsRbsp)
        val sps33 = nal(33, byteArrayOf(0x01, 0x22, 0x20, 0x00, 0x00, 0xB0.toByte(), 0x00, 0x00, 0x96.toByte()))
        val sample = concat(dvVps, sps33, idr)

        val line = DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = false
        )

        assertTrue("the mode is named, so a difference is not misread: $line", line.contains("applied=false"))
        assertTrue(
            "the source VPS is dumped, header and all: $line",
            line.contains("vps=${payloadSize(dvVps)}:40010C01FFFF")
        )
        assertTrue(
            "and what the HDR10 rewrite makes of it, with its bytes: $line",
            line.contains("vpsRewrite=rewritten(") && line.contains(":") && !line.contains("vpsRewrite=failed")
        )
        assertTrue(
            "the SPS is audited too: $line",
            line.contains("sps=${payloadSize(sps33)}:") && line.contains("spsRewrite=")
        )
    }

    @Test
    fun `the parameter-set audit says when a sample carries no parameter sets`() {
        val sample = concat(idr, rpu)
        val line = DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = true
        )
        assertTrue(line, line.contains("applied=true"))
        assertTrue(line, line.contains("vps=absent"))
        assertTrue(line, line.contains("sps=absent"))
    }

    // ── Fix F8: the same rule on the mutating strip ────────────────────────
    //
    // The mutating strip is where a DECLARED `dvhe.08.06` track lands on a box
    // with no Dolby Vision decoder - the K83 / NORViNE stall - and it is the one
    // strip that rewrites the in-sample parameter sets, which is exactly why the
    // audit's NAL-40 rule was left off it. These cases pin that the rule now runs
    // there for a declared-DV track and still cannot reach an in-band one.

    @Test
    fun `the mutating strip drops a declared-DV vendor-only suffix SEI`() {
        val sample = concat(idr, prefSei, vendorSuffix, rpu)
        val stats = DolbyVisionCompat.StripStats()

        val newLen = DolbyVisionCompat.transformAnnexB(
            sample, sample.size, stripHdr10Plus = true, stats = stats, declaredDv = true
        )

        // The vendor-only suffix SEI goes the way of the RPU; the prefix SEI
        // (which carries the mastering-display type) and the picture NAL stay.
        val expected = concat(idr, prefSei)
        assertTrue("something must have been removed", newLen >= 0)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertEquals("the field line has to be able to attribute it", 1, stats.dvVendorSeiNals)
        assertEquals(payloadSize(vendorSuffix), stats.dvVendorSeiBytes)
    }

    @Test
    fun `the mutating strip keeps the same suffix SEI on the in-band path`() {
        // declaredDv defaults to false: this is the path whose NAL 40 handling
        // the rule must not change at all.
        val sample = concat(idr, vendorSuffix, rpu)
        val stats = DolbyVisionCompat.StripStats()

        val newLen = DolbyVisionCompat.transformAnnexB(
            sample, sample.size, stripHdr10Plus = true, stats = stats
        )

        val expected = concat(idr, vendorSuffix)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertEquals("and nothing was attributed to a rule that never ran", 0, stats.dvVendorSeiNals)
    }

    @Test
    fun `a decoder-relevant suffix SEI survives the mutating strip`() {
        val mastering = seiNal(40, seiMessage(137, vendorBody(20)))
        val sample = concat(idr, mastering, rpu)
        val stats = DolbyVisionCompat.StripStats()

        val newLen = DolbyVisionCompat.transformAnnexB(
            sample, sample.size, stripHdr10Plus = true, stats = stats, declaredDv = true
        )

        val expected = concat(idr, mastering)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertEquals(0, stats.dvVendorSeiNals)
    }

    @Test
    fun `a truncated suffix SEI is never dropped by the mutating strip`() {
        val truncated = seiNal(40, byteArrayOf(0x05, 0x20, 0x5A))
        val sample = concat(idr, truncated, rpu)
        val stats = DolbyVisionCompat.StripStats()

        val newLen = DolbyVisionCompat.transformAnnexB(
            sample, sample.size, stripHdr10Plus = true, stats = stats, declaredDv = true
        )

        val expected = concat(idr, truncated)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
        assertEquals("an unfinished walk decides nothing", 0, stats.dvVendorSeiNals)
    }

    @Test
    fun `the mutating strip never drops a prefix SEI by the suffix rule`() {
        val vendorPrefix = seiNal(39, seiMessage(5, vendorBody(10)))
        val sample = concat(idr, vendorPrefix, rpu)

        val newLen = DolbyVisionCompat.transformAnnexB(
            sample, sample.size, stripHdr10Plus = true, stats = null, declaredDv = true
        )

        val expected = concat(idr, vendorPrefix)
        assertEquals(expected.size, newLen)
        assertArrayEquals(expected, sample.copyOf(newLen))
    }

    @Test
    fun `the mutating strip follows the same rule in the length-delimited framing`() {
        val withVendorSei = lengthDelimited(ldIdr, ldVendorSuffix, ldRpu)
        val declaredBuf = withVendorSei.copyOf()
        val stats = DolbyVisionCompat.StripStats()

        val dropped = DolbyVisionCompat.transformLengthDelimited(
            declaredBuf, declaredBuf.size, 4,
            stripHdr10Plus = true, stats = stats, declaredDv = true
        )

        val expectedDropped = lengthDelimited(ldIdr)
        assertEquals(expectedDropped.size, dropped)
        assertArrayEquals(expectedDropped, declaredBuf.copyOf(dropped))
        assertEquals(1, stats.dvVendorSeiNals)
        assertEquals(ldVendorSuffix.size, stats.dvVendorSeiBytes)

        val inBandBuf = withVendorSei.copyOf()
        val inBand = DolbyVisionCompat.transformLengthDelimited(
            inBandBuf, inBandBuf.size, 4, stripHdr10Plus = true, stats = null
        )

        val expectedInBand = lengthDelimited(ldIdr, ldVendorSuffix)
        assertEquals("the in-band arm keeps the SEI, as it did before the rule existed", expectedInBand.size, inBand)
        assertArrayEquals(expectedInBand, inBandBuf.copyOf(inBand))
    }

    // ── Fix F2: does a DV-flagged set survive into the decoder's bytes? ────

    @Test
    fun `a set the mutating strip rewrites away is not a survivor`() {
        val sample = concat(nal(32, dvVpsRbsp), idr)

        val line = DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = true
        )

        assertTrue("the set is counted: $line", line.contains("inSampleSets=1"))
        assertTrue(
            "applied=true means the rewrite is written back, so no DV extension is left: $line",
            line.contains("dvFlaggedSurviving=0")
        )
        assertTrue("and the verdict is that nothing can re-arm: $line", line.contains("reArm=no"))
        assertTrue(line, line.contains("survivors=none"))
    }

    @Test
    fun `the same set survives when the mode does not write the rewrite back`() {
        // applied=false is the relabel modes' shape (their codec config carries
        // the rewrite, the samples do not): the in-sample set keeps the DV
        // extension, which is what F2 has to be able to say.
        val sample = concat(nal(32, dvVpsRbsp), idr)

        val line = DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = false
        )

        assertTrue(line, line.contains("inSampleSets=1"))
        assertTrue("the set still describes Dolby Vision: $line", line.contains("dvFlaggedSurviving=1"))
        assertTrue("so a DV decoder can re-arm mid-stream: $line", line.contains("reArm=yes"))
        assertTrue(
            "and the line says which set and why: $line",
            line.contains("survivors=vps:rewriteLeftInSample")
        )
    }

    @Test
    fun `a set the rewrite cannot touch survives whatever the mode does`() {
        // 3 bytes: no profile_tier_level to rewrite, so the set is handed to the
        // decoder as it stands on either arm - the F2 smoking gun.
        val sample = concat(nal(32, byteArrayOf(0x11)), idr)

        listOf(true, false).forEach { applied ->
            val line = DolbyVisionCompat.auditParameterSets(
                sample, sample.size, annexB = true, nalLengthFieldLength = 4,
                rewriteApplied = applied
            )
            assertTrue(line, line.contains("inSampleSets=1"))
            assertTrue(line, line.contains("dvFlaggedSurviving=1"))
            assertTrue(line, line.contains("reArm=yes"))
            assertTrue(line, line.contains("survivors=vps:rewriteFailed"))
        }
    }

    @Test
    fun `every in-sample set is counted, not just the first`() {
        // A repeat (a mid-stream re-init) carries a second VPS; the audit's byte
        // dump covers the first one, but the survival count has to cover both.
        val sample = concat(nal(32, dvVpsRbsp), idr, nal(32, dvVpsRbsp))

        val applied = DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = true
        )
        assertTrue(applied, applied.contains("inSampleSets=2"))
        assertTrue(applied, applied.contains("dvFlaggedSurviving=0"))

        val skipped = DolbyVisionCompat.auditParameterSets(
            sample, sample.size, annexB = true, nalLengthFieldLength = 4, rewriteApplied = false
        )
        assertTrue(skipped, skipped.contains("inSampleSets=2"))
        assertTrue(skipped, skipped.contains("dvFlaggedSurviving=2"))
        assertEquals(
            "both sets are named, in sample order",
            "vps:rewriteLeftInSample,vps:rewriteLeftInSample",
            skipped.substringAfter("survivors=").trim()
        )
    }
}
