package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Dolby Vision chips are the TV page's chips.
 *
 * They were not: the AUTO mode read "P7 → 8.1" on a phone, so the mode looked
 * like a fixed conversion rather than the display-driven choice the TV page
 * calls "Auto", and one static sentence underneath had to describe all three
 * modes - it named the first one wrong and never mentioned "Off" at all. The
 * TV page has always shown a per-mode explanation; a phone shows the same
 * three modes and must explain them the same way, with only the audience noun
 * adapted ("TVs" reads "displays" here).
 *
 * The copy lives in two pure functions so a unit test can read every mode's
 * text instead of grepping one long `when` for substrings.
 */
class DvCompatCopyMobileContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun mobileSettings(): String =
        source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    private fun tvSettings(): String =
        source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")

    // ── the labels are the TV page's, exactly ───────────────────────

    @Test
    fun `the three chips read auto, none and strip all`() {
        assertEquals("Auto", dvCompatChipLabel(AppPreferences.DV_COMPAT_AUTO))
        assertEquals("None", dvCompatChipLabel(AppPreferences.DV_COMPAT_OFF))
        assertEquals("Strip All", dvCompatChipLabel(AppPreferences.DV_COMPAT_ALL))
    }

    @Test
    fun `the phone offers the modes in the tv page's order`() {
        val mobile = mobileSettings()
        val row = mobile
            .substringAfter("label = \"Dolby Vision compatibility\",")
            .substringBefore("selected = dvMode")
        assertTrue(
            "the row must list Auto, None then Strip All - the TV page's order - " +
                "with each label taken from the shared function so the strings cannot " +
                "drift apart again: $row",
            row.contains(
                "options = listOf( AppPreferences.DV_COMPAT_AUTO, " +
                    "AppPreferences.DV_COMPAT_OFF, AppPreferences.DV_COMPAT_ALL )" +
                    ".map { mode -> dvCompatChipLabel(mode) to mode }"
            )
        )
    }

    // ── the explanation follows the selection ──────────────────────

    @Test
    fun `each mode explains itself with the tv page's wording`() {
        assertEquals(
            "Chooses by your display: Dolby Vision passthrough on DV displays " +
                "(P7 → 8.1 for compatibility); HDR10 with HDR10+ removed on non-DV " +
                "displays. P5 follows its toggle below",
            dvCompatDescription(AppPreferences.DV_COMPAT_AUTO)
        )
        assertEquals(
            "Play files exactly as provided (device must handle DV)",
            dvCompatDescription(AppPreferences.DV_COMPAT_OFF)
        )
        assertEquals(
            "Strips every DV profile (P4/P5/P7/P8) → HDR10/HEVC for non-DV " +
                "displays. P5 colors are corrected on the GPU automatically",
            dvCompatDescription(AppPreferences.DV_COMPAT_ALL)
        )
    }

    @Test
    fun `no mode borrows another mode's explanation`() {
        val texts = listOf(
            AppPreferences.DV_COMPAT_AUTO,
            AppPreferences.DV_COMPAT_OFF,
            AppPreferences.DV_COMPAT_ALL
        ).map { dvCompatDescription(it) }
        assertEquals("every mode gets its own answer", 3, texts.toSet().size)
        texts.forEach { assertTrue("no mode may be silent", it.isNotBlank()) }
        assertEquals(
            "an unknown value says nothing rather than something wrong",
            "",
            dvCompatDescription(Int.MIN_VALUE)
        )
    }

    @Test
    fun `the auto copy says it chooses by display and never claims a tv`() {
        val auto = dvCompatDescription(AppPreferences.DV_COMPAT_AUTO)
        assertTrue(
            "Auto is the display-driven mode, and the phone's copy has to say so " +
                "instead of naming its P7 conversion as if it were the whole mode",
            auto.startsWith("Chooses by your display:")
        )
        assertTrue(
            "the noun is the phone's: a phone explains itself in terms of displays",
            auto.contains("DV displays") && auto.contains("non-DV displays")
        )
        assertFalse("a phone must not call the viewer's screen a TV", auto.contains("TV"))
        assertFalse(
            "and neither may the strip-all copy",
            dvCompatDescription(AppPreferences.DV_COMPAT_ALL).contains("TV")
        )
    }

    // ── and the phone actually shows it ────────────────────────────

    @Test
    fun `the description is the selected mode's, not one static sentence`() {
        val mobile = mobileSettings()
        assertTrue(
            "the row has to render the selected mode's own explanation",
            mobile.contains("description = dvCompatDescription(dvMode)")
        )
        listOf(
            "P7 → 8.1\" to",
            "\"Strip all\""
        ).forEach { stale ->
            assertFalse(
                "the drifted label $stale is what this change removes",
                mobile.contains(stale)
            )
        }
        assertFalse(
            "the one sentence that had to describe three modes is gone",
            mobile.contains("converts Profile 7 to a form players accept")
        )
    }

    /**
     * The P5 switch is the TV page's switch, wiring included (2026-10-23).
     *
     * `getConvertP5To81` is the EFFECTIVE state: it reads true whenever this
     * device advertises no Dolby Vision decoder, whatever the stored flag
     * says. A switch free to move therefore stored `false` and read back on at
     * the next visit - the "it keeps turning itself back on" report - so the
     * row is locked wherever the device or the selected mode has already
     * decided, exactly as the TV page locks it.
     */
    @Test
    fun `the p5 switch is locked where the device has already decided`() {
        val mobile = mobileSettings()
        val row = mobile
            .substringAfter("label = \"Convert Profile 5 to 8.1\",")
            .substringBefore("SettingsSectionMobile(\"Stream Picks & Auto-play\")")
        assertTrue(
            "the row must read the device rule the TV page reads",
            mobile.contains(
                "val p5ConversionRequired = " +
                    "AppPreferences.isP5ConversionRequired(context)"
            )
        )
        assertTrue(
            "and lock the switch off that requirement and off the modes that " +
                "ignore it: $row",
            row.contains(
                "enabled = dvMode == AppPreferences.DV_COMPAT_AUTO && " +
                    "!p5ConversionRequired"
            )
        )
        assertTrue(
            "the row says why it cannot be moved where the conversion is not a " +
                "choice: $row",
            row.contains("Always on: this device has no Dolby Vision decoder")
        )
        assertTrue(
            "a locked row must not move: the switch takes input only when enabled",
            mobile.contains("onCheckedChange = if (enabled) onChange else null")
        )
        assertTrue(
            "and the key it writes is still the same one",
            mobile.contains("AppPreferences.setConvertP5To81(context, it)")
        )
    }

    // ── the mode's behaviour and the TV page are untouched ─────────

    @Test
    fun `only the copy changed`() {
        val mobile = mobileSettings()
        assertTrue(
            "the selection still writes the same preference",
            mobile.contains("dvMode = value") &&
                mobile.contains("AppPreferences.setDvCompatMode(context, value)")
        )
        assertTrue(
            "and the P5 and HDR10+ toggles below are still the same keys",
            mobile.contains("AppPreferences.setConvertP5To81(context, it)") &&
                mobile.contains("AppPreferences.setStripHdr10Plus(context, it)")
        )
        val tv = tvSettings()
        assertTrue(
            "the TV page is the reference and keeps its own nouns",
            tv.contains("AppPreferences.DV_COMPAT_AUTO to \"Auto\"") &&
                tv.contains("AppPreferences.DV_COMPAT_OFF to \"None\"") &&
                tv.contains("AppPreferences.DV_COMPAT_ALL to \"Strip All\"") &&
                tv.contains("Dolby Vision passthrough on DV TVs")
        )
    }
}
