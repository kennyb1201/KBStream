package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TEMPORARY Dolby Vision stall-bisect switches (2026-10-11), pinned where a
 * device pass cannot see them.
 *
 * A diagnostic toggle has two silent failure modes. It can be wired to nothing,
 * in which case the field trip spends four playbacks and the verdict is
 * "nothing changed" - indistinguishable from "neither suspect is the cause".
 * Or it can leak into the shipping path, in which case every stream's bytes
 * change. Both are read off the sources here: the arms default OFF at every
 * layer, and each one is actually handed to the call it is meant to change.
 *
 * These assertions come out with the arms themselves; nothing else in this file
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
    fun `both arms default off at every layer`() {
        val factory = source(FACTORY)
        assertTrue(
            "the factory's arms must default off, or every stream's bytes change",
            factory.contains("private val diagDropRpu: Boolean = false,") &&
                factory.contains("private val diagNoColorInfo: Boolean = false")
        )
        assertTrue(
            "the strip functions' arm must default off too",
            source(COMPAT).contains("dropRpuNal: Boolean = false,")
        )
        val prefs = source(PREFS)
        assertTrue(
            "the stored arms must default off",
            prefs.contains("getBoolean(KEY_DV_DIAG_DROP_RPU, false)") &&
                prefs.contains("getBoolean(KEY_DV_DIAG_NO_COLOR_INFO, false)")
        )
    }

    @Test
    fun `arm A reaches both strip calls of the relabel path`() {
        val factory = source(FACTORY)
        assertEquals(
            "each of the relabel path's two strip calls (Annex-B and length-delimited) " +
                "must carry arm A, or the toggle answers nothing",
            2,
            count(factory, "dropRpuNal = diagDropRpu")
        )
        assertTrue(
            "arm A drops only the RPU; the EL and the parameter sets stay",
            source(COMPAT).contains("if (keep && dropRpuNal && nalType == NAL_DV_RPU)")
        )
    }

    @Test
    fun `arm B reaches both relabel sites`() {
        val factory = source(FACTORY)
        assertEquals(
            "both relabel sites (non-DV display and DV-decoder hardware) must carry arm B",
            2,
            count(factory, "applyColorInfo = if (diagNoColorInfo) ({ it })")
        )
        assertTrue(
            "arm B skips the attached color info, so the extractor's own (or none) ships",
            factory.contains("else ({ it.setColorInfo(DV_HDR10_COLOR_INFO) })")
        )
    }

    @Test
    fun `the player reads both arms and names them in the DV settings line`() {
        val native = source(NATIVE)
        assertTrue(
            "the arms have to be read from the settings store",
            native.contains("AppPreferences.isDvDiagDropRpu(this)") &&
                native.contains("AppPreferences.isDvDiagNoColorInfo(this)")
        )
        assertTrue(
            "and handed to the factory",
            native.contains("diagDropRpu = dvDiagDropRpu,") &&
                native.contains("diagNoColorInfo = dvDiagNoColorInfo")
        )
        assertTrue(
            "every field capture has to say which arms it ran",
            native.contains("\"diagDropRpu=\$dvDiagDropRpu diagNoColor=\$dvDiagNoColorInfo\"")
        )
    }

    @Test
    fun `the diagnostic rows are drawn and findable`() {
        val screen = source(SCREEN)
        assertTrue(
            "both rows must be drawn in the Dolby Vision cluster",
            screen.contains("\"Diagnostic: drop RPU NAL (type 62)\"") &&
                screen.contains("\"Diagnostic: skip HDR10 color info\"")
        )
        val search = source(SEARCH)
        assertTrue(
            "a drawn toggle row that is not indexed is visible but unfindable",
            search.contains("\"Diagnostic: drop RPU NAL (type 62)\"") &&
                search.contains("\"Diagnostic: skip HDR10 color info\"")
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
