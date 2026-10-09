package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the OMDb key is reachable from Settings.
 *
 * The Awards fact on the detail screen is sourced from OMDB (see
 * `data/omdb/OmdbRepository`), and with no key saved it deliberately shows
 * nothing at all. A getter and setter that no screen calls would leave the
 * whole feature unreachable - which is exactly what it was before this field
 * existed - so the row, its label and all three save paths are pinned here.
 *
 * Read from the source: a `KBTextField`'s focus/blur/Done wiring is Compose,
 * which this module's test suite cannot run.
 */
class OmdbKeySettingsContractTest {

    private val source: String by lazy {
        File(findMainSourceRoot(), SETTINGS).readText().replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun `the integrations pane offers an OMDb key field`() {
        assertTrue(
            "the row the viewer looks for",
            source.contains("\"OMDb API Key\"")
        )
        assertTrue(
            "seeded from the stored key, like every other credential field",
            source.contains("AppPreferences.getOmdbApiKey(context)")
        )
    }

    @Test
    fun `a pasted, Done-d or blurred field saves the key`() {
        // Paste chip, onDone and onFocusChanged: the same three paths the
        // MDBList/TorBox/OpenSubtitles fields use, because remote users
        // routinely navigate away after pasting rather than pressing Done.
        assertEquals(
            3,
            Regex("AppPreferences\\.setOmdbApiKey\\(context, omdbKeyInput\\)")
                .findAll(source).count()
        )
        assertTrue(
            "and the saved state is reflected back to the viewer",
            source.contains("\"Saved — the AWARDS fact appears on the next title you open.\"")
        )
    }

    @Test
    fun `the field lives in the API Keys section, beside the other credentials`() {
        val header = source.indexOf("SettingsSectionHeader(\"API Keys\")")
        val row = source.indexOf("\"OMDb API Key\"")
        val next = source.indexOf("\"OpenSubtitles API Key\"")

        assertTrue("the API Keys section is missing", header >= 0)
        assertTrue("the OMDb row is missing", row >= 0)
        assertTrue("the OpenSubtitles row is missing", next >= 0)
        assertTrue(
            "the OMDb field belongs under API Keys, not in some other pane",
            header < row
        )
        assertTrue(
            "and it sits with the other credentials rather than after the player rows",
            row < next
        )
    }

    private fun findMainSourceRoot(): File {
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

    private companion object {
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }
}
