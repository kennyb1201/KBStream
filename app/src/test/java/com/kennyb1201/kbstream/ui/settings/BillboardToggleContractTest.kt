package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV's billboard on/off toggle (spec 2026-11-12).
 *
 * The pref, its getter and the Home's gate for it all already existed - and the
 * PHONE already writes it, from its Home manager. The TV had no setter anywhere,
 * so a TV viewer could only get rid of the Spotlight hero by switching the Home
 * layout to Modern. This is one settings row, not a behaviour change: what the
 * row switches OFF is already implemented on the Home side.
 *
 * Source-reading, like this pane's other contracts: there is no device in CI and
 * a Compose screen cannot be driven from a JVM test.
 */
class BillboardToggleContractTest {

    private val settings: String by lazy { read(SETTINGS) }
    private val prefs: String by lazy { read(PREFS) }
    private val spotlight: String by lazy { read(SPOTLIGHT) }

    /** The section, from its declaration to the dialog that follows it. */
    private fun section(): String = between(
        settings,
        "private fun BillboardSettingsSection()",
        "private fun BillboardCatalogsDialog"
    )

    // ── F1: one On/Off row at the top of the section ──────────────────────

    @Test
    fun `the section leads with one Billboard On-Off row`() {
        val s = section()
        assertTrue(
            "the row reads the existing pref",
            s.contains("AppPreferences.getHomeBillboardEnabled(context)")
        )
        assertTrue(
            "and writes it through the existing setter",
            s.contains("AppPreferences.setHomeBillboardEnabled(context, value)")
        )
        assertTrue(
            "it is the app's On/Off presentation - two PillChips on KBCards - not a new control",
            s.contains("listOf(false, true).forEach { value ->") &&
                s.contains("PillChip(if (value) \"On\" else \"Off\", billboardOn == value)")
        )
        assertTrue(
            "the row is a search target, like every other row in the pane",
            s.contains("searchAnchor(SettingsSearchIndex.entryForLabel(\"Billboard\"))")
        )
        assertTrue(
            "and it comes BEFORE the catalogs row, which depends on it",
            s.indexOf("setHomeBillboardEnabled") < s.indexOf("Billboard catalogs")
        )
    }

    // ── F2: OFF is already a Home-side gate ───────────────────────────────

    @Test
    fun `the hero is gated on the pref the row writes, on a fresh Home entry`() {
        assertTrue(
            "Spotlight reads the same pref",
            spotlight.contains("AppPreferences.getHomeBillboardEnabled(context)")
        )
        assertTrue(
            "the read is a keyless remember, so a fresh Home entry re-reads it",
            spotlight.contains("remember { AppPreferences.getHomeBillboardEnabled(context) }")
        )
        assertTrue(
            "and the gate renders nothing while it is off - no hero, no spacer",
            spotlight.contains("if (!billboardEnabled || pages.none { page -> page.settled }) return")
        )
    }

    // ── F3: dependent rows dim, keep out of the walk, and write nothing ───

    @Test
    fun `the dependent rows dim and stop taking the D-pad while it is off`() {
        val s = section()
        assertTrue(
            "the dependent block is dimmed as a whole",
            s.contains("Column(modifier = Modifier.alpha(if (billboardOn) 1f else 0.4f))")
        )
        assertTrue(
            "each dependent card leaves the focus walk",
            s.contains("Modifier.focusProperties { if (!billboardOn) canFocus = false }")
        )
        val flat = s.replace(Regex("\\s+"), " ")
        assertTrue(
            "and its press is inert while off",
            flat.contains("if (billboardOn) catalogsOpen = true") &&
                flat.contains("if (billboardOn) { cycleMs = option") &&
                flat.contains("if (billboardOn) { trailerMode = mode")
        )
    }

    @Test
    fun `turning it off clears no other billboard pref`() {
        val s = section()
        val toggle = s.substringAfter("listOf(false, true).forEach { value ->")
            .substringBefore("Off hides the Spotlight hero")
        assertTrue(
            "the toggle's own write is the only one it makes",
            toggle.contains("AppPreferences.setHomeBillboardEnabled(context, value)") &&
                !toggle.contains("setHomeBillboardFeeds") &&
                !toggle.contains("setHomeBillboardCycleMs") &&
                !toggle.contains("setHomeBillboardTrailerMode")
        )
        assertFalse(
            "and OFF clears no stored value anywhere in the section",
            s.contains("setHomeBillboardFeeds(context, emptyList())") ||
                s.contains("setHomeBillboardCycleMs(context, 0")
        )
    }

    // ── F4/F5: one global, device-level key ───────────────────────────────

    @Test
    fun `the pref is the global, device-level key on every profile`() {
        assertTrue(
            "one key, the one the phone's Home manager writes",
            prefs.contains("const val KEY_BILLBOARD_ENABLED = \"home_billboard_enabled\"")
        )
        val getter = prefs.substringAfter("fun getHomeBillboardEnabled(")
            .substringBefore("fun setHomeBillboardEnabled(")
        assertTrue(
            "read from the DEVICE store (not a profile's file), defaulting ON",
            getter.contains("deviceValue(context, KEY_BILLBOARD_ENABLED)") &&
                getter.contains("?: true")
        )
        val setter = prefs.substringAfter("fun setHomeBillboardEnabled(")
            .substringBefore("}")
        assertTrue(
            "and written to the same device store - so a profile switch cannot reset it",
            setter.contains("devicePrefs(context)") &&
                !setter.contains("profile")
        )
        assertTrue(
            "it is one of the six TV-scoped sync keys, not a local-only pref",
            prefs.contains("internal val DEVICE_SCOPED_KEYS: Set<String> = setOf(") &&
                prefs.substringAfter("internal val DEVICE_SCOPED_KEYS")
                    .substringBefore(")")
                    .contains("KEY_BILLBOARD_ENABLED")
        )
        assertTrue(
            "the section itself calls no sync layer",
            !section().contains("SupabaseSync") &&
                !section().contains("PrefsPayloadApplier") &&
                !section().contains("syncBillboard")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val at = text.indexOf(start)
        assertTrue("missing: $start", at >= 0)
        val rest = text.substring(at)
        val stop = rest.indexOf(end)
        return if (stop >= 0) rest.substring(0, stop) else rest
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val SPOTLIGHT = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
    }
}
