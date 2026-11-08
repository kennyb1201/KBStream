package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's default view belongs to the surface, not to the mode.
 *
 * Both screens read the same per-profile pref through [GuideViewMode.fromPref],
 * and until this change that rule answered LIST when nothing was stored - so a
 * television's very first guide open was the list, which is the one shape a
 * D-pad guide is not built around. Now the caller names the fallback: GRID on
 * the television, LIST on the phone, and a stored choice - whatever it is - still
 * beats it.
 *
 * T1-T5 are pinned here: the rule on the JVM, and the two call sites from the
 * sources, since this module has no Compose harness.
 */
class GuideViewDefaultContractTest {

    private val tvScreen by lazy { source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt") }
    private val phoneScreen by lazy { source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt") }
    private val rules by lazy { source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt") }

    // ── T1 / T2: a fresh profile opens on the surface's own view ───────────

    @Test
    fun `a fresh television profile opens on the grid`() {
        assertEquals(
            "T1: nothing stored, television default",
            GuideViewMode.GRID,
            GuideViewMode.fromPref(null, default = GuideViewMode.GRID)
        )
        assertTrue(
            "and the television's call site asks for that default: $tvScreen",
            tvScreen.contains("guidePreferences.getString(\"guide_view\", null)") &&
                tvScreen.contains("default = GuideViewMode.GRID,")
        )
    }

    @Test
    fun `a fresh phone profile opens on the list`() {
        assertEquals(
            "T2: nothing stored, phone default",
            GuideViewMode.LIST,
            GuideViewMode.fromPref(null, default = GuideViewMode.LIST)
        )
        assertTrue(
            "and the phone's call site asks for that default: $phoneScreen",
            phoneScreen.contains("guidePrefs.getString(\"guide_view\", null)") &&
                phoneScreen.contains("default = GuideViewMode.LIST,")
        )
    }

    @Test
    fun `the shared rule itself stays neutral`() {
        assertEquals(
            "F1a: the enum's own fallback is the list, so no surface inherits the " +
                "television's choice by accident",
            GuideViewMode.LIST,
            GuideViewMode.fromPref(null)
        )
    }

    // ── T3: a stored choice always wins ────────────────────────────────────

    @Test
    fun `a stored view beats the surface default in both directions`() {
        assertEquals(
            "T3: a television viewer who chose the list keeps the list",
            GuideViewMode.LIST,
            GuideViewMode.fromPref("list", default = GuideViewMode.GRID)
        )
        assertEquals(
            "T3: and a phone viewer who chose the grid keeps the grid",
            GuideViewMode.GRID,
            GuideViewMode.fromPref("grid", default = GuideViewMode.LIST)
        )
        assertEquals(
            "F1c: the stored spelling is the pref's own, unchanged",
            "grid",
            GuideViewMode.GRID.prefValue
        )
    }

    // ── T4: an unknown stored value is not a crash ─────────────────────────

    @Test
    fun `an unknown stored value falls back to the surface default`() {
        assertEquals(
            "T4: television",
            GuideViewMode.GRID,
            GuideViewMode.fromPref("timeline", default = GuideViewMode.GRID)
        )
        assertEquals(
            "T4: phone",
            GuideViewMode.LIST,
            GuideViewMode.fromPref("timeline", default = GuideViewMode.LIST)
        )
        assertEquals(
            "and an empty value is no value",
            GuideViewMode.GRID,
            GuideViewMode.fromPref("", default = GuideViewMode.GRID)
        )
    }

    // ── T5: a profile switch re-reads that profile's pref ──────────────────

    @Test
    fun `switching profiles re-reads the pref under the new profile`() {
        assertTrue(
            "T5: the television keys its view state on the profile, so a switch " +
                "re-runs the read: $tvScreen",
            tvScreen.contains("var guideView by remember(activeProfileId) {")
        )
        assertTrue(
            "T5: and so does the phone: $phoneScreen",
            phoneScreen.contains("var guideView by remember(activeProfileId) {")
        )
        assertTrue(
            "F2b: both read the stored value at that moment, not a remembered one",
            tvScreen.contains("guidePreferences.getString(\"guide_view\", null)") &&
                phoneScreen.contains("guidePrefs.getString(\"guide_view\", null)")
        )
    }

    // ── F2: the toggle and its write are unchanged ────────────────────────

    @Test
    fun `the toggle still persists a choice on both surfaces`() {
        assertTrue(
            "F2a: the television's toggle writes the chosen view",
            tvScreen.contains("guidePreferences.edit()") &&
                tvScreen.contains("putString(\"guide_view\", next.prefValue)")
        )
        assertTrue(
            "F2a: and the phone's",
            phoneScreen.contains("guidePrefs.edit()") &&
                phoneScreen.contains("putString(\"guide_view\", next.prefValue)")
        )
        assertTrue(
            "F1a: the rule still offers the one flip the toggle needs",
            rules.contains("fun toggled(current: GuideViewMode): GuideViewMode =")
        )
    }

    // ── Source helper ─────────────────────────────────────────────────────

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
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
}
