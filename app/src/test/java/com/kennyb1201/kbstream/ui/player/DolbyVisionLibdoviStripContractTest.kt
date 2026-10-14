package com.kennyb1201.kbstream.ui.player

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DolbyVisionCompat.stripAnnexB] and [DolbyVisionCompat.stripLengthDelimited]
 * hand the whole sample to [DoviBridge.stripSample] first, and the Kotlin NAL
 * parser below them is the fallback for a build without the `.so` (every local
 * build, and any CI run where the native step failed). Strip All is the
 * exception: it passes `kotlinOnly = true` and never reaches the bridge, because
 * libdovi's HDR10+ strip is coarse — it drops a whole SEI NAL — and that NAL
 * carried HDR10 static metadata on the 2026-10-14 field file (Nuvio's stripper
 * never touches it). Every other caller keeps the native strip.
 *
 * Two things are pinned here. First the routing, including Strip All's opt-out,
 * because a change that quietly dropped the native strip for the other callers
 * would be invisible in a JVM test and costly in the field. Second the fallback
 * itself: with no library in this process, which is exactly the state a local
 * build is in, both functions have to behave precisely as they did before the
 * routing existed, including the -1 that means "nothing was removed".
 *
 * `DoviBridge.available` is false in a JVM unit test by construction (there is
 * no Android device and no `.so`), so the behavioral tests below exercise the
 * fallback path and nothing else; what libdovi itself does is the C side's
 * contract (see dovi_bridge.c) and the field run's.
 */
class DolbyVisionLibdoviStripContractTest {

    // ── the routing ───────────────────────────────────────────────────────

    private val compat: String by lazy { read(COMPAT) }
    private val extractor: String by lazy { read(EXTRACTOR) }

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

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    /**
     * [source] with every whitespace character removed, so a call's shape can be
     * pinned without depending on how it is indented or wrapped.
     */
    private fun flat(source: String): String = source.filterNot { it.isWhitespace() }

    @Test
    fun `both strip functions hand the sample to libdovi first`() {
        // One call site per framing, and the framing each one declares has to
        // match the shape the caller handed it.
        assertEquals(
            "one libdovi strip per strip function",
            2,
            occurrences(compat, "DoviBridge.stripSample(")
        )
        val flat = flat(compat)
        assertTrue(
            "the Annex-B strip asks for the Annex-B walk",
            flat.contains(
                "DoviBridge.stripSample(buf,length,DoviBridge.NAL_FORMAT_ANNEX_B,0," +
                    "stripDv,stripHdr10Plus)"
            )
        )
        assertTrue(
            "and the length-delimited one for the length-delimited walk",
            flat.contains(
                "DoviBridge.stripSample(buf,length,DoviBridge.NAL_FORMAT_LENGTH_DELIMITED," +
                    "nalLengthFieldLength,stripDv,stripHdr10Plus)"
            )
        )
    }

    @Test
    fun `the RPU-only arm stays on the Kotlin parser`() {
        // libdovi has no RPU-only strip — its strip_dovi_rpu takes the
        // enhancement layer with the RPU — so the dropRpuNal callers must never
        // reach it: the gate has to appear on both entry points, and the Kotlin
        // body has to still be there behind it.
        assertEquals(
            "both strip functions gate the native path on dropRpuNal and kotlinOnly",
            2,
            occurrences(compat, "if (!dropRpuNal && !kotlinOnly) {")
        )
        val annexB = compat.substringAfter("fun stripAnnexB(")
            .substringBefore("fun stripLengthDelimited(")
        val gateAt = annexB.indexOf("if (!dropRpuNal && !kotlinOnly) {")
        val kotlinScanAt = annexB.indexOf("if (length < 5) return -1")
        assertTrue("the gate not found in stripAnnexB", gateAt >= 0)
        assertTrue(
            "the Kotlin scan must still be the fallback below the native attempt",
            kotlinScanAt > gateAt
        )
        val lengthDelimited = compat.substringAfter("fun stripLengthDelimited(")
            .substringBefore("private fun recordNativeStripAccounting(")
        assertTrue(
            "the length-delimited fallback is still there too",
            lengthDelimited.indexOf("val fieldLen = nalLengthFieldLength.coerceIn(1, 4)") >
                lengthDelimited.indexOf("if (!dropRpuNal && !kotlinOnly) {")
        )
        assertTrue(
            "and the surgical SEI parser the fallback uses is untouched",
            compat.contains("private fun stripHdr10PlusMessagesInPlace(")
        )
    }

    @Test
    fun `a sample libdovi returned whole is reported as unchanged`() {
        // -1 is this function's "nothing was removed", and the sniff path acts
        // on it (it locks its strip mode and logs "HDR10+ SEI detected" off a
        // non-negative answer). Passing back the input length instead would make
        // every clean sample of a non-DV track look stripped.
        val nativeResult = compat.substringAfter("let { stripped ->")
            .substringBefore("System.arraycopy(stripped, 0, buf, 0, stripped.size)")
        assertTrue(
            "the unchanged case has to be turned into the Kotlin answer, not the native one",
            nativeResult.contains("if (stripped.size == length) return -1")
        )
    }

    @Test
    fun `the native accounting records the existing categories and marks them`() {
        val helper = compat.substringAfter("private fun recordNativeStripAccounting(")
            .substringBefore("internal class StripStats")
        assertTrue(
            "the object has to say libdovi produced these bytes: its entries are categories, not NAL fates",
            helper.contains("accounting.viaLibdovi = true")
        )
        listOf("NAL_DV_RPU", "NAL_DV_EL", "NAL_PREFIX_SEI").forEach { type ->
            assertTrue(
                "$type is the category the Kotlin path records for the same NAL",
                helper.contains("accounting.record($type, 0, SeiStripAccounting.Action.DROPPED)")
            )
        }
        assertFalse(
            "no invented categories: the same three actions, and only DROPPED",
            helper.contains("Action.KEPT") || helper.contains("Action.SHRUNK")
        )
    }

    @Test
    fun `the Strip All handler still asks for both removals and names the stripper`() {
        val handler = extractor.substringAfter("if (mode == Mode.STRIP_NALS_ONLY) {")
            .substringBefore("val stats = DolbyVisionCompat.StripStats()")
        assertEquals(
            "both framing branches drop the RPU/EL",
            2,
            occurrences(handler, "stripDv = true,")
        )
        assertTrue("and both drop the HDR10+ SEI", handler.contains("stripHdr10Plus = true"))
        // Strip All opts out of the native strip on both framings: libdovi's
        // HDR10+ strip is coarse and dropped the HDR10 static metadata with the
        // whole SEI NAL on the 2026-10-14 field file. The Kotlin surgery keeps
        // the NAL and removes only the HDR10+ message.
        assertEquals(
            "both framing branches run the Kotlin surgery, not the library",
            2,
            occurrences(handler, "kotlinOnly = true,")
        )
        // Which stripper produced the bytes: the native path's entries are
        // categories with no sizes, so the log says so; the Kotlin arm prints
        // its own marker so the field run can grep for it.
        assertTrue(
            "the first-sample line has to consult the marker",
            flat(handler).contains("accounting?.viaLibdovi==true")
        )
        assertTrue(
            "and print it",
            flat(handler).contains("(vialibdovi)")
        )
        assertTrue(
            "with the Kotlin arm's own label, which Strip All now takes",
            flat(handler).contains("(viakotlin)")
        )
        assertTrue(
            "the field run greps this label for what was removed",
            extractor.contains("NALS-strip: first sample")
        )
        assertFalse(
            "with no parameter-set rewrite here, which is what stalls MTK-class decoders",
            handler.contains("rewriteInitData")
        )
    }

    // ── the fallback, byte for byte ───────────────────────────────────────

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

    private val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
    private val sps = nal(33, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
    private val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))
    private val rpu = nal(62, byteArrayOf(0x11, 0x22, 0x33, 0x44))
    private val el = nal(63, byteArrayOf(0x0A, 0x0B, 0x0C))

    /** An HDR10+ (ST 2094-40) prefix SEI NAL: payload type 4, T.35 country 0xB5. */
    private val hdr10PlusSei =
        nal(39, byteArrayOf(0x04, 0x06, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04))
    private val vcl = nal(19, byteArrayOf(0x55, 0x66, 0x77, 0x88.toByte()))

    @Test
    fun `with no library in the build the Kotlin strip is what runs, byte for byte`() {
        // The Strip All removal the routing could have taken over, on one
        // Annex-B access unit: the RPU (62) and the enhancement layer (63) go,
        // and the parameter sets, the HDR10+ SEI and the slice stay bit-exact —
        // a NAL 39 is never dropped for DV (Nuvio's stripper, and the
        // 2026-10-14 field file's hdrStaticInfo=absent).
        val au = annexB(vps, sps, pps, rpu, el, hdr10PlusSei, vcl)
        val buf = au.copyOf()
        val newLen = DolbyVisionCompat.stripAnnexB(
            buf, au.size, stripDv = true, stripHdr10Plus = true
        )

        val expected = annexB(vps, sps, pps, hdr10PlusSei, vcl)
        assertTrue("something had to be removed", newLen >= 0)
        assertArrayEquals("the Kotlin strip's bytes are unchanged", expected, buf.copyOf(newLen))
    }

    @Test
    fun `the two entry paths agree with the library absent`() {
        // dropRpuNal = true never consults the bridge, so it is the Kotlin
        // parser's own answer; dropRpuNal = false consults it, is declined, and
        // must land on identical bytes. One fixture with no EL, so both shapes
        // mean the same removal (drop the RPU, keep everything else).
        val au = annexB(vps, sps, pps, rpu, hdr10PlusSei, vcl)
        val bridged = au.copyOf()
        val kotlinOnly = au.copyOf()

        val bridgedLen = DolbyVisionCompat.stripAnnexB(
            bridged, au.size, stripDv = true, stripHdr10Plus = false
        )
        val kotlinLen = DolbyVisionCompat.stripAnnexB(
            kotlinOnly, au.size, stripDv = true, stripHdr10Plus = false, dropRpuNal = true
        )

        assertEquals("the fallback must report what the Kotlin path reports", kotlinLen, bridgedLen)
        assertArrayEquals("and produce its exact bytes", kotlinOnly, bridged)
        val expected = annexB(vps, sps, pps, hdr10PlusSei, vcl)
        assertArrayEquals(expected, bridged.copyOf(bridgedLen))
    }

    @Test
    fun `the length-delimited fallback agrees the same way`() {
        val sample = lengthDelimited(vps, sps, pps, rpu, hdr10PlusSei, vcl)
        val bridged = sample.copyOf()
        val kotlinOnly = sample.copyOf()

        val bridgedLen = DolbyVisionCompat.stripLengthDelimited(
            bridged, sample.size, 4, stripDv = true, stripHdr10Plus = false
        )
        val kotlinLen = DolbyVisionCompat.stripLengthDelimited(
            kotlinOnly, sample.size, 4, stripDv = true, stripHdr10Plus = false, dropRpuNal = true
        )

        assertEquals(kotlinLen, bridgedLen)
        assertArrayEquals(kotlinOnly, bridged)
        // The length prefixes of the kept NALs have to still describe them, or
        // the sample desynchronizes from the first one on.
        val expected = lengthDelimited(vps, sps, pps, hdr10PlusSei, vcl)
        assertArrayEquals(expected, bridged.copyOf(bridgedLen))
    }

    @Test
    fun `nothing to remove is still an unchanged -1, with the buffer untouched`() {
        // The sniff path turns a non-negative answer into "HDR10+ SEI detected
        // on plain HEVC track" and locks its strip mode, so a clean sample has
        // to keep answering -1 — with libdovi in the build too, which is why the
        // routing translates the native "returned it whole" into that.
        val au = annexB(vps, sps, pps, vcl)
        val before = au.copyOf()
        assertEquals(
            -1,
            DolbyVisionCompat.stripAnnexB(au, au.size, stripDv = true, stripHdr10Plus = true)
        )
        assertArrayEquals("nothing removed means nothing touched", before, au)

        val sample = lengthDelimited(vps, sps, pps, vcl)
        val lengthBefore = sample.copyOf()
        assertEquals(
            -1,
            DolbyVisionCompat.stripLengthDelimited(
                sample, sample.size, 4, stripDv = true, stripHdr10Plus = true
            )
        )
        assertArrayEquals(lengthBefore, sample)
    }

    @Test
    fun `the Kotlin path keeps reporting real sizes and is not marked as libdovi`() {
        val au = annexB(vps, sps, pps, rpu, el, hdr10PlusSei, vcl)
        val accounting = DolbyVisionCompat.SeiStripAccounting()
        val newLen = DolbyVisionCompat.stripAnnexB(
            au, au.size, stripDv = true, stripHdr10Plus = true, accounting = accounting
        )
        assertTrue(newLen >= 0)

        assertFalse("no libdovi in this process, so the marker must stay off", accounting.viaLibdovi)
        assertEquals(
            "the RPU, the EL and the HDR10+ SEI are all examined, in order",
            listOf(62, 63, 39),
            accounting.entries.map { it.nalType }
        )
        assertEquals(
            "but only the RPU and the EL go — the NAL 39 is kept whole for DV",
            listOf(
                DolbyVisionCompat.SeiStripAccounting.Action.DROPPED,
                DolbyVisionCompat.SeiStripAccounting.Action.DROPPED,
                DolbyVisionCompat.SeiStripAccounting.Action.KEPT
            ),
            accounting.entries.map { it.action }
        )
        assertTrue(
            "and their sizes are measurements, unlike the native path's categories",
            accounting.entries.all { it.originalSize > 0 }
        )
    }

    private companion object {
        const val COMPAT = "com/kennyb1201/kbstream/ui/player/DolbyVisionCompat.kt"
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
    }
}
