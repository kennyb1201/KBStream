package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TEMPORARY Dolby Vision stall-bisect switch (2026-10-11), pinned where a
 * device pass cannot see it.
 *
 * Arms A (RPU drop), B (no color attach) and C (whole-NAL SEI drop) all stalled
 * and are gone; the one that survives is arm E, the Vimu config — the relabel
 * path drops the DV RPU (type 62) and KEEPS the HDR10+ SEI, which is what Vimu
 * and Kodi play on this chipset with Dolby Vision disabled. It is the single
 * cell of the bisect the three earlier arms never tested, because the relabel
 * path hardcodes `stripHdr10Plus = true`.
 *
 * A diagnostic toggle has two silent failure modes. It can be wired to nothing,
 * in which case the field trip spends a playback and the verdict is "nothing
 * changed" — indistinguishable from "the suspect is not the cause". Or it can
 * leak into the shipping path, in which case every stream's bytes change. Both
 * are read off the sources here: the arm defaults OFF at every layer, it is
 * actually handed to both strip calls it is meant to change, and the three
 * arms it replaced have left nothing behind.
 *
 * These assertions come out with the arm itself; nothing else in this file
 * outlives the bisect.
 */
class DolbyVisionDiagBisectContractTest {

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
    fun `arm E defaults off at every layer`() {
        val factory = source(FACTORY)
        assertTrue(
            "the factory's arm must default off, or every stream's bytes change",
            factory.contains("private val diagVimuConfig: Boolean = false")
        )
        val compat = source(COMPAT)
        assertTrue(
            "the strip functions' RPU switch must default off too",
            count(compat, "dropRpuNal: Boolean = false,") == 2
        )
        assertFalse(
            "the arm E switch must be the only VP switch left in the strip functions",
            compat.contains("wholeNalSeiDrop")
        )
        val prefs = source(PREFS)
        assertTrue(
            "the stored arm must default off",
            prefs.contains("getBoolean(KEY_DV_DIAG_VIMU_CONFIG, false)")
        )
    }

    @Test
    fun `arm E reaches both strip calls of the relabel path`() {
        val factory = source(FACTORY)
        // The arm is the pair itself: HDR10+ SEI kept (stripHdr10Plus = false)
        // and the RPU dropped. Both strip calls (Annex-B and length-delimited)
        // must carry both halves, or the toggle answers nothing.
        assertEquals(
            "both strip calls must keep the HDR10+ SEI under the arm",
            2,
            count(factory, "stripHdr10Plus = !diagVimuConfig")
        )
        assertEquals(
            "and both must drop the RPU under the arm",
            2,
            count(factory, "dropRpuNal = diagVimuConfig")
        )
        val compat = source(COMPAT)
        assertEquals(
            "arm E drops only the RPU in both strip functions; the EL and the " +
                "parameter sets stay",
            2,
            count(compat, "if (keep && dropRpuNal && nalType == NAL_DV_RPU)")
        )
    }

    @Test
    fun `the relabel sites attach the HDR10 color info unconditionally again`() {
        val factory = source(FACTORY)
        assertEquals(
            "both relabel sites (non-DV display and DV-decoder hardware) must attach " +
                "the color info with no arm-B branch left",
            2,
            count(factory, "applyColorInfo = { it.setColorInfo(DV_HDR10_COLOR_INFO) }")
        )
        assertFalse(
            "arm B's identity branch is deleted, not merely defaulted off",
            factory.contains("diagNoColorInfo")
        )
    }

    @Test
    fun `the player reads arm E and names it in the DV settings line`() {
        val native = source(NATIVE)
        assertTrue(
            "the arm has to be read from the settings store",
            native.contains("AppPreferences.isDvDiagVimuConfig(this)")
        )
        assertTrue(
            "and handed to the factory",
            native.contains("diagVimuConfig = dvDiagVimuConfig")
        )
        assertTrue(
            "every field capture has to say whether the arm ran",
            native.contains("\"diagVimuConfig=\$dvDiagVimuConfig\"")
        )
        assertFalse(
            "the three removed arms must not survive in the settings line",
            native.contains("diagDropRpu=") ||
                native.contains("diagNoColor=") ||
                native.contains("diagWholeNalSei=")
        )
    }

    @Test
    fun `the diagnostic row is drawn and findable`() {
        val screen = source(SCREEN)
        assertTrue(
            "the row must be drawn in the Dolby Vision cluster",
            screen.contains("\"Vimu config: drop DV RPU, keep HDR10+\"")
        )
        assertTrue(
            "and read as a temporary diagnostic like its predecessors",
            screen.contains("Arm E, temporary diagnostic")
        )
        val search = source(SEARCH)
        assertTrue(
            "a drawn toggle row that is not indexed is visible but unfindable",
            search.contains("\"Vimu config: drop DV RPU, keep HDR10+\"")
        )
    }

    @Test
    fun `arms A, B and C left nothing behind`() {
        val removedSymbols = listOf(
            "diagDropRpu", "diagNoColorInfo", "diagWholeNalSeiDrop",
            "dvDiagDropRpu", "dvDiagNoColorInfo", "dvDiagWholeNalSei",
            "KEY_DV_DIAG_DROP_RPU", "KEY_DV_DIAG_NO_COLOR_INFO", "KEY_DV_DIAG_WHOLE_NAL_SEI",
            "SeiShrinkLog", "onSeiShrunk"
        )
        listOf(PREFS, SCREEN, SEARCH, NATIVE, COMPAT, FACTORY).forEach { path ->
            val text = source(path)
            removedSymbols.forEach { symbol ->
                assertFalse(
                    "$path still references the removed $symbol",
                    text.contains(symbol)
                )
            }
        }
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
