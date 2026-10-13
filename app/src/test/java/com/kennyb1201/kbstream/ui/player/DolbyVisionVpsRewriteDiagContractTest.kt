package com.kennyb1201.kbstream.ui.player

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `diagVpsRewrite` experiment: with [DolbyVisionCompat.rewriteVpsInSample]
 * it rewrites the sample's VPS to a clean HDR10 one (DV extension dropped, PTL
 * patched) while the declared `dvhe.08.06` codec string stays on the track.
 *
 * It is the companion to `diagNoRelabel`, and it is deliberately narrower than
 * "rewrite the VPS": the MediaTek hypothesis is about a decoder that configures
 * its DV pipeline from the VPS and then waits for RPUs the strip removed, so
 * the run that tests it has to be the unrelabeled one — the same arm, one more
 * variable. The relabel arm reaches the same in-place strip through its own two
 * `mode = Mode.STRIP_NALS_ONLY` sites, so a gate that read only
 * `diagVpsRewrite` would rewrite the parameter sets of the shipped relabel
 * path: a field-proven arm, and exactly the mistake the previous experiment's
 * test file forbids. [the rewrite is gated on the unrelabeled arm] pins that,
 * and the two behavior tests pin what the rewrite itself may and may not touch
 * (the VPS changes, the SPS — whose bytes the advertised codec string is read
 * from — and the PPS do not).
 */
class DolbyVisionVpsRewriteDiagContractTest {

    // ── behavioral: the in-place VPS rewrite ──────────────────────────────

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

    // ── the gate: the unrelabeled arm only ────────────────────────────────

    private val source: String by lazy {
        val file = File(findSourceRoot(), EXTRACTOR)
        assertTrue("source missing: $file", file.isFile)
        file.readText()
    }

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

    /** Where `if (diagNoRelabel && diagVpsRewrite) {` opens in the factory. */
    private fun gateLine(): String =
        "if (diagNoRelabel && diagVpsRewrite) {"

    @Test
    fun `the rewrite is gated on the unrelabeled arm`() {
        val gate = source.indexOf(gateLine())
        assertTrue("the gate not found", gate >= 0)
        assertTrue(
            "it belongs to the in-place strip, which is the handler both arms run through",
            source.indexOf("if (mode == Mode.STRIP_NALS_ONLY) {") < gate
        )
        // The relabel arm's two `mode = Mode.STRIP_NALS_ONLY` sites feed this
        // same handler, so `diagVpsRewrite` alone must not be enough...
        assertFalse(
            "a gate that reads only diagVpsRewrite would rewrite the shipped relabel path's parameter sets",
            source.contains("if (diagVpsRewrite) {")
        )
        // ...and `diagNoRelabel` is an exact proxy for "the unrelabeled arm
        // chose this mode", because that branch returns before the RELABEL_ONLY
        // branch below it can be reached.
        val unrelabeled = source.indexOf("\"diagNoRelabel: keeping ")
        val relabelOnly = source.indexOf("if (handling == Hdr10BaseHandling.RELABEL_ONLY) {")
        assertTrue("the unrelabeled branch not found", unrelabeled >= 0)
        assertTrue("the RELABEL_ONLY branch not found", relabelOnly > unrelabeled)
        assertTrue(
            "the unrelabeled arm has to return before the relabel branch, or diagNoRelabel says nothing about which arm set the mode",
            Regex("\\breturn\\b")
                .containsMatchIn(source.substring(unrelabeled, relabelOnly))
        )
    }

    @Test
    fun `the block can only rewrite the VPS`() {
        val gate = source.indexOf(gateLine())
        val end = source.indexOf("emit(outLen)", gate)
        assertTrue("the block does not end at its emit", end > gate)
        val block = source.substring(gate, end)
        assertTrue(
            "the rewrite itself, framed the way the strip above was",
            block.contains("DolbyVisionCompat.rewriteVpsInSample(") &&
                block.contains("framing == NalFraming.ANNEX_B")
        )
        assertTrue(
            "and its result replaces the sample in a buffer with room for it — an " +
                "in-place rewrite runs off the end when the rebuilt VPS is longer",
            block.contains("ensurePendingCapacity(rewritten.size)")
        )
        assertTrue(
            "while the carry is parked across the copy and put back at 0, which is " +
                "where the next read appends",
            block.contains("pendingBuf.copyOfRange(sampleEnd, sampleEnd + carrySize)") &&
                block.contains("carry?.copyInto(pendingBuf, 0)")
        )
        listOf(
            "relabelDvFormat" to "the codec string is the one variable this arm keeps",
            "relabelCodec" to "the codec string is the one variable this arm keeps",
            "setCodecs" to "the declared dvhe.08.06 string may not change",
            "setSampleMimeType" to "the mime type may not change",
            "setInitializationData" to "the container's parameter sets are not this block's to replace",
            "rewriteInitData" to "the container's parameter sets are not this block's to replace",
            "setColorInfo" to "the color metadata is not this block's variable",
            "dvHdr10ColorInfoFor" to "the color metadata is not this block's variable",
            "mode =" to "the mode the strip already chose is not this block's to change",
            "delegate.format(format)" to "the format was already handed through by the arm above"
        ).forEach { (symbol, why) ->
            assertFalse("$why (found $symbol)", block.contains(symbol))
        }
    }

    // ── the wiring end to end ────────────────────────────────────────────

    @Test
    fun `the factory takes the flag, defaults it off and hands it to every wrapper`() {
        assertTrue(
            "the factory parameter must default off, so no caller can opt in by accident",
            source.contains("private val diagVpsRewrite: Boolean = false")
        )
        assertEquals(
            "factory + the three wrappers (extractor, output, track output)",
            4,
            Regex("diagVpsRewrite: Boolean").findAll(source).count()
        )
        assertEquals(
            "and it is handed down at each of the three construction sites, beside the arm it belongs to",
            3,
            Regex("nonDvDisplayOnDvDevice,\\s*diagNoRelabel,\\s*diagVpsRewrite").findAll(source).count()
        )
        assertTrue(
            "the PLAYER_DV extractor line has to say which arm the run was in",
            source.contains("diagNoRelabel=\$diagNoRelabel diagVpsRewrite=\$diagVpsRewrite")
        )
    }

    @Test
    fun `the preference is stored under its own key, off by default`() {
        val prefs = read(PREFS)
        assertTrue(
            "the key itself",
            prefs.contains("private const val KEY_DV_DIAG_VPS_REWRITE = \"dv_diag_vps_rewrite\"")
        )
        assertTrue(
            "the getter defaults to false — a fresh install must not be in the experiment",
            prefs.contains("getBoolean(KEY_DV_DIAG_VPS_REWRITE, false)")
        )
        assertTrue(
            "and the setter writes the same key",
            prefs.contains("putBoolean(KEY_DV_DIAG_VPS_REWRITE, enabled)")
        )
    }

    @Test
    fun `the player reads the toggle, passes it and logs it`() {
        val native = read(NATIVE)
        assertTrue(
            "the player has to read it like every other DV toggle",
            native.contains("AppPreferences.isDiagVpsRewrite(this)")
        )
        assertTrue(
            "and hand it to the factory",
            native.contains("diagVpsRewrite = diagVpsRewrite")
        )
        assertTrue(
            "the PLAYER_DV settings line must name it — the capture has to say whether the VPS was rewritten",
            native.contains("diagVpsRewrite=\$diagVpsRewrite")
        )
    }

    @Test
    fun `the settings row sits under the no-relabel switch it depends on`() {
        val screen = read(SCREEN)
        assertTrue(
            "the row seeds its state from the preference",
            screen.contains(
                "var diagVpsRewrite by remember { " +
                    "mutableStateOf(AppPreferences.isDiagVpsRewrite(context)) }"
            )
        )
        assertTrue(
            "and writes it back on toggle",
            screen.contains("AppPreferences.setDiagVpsRewrite(context, it)")
        )
        val noRelabel = screen.indexOf("label = \"Keep DV codec (no relabel)\"")
        val row = screen.indexOf("label = \"diagnose: rewrite VPS to HDR10 (no-relabel path)\"")
        val nextSection = screen.indexOf("SettingsSectionHeader(\"Stream Picks & Auto-play\")")
        assertTrue("the experiment row not found", row >= 0)
        assertTrue(
            "it belongs directly under the switch it needs, and still in the Dolby Vision & HDR section",
            noRelabel >= 0 && row > noRelabel && row < nextSection
        )
    }

    private companion object {
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }
}
