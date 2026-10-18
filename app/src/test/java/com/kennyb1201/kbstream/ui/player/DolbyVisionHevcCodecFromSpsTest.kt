package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DV relabel's codec string must come from the stream's own SPS, not from
 * the hardcoded Main10@L5.1 guess. Field: a `dvhe.08.06` file declares High
 * tier, Level 5.0 (`hvc1.2.4.H150.B0` in the reference player's logcat) while KBStream
 * advertised `hvc1.2.4.L153.B0` — and the MTK HEVC decoder accepted the first
 * input frame and emitted zero output frames whenever the advertised tier/level
 * disagreed with the SPS.
 *
 * This pins [DolbyVisionCompat.hevcCodecFromInitData] behaviorally against
 * hand-built SPS NALs (both container forms Media3 delivers) and pins the
 * factory wiring that consumes it — including that the removed diagnostic
 * toggle left nothing behind.
 */
class DolbyVisionHevcCodecFromSpsTest {

    // ── behavioral: the SPS parse ──────────────────────────────────────────

    /** A 2-byte NAL header for [type] plus [payload] as the RBSP. */
    private fun nal(type: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(((type shl 1) and 0xFF).toByte(), 0x01)
        return header + payload
    }

    /** Annex-B framing: 4-byte start code before every NAL. */
    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        nals.forEach { nal ->
            out.add(0); out.add(0); out.add(0); out.add(1)
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    /**
     * SPS RBSP for Main10 with the given tier and level and the General
     * constraint byte 0xB0: sps_video_parameter_set_id(4)=0,
     * sps_max_sub_layers_minus1(3)=0, sps_temporal_id_nesting_flag(1)=1, then
     * profile_tier_level: profile_space(2)=0, tier(1), profile_idc(5)=2,
     * compatibility flags(32) with only flag[2], six constraint bytes
     * (0xB0,0,0,0,0,0), general_level_idc(8).
     */
    private fun spsRbsp(highTier: Boolean, levelIdc: Int): ByteArray = byteArrayOf(
        0x01,
        (if (highTier) 0x22 else 0x02).toByte(),
        0x20, 0x00, 0x00, 0x00,
        0xB0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        levelIdc.toByte()
    )

    /** Re-inserts emulation-prevention bytes so the RBSP can ride in Annex-B. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 4)
        var zeros = 0
        rbsp.forEach { raw ->
            val v = raw.toInt() and 0xFF
            if (zeros >= 2 && v <= 0x03) {
                out.add(0x03)
                zeros = 0
            }
            out.add(v.toByte())
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private val vps = nal(32, byteArrayOf(0x0C, 0x01, 0x0A, 0x0B))
    private val pps = nal(34, byteArrayOf(0x0D, 0x0E, 0x0F))

    @Test
    fun `Main10 High tier Level 5_0 derives the SPS codec string`() {
        val sps = nal(33, spsRbsp(highTier = true, levelIdc = 150))
        val init = annexB(vps, sps, pps)

        assertEquals(
            "the dvhe.08.06 field case: High tier Level 5.0 is H150, not the L153 guess",
            "hvc1.2.4.H150.B0",
            DolbyVisionCompat.hevcCodecFromInitData(listOf(init))
        )
    }

    @Test
    fun `Main tier Level 3_1 derives its own level`() {
        val sps = nal(33, spsRbsp(highTier = false, levelIdc = 93))
        val init = annexB(vps, sps, pps)

        assertEquals(
            "Main tier Level 3.1 is L93",
            "hvc1.2.4.L93.B0",
            DolbyVisionCompat.hevcCodecFromInitData(listOf(init))
        )
    }

    @Test
    fun `emulation-prevention bytes in the SPS RBSP are removed before parsing`() {
        val raw = spsRbsp(highTier = true, levelIdc = 150)
        val escaped = escape(raw)
        assertTrue(
            "the fixture must actually contain emulation-prevention bytes, or it proves nothing",
            escaped.size > raw.size
        )
        val sps = nal(33, escaped)
        val init = annexB(vps, sps, pps)

        assertEquals(
            "an unescaped 00 00 03 must not shift the profile_tier_level bits",
            "hvc1.2.4.H150.B0",
            DolbyVisionCompat.hevcCodecFromInitData(listOf(init))
        )
    }

    @Test
    fun `an hvcC configuration record yields the same string`() {
        val payload = nal(33, spsRbsp(highTier = true, levelIdc = 150))
        val header = ByteArray(23)
        header[0] = 1
        header[22] = 1 // one array
        val array = byteArrayOf(
            // nal_unit_type 33 (completeness/reserved bits clear)
            33,
            0, 1, // one NAL
            (payload.size shr 8).toByte(), payload.size.toByte()
        )
        val hvcC = header + array + payload

        assertEquals(
            "MP4/fMP4 delivers the parameters as an hvcC record, not Annex-B",
            "hvc1.2.4.H150.B0",
            DolbyVisionCompat.hevcCodecFromInitData(listOf(hvcC))
        )
    }

    @Test
    fun `no SPS in the init data returns null`() {
        val vpsOnly = annexB(vps, pps)

        assertNull(
            "a buffer with no SPS has nothing to derive",
            DolbyVisionCompat.hevcCodecFromInitData(listOf(vpsOnly))
        )
        assertNull(
            "an empty parameter-set list has nothing to derive",
            DolbyVisionCompat.hevcCodecFromInitData(emptyList())
        )
    }

    @Test
    fun `a truncated SPS returns null instead of a wrong guess`() {
        // Only the first two RBSP bytes survive: the reader runs out inside the
        // compatibility flags, so the parse must fail closed.
        val sps = nal(33, byteArrayOf(0x01, 0x22, 0x20, 0x00))
        val init = annexB(vps, sps, pps)

        assertNull(
            "a parse that runs out of bits must return null",
            DolbyVisionCompat.hevcCodecFromInitData(listOf(init))
        )
    }

    // ── contract: the relabel uses it, and the toggle is gone ─────────────

    private fun source(path: String): String {
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

    private fun count(text: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    @Test
    fun `both relabel sites advertise the SPS-derived codec and log its source`() {
        val factory = source(FACTORY)
        assertTrue(
            "the relabel has to derive the codec from the track's own SPS",
            factory.contains(
                "DolbyVisionCompat.hevcCodecFromInitData(format.initializationData)"
            )
        )
        assertTrue(
            "and fall back to the guessed constant only when the SPS will not parse",
            factory.contains("val relabelCodec = spsCodec ?: rewriteCodec")
        )
        assertEquals(
            "both relabel call sites must hand the derived string to relabelDvFormat, and " +
                "the format they hand it is the VPS-rewritten one",
            2,
            count(factory, "relabelDvFormat(relabel.format, relabelCodec)")
        )
        assertEquals(
            "and every re-advertising log must name codecSrc=sps|fallback — the two relabel " +
                "sites plus the keep-DV strip, whose own log reports that the SPS-derived string " +
                "it decided NOT to advertise came from the track (the keep-DV arm keeps the " +
                "declared dvhe string, so the field log has to say both that it did not relabel " +
                "and what the relabel would have said)",
            3,
            count(factory, " codecSrc=\$codecSrc")
        )
    }

    @Test
    fun `the mutating strip advertises the codec its rewritten config declares`() {
        val factory = source(FACTORY)
        // This arm rewrites the codec config's VPS AND SPS (rewriteInitData ->
        // rewriteInitDataVps -> rewriteCodecConfig), so the string it advertises
        // has to come from that REWRITTEN config: the pre-rewrite csd SPS names
        // the source's proprietary DV profile / tier, which nothing this path
        // sends carries any more, and the bare `hvc1.2.4.L153.B0` guess names a
        // level the rewritten SPS does not (5.1 beside an SPS saying 5.0 is the
        // advertised-string-vs-SPS stall this file exists for).
        val start = factory.indexOf("val stripped = runCatching {")
        assertTrue("the mutating strip block not found", start >= 0)
        val end = factory.indexOf("val strippedFormat =", start)
        assertTrue("the mutating strip block end not found", end > start)
        val mutatingStrip = factory.substring(start, end)
        val rewrite = mutatingStrip.indexOf("rewriteInitData(format.initializationData)")
        val derive = mutatingStrip.indexOf("hevcCodecFromInitData(rewrittenInitData)")
        val advertise = mutatingStrip.indexOf("setCodecs(")
        assertTrue("the config must be rewritten in this block", rewrite >= 0)
        assertTrue(
            "the advertised string must be derived from the rewritten config",
            derive > rewrite
        )
        assertTrue(
            "and derived BEFORE it is advertised",
            advertise > derive
        )
        assertTrue(
            "the guess must survive only as the fallback for a config with no SPS",
            mutatingStrip.contains("?: rewriteCodec")
        )
        // Both wrong shapes: the bare guess, and a string derived from the
        // pre-rewrite source csd.
        assertFalse(
            "the mutating strip must not advertise the bare L153 guess",
            mutatingStrip.contains("setCodecs(rewriteCodec)")
        )
        assertFalse(
            "and must not derive its string from the pre-rewrite csd",
            mutatingStrip.contains("hevcCodecFromInitData(format.initializationData)")
        )
        assertFalse(
            "nor from the source SPS the relabel arms advertise",
            mutatingStrip.contains("setCodecs(relabelCodec)")
        )
    }

    @Test
    fun `the removed diagnostic toggle left nothing behind`() {
        val removed = listOf(
            "diagNuvioConfig", "dvDiagNuvioConfig", "KEY_DV_DIAG_NUVIO_CONFIG",
            "isDvDiagNuvioConfig", "setDvDiagNuvioConfig", "colorSrc=",
            "containerColor=", "describeColorInfo", "Nuvio config (diag)",
            "Diagnostics (temporary)"
        )
        listOf(PREFS, SCREEN, SEARCH, NATIVE, COMPAT, FACTORY).forEach { path ->
            val text = source(path)
            removed.forEach { symbol ->
                assertFalse(
                    "$path still references the removed $symbol",
                    text.contains(symbol)
                )
            }
        }
        assertTrue(
            "the RPU-drop parameter itself stays (the Strip All button's future arm)",
            count(source(COMPAT), "dropRpuNal: Boolean = false,") == 2
        )
    }

    private companion object {
        const val FACTORY = "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
        const val COMPAT = "com/kennyb1201/kbstream/ui/player/DolbyVisionCompat.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
    }
}
