package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-relabel DV experiment (`diagNoRelabel`): keep the file's declared
 * Dolby Vision codec string and strip the RPU/EL + HDR10+ SEI in place, instead
 * of re-advertising the base layer as hvc1.
 *
 * That combination is what the reference transformer plays in
 * `stripRpuOnly` mode: it never relabels (`onDolbyVisionCodecString` returns
 * null, so the declared `dvhe.08.06` stays), it drops the RPU NALs and the
 * enhancement layers, and the MTK decoder configures its DV pipeline for the
 * declared profile, receives no RPU, and falls back to the HDR10 base layer.
 * Every KBStream DV arm so far relabeled, so the question — is the relabel
 * itself what stalls this chipset? — has never been run. This arm exists to
 * answer exactly that, and nothing else: it must NOT be able to change the
 * codec string, the parameter sets, the color info, or the mime type. If it
 * could, the experiment would be measuring a second variable.
 *
 * With the setting off every other path is what it was, which the last test
 * pins: the flag is threaded to the strip only, and the relabel call sites are
 * still there behind their own gates.
 */
class DolbyVisionNoRelabelDiagContractTest {

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

    /**
     * The branch body: from the distinguishing log line up to the `RELABEL_ONLY`
     * gate that follows it. Everything the arm is allowed to do is in here, and
     * the assertions below are all about what must NOT be in here.
     */
    private fun branch(): String {
        val start = source.indexOf("\"diagNoRelabel: keeping ")
        assertTrue("the diagNoRelabel branch not found", start >= 0)
        val end = source.indexOf("if (handling == Hdr10BaseHandling.RELABEL_ONLY) {", start)
        assertTrue("the branch does not end at the RELABEL_ONLY gate", end > start)
        return source.substring(start, end)
    }

    // ── the branch itself ──────────────────────────────────────────────

    @Test
    fun `the branch keeps the declared codec string and strips through the existing mode`() {
        val branch = branch()
        assertTrue(
            "the field run reads this exact line to confirm the arm ran",
            branch.contains(
                "\"diagNoRelabel: keeping \${format.codecs}, stripping RPU/EL + HDR10+ " +
                    "(no relabel)\""
            )
        )
        assertTrue(
            "the same in-place strip the relabel arm uses, so only the codec string differs",
            branch.contains("mode = Mode.STRIP_NALS_ONLY")
        )
        assertTrue(
            "the format is handed through as-is — that is the whole experiment",
            branch.contains("delegate.format(format)")
        )
    }

    @Test
    fun `the branch cannot relabel, rewrite or recolor anything`() {
        val branch = branch()
        listOf(
            "relabelDvFormat" to "the relabel must not run in the no-relabel arm",
            "relabelCodec" to "the SPS-derived codec string belongs to the relabel arm only",
            "setCodecs" to "the declared codec string may not be replaced",
            "setSampleMimeType" to "the DV mime type may not be swapped for video/hevc",
            "setInitializationData" to "VPS/SPS/PPS must stay exactly as the container had them",
            "rewriteInitData" to "VPS/SPS/PPS must stay exactly as the container had them",
            "setColorInfo" to "the color metadata is not this arm's variable",
            "dvHdr10ColorInfoFor" to "the color metadata is not this arm's variable"
        ).forEach { (symbol, why) ->
            assertFalse("$why (found $symbol)", branch.contains(symbol))
        }
    }

    @Test
    fun `only the two relabel sites are redirected`() {
        // The safety property: a box WITHOUT a Dolby Vision decoder keeps the
        // tested mutating strip (VPS rewrite + RPU/EL removal), and native
        // passthrough has already returned above this point. If the gate ever
        // widened to every HDR10-base profile, the experiment would replace a
        // field-proven path with an unproven one on hardware that has no
        // relabel to be suspicious of in the first place.
        val gate = Regex(
            "if \\(\\s*diagNoRelabel &&\\s*" +
                "\\(handling == Hdr10BaseHandling\\.RELABEL_ONLY \\|\\| nativeDvSupported\\)\\s*\\)"
        )
        assertTrue(
            "the branch must be gated on the relabel sites, not on every HDR10-base profile",
            gate.containsMatchIn(source)
        )
        assertTrue(
            "and it must sit BEFORE the RELABEL_ONLY branch it replaces",
            source.indexOf("diagNoRelabel: keeping ") <
                source.indexOf("if (handling == Hdr10BaseHandling.RELABEL_ONLY) {")
        )
    }

    @Test
    fun `the strip the arm engages is still the one that drops the RPU EL and HDR10+`() {
        // Reused, not reimplemented: the arm's whole premise is that
        // Mode.STRIP_NALS_ONLY already strips the RPU (62) / EL (63) and the
        // HDR10+ SEI while never rewriting a parameter set.
        val start = source.indexOf("if (mode == Mode.STRIP_NALS_ONLY) {")
        assertTrue("the STRIP_NALS_ONLY handler not found", start >= 0)
        val end = source.indexOf("val stats = DolbyVisionCompat.StripStats()", start)
        assertTrue("the STRIP_NALS_ONLY handler end not found", end > start)
        val handler = source.substring(start, end)
        assertEquals(
            "both framing branches drop the RPU/EL",
            2,
            Regex("stripDv = true,").findAll(handler).count()
        )
        assertTrue("and both drop the HDR10+ SEI", handler.contains("stripHdr10Plus = true"))
        assertFalse(
            "with no parameter-set rewrite, which is what stalls MTK-class decoders",
            handler.contains("rewriteInitData")
        )
        assertTrue(
            "the field run greps this label for the per-NAL fates (62:<size>->dropped)",
            source.contains("NALS-strip: first sample")
        )
    }

    @Test
    fun `the flag is off by default and reaches the strip through every wrapper`() {
        assertTrue(
            "the factory parameter must default off, so no caller can opt in by accident",
            source.contains("private val diagNoRelabel: Boolean = false")
        )
        assertEquals(
            "factory + the three wrappers (extractor, output, track output)",
            4,
            Regex("diagNoRelabel: Boolean").findAll(source).count()
        )
        assertEquals(
            "and it must be handed down at each of the three construction sites",
            3,
            Regex("nonDvDisplayOnDvDevice,\\s*diagNoRelabel").findAll(source).count()
        )
    }

    // ── the wiring end to end ──────────────────────────────────────────

    @Test
    fun `the preference is stored under its own key, off by default`() {
        val prefs = read(PREFS)
        assertTrue(
            "the key itself",
            prefs.contains("private const val KEY_DIAG_NO_RELABEL = \"diag_no_relabel\"")
        )
        assertTrue(
            "read defaults to false — a fresh install must not be in the experiment",
            prefs.contains("getBoolean(KEY_DIAG_NO_RELABEL, false)")
        )
        assertTrue(
            "and the setter writes the same key",
            prefs.contains("putBoolean(KEY_DIAG_NO_RELABEL, enabled)")
        )
    }

    @Test
    fun `the player reads the toggle, passes it and logs it`() {
        val native = read(NATIVE)
        assertTrue(
            "the player has to read it like every other DV toggle",
            native.contains("AppPreferences.isDiagNoRelabel(this)")
        )
        assertTrue(
            "and hand it to the factory",
            native.contains("diagNoRelabel = diagNoRelabel")
        )
        assertTrue(
            "the PLAYER_DV settings line must say which arm the run was in — the " +
                "2026-10-11 capture had no way to tell a relabel from a passthrough",
            native.contains("diagNoRelabel=\$diagNoRelabel")
        )
    }

    @Test
    fun `the settings row lives with the other Dolby Vision switches`() {
        val screen = read(SCREEN)
        assertTrue(
            "the row seeds its state from the preference",
            screen.contains(
                "var diagNoRelabel by remember { " +
                    "mutableStateOf(AppPreferences.isDiagNoRelabel(context)) }"
            )
        )
        assertTrue(
            "and writes it back on toggle",
            screen.contains("AppPreferences.setDiagNoRelabel(context, it)")
        )
        val row = screen.indexOf("label = \"Keep DV codec (no relabel)\"")
        val hdr10Plus = screen.indexOf("label = \"Strip HDR10+\"")
        val nextSection = screen.indexOf("SettingsSectionHeader(\"Stream Picks & Auto-play\")")
        assertTrue("the experiment row not found", row >= 0)
        assertTrue(
            "it belongs in the Dolby Vision & HDR section, after the HDR10+ switch",
            row > hdr10Plus && row < nextSection
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
