package com.kennyb1201.kbstream.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom accent: the colour the viewer MIXES on the theme picker's three
 * bars.
 *
 * Three things about it are decisions rather than layout, and each has one way
 * to be silently wrong:
 *
 *  - the bars have to be a faithful model of the colour - a colour taken out to
 *    hue / saturation / brightness and put back has to come home unchanged, or
 *    reopening Settings quietly darkens the accent a notch at a time;
 *  - the sentinel index has to resolve to the mixed colour when there is one
 *    and to the palette default when there is not, so an index synced from a
 *    device that has a custom colour cannot leave this one accentless;
 *  - the "unset" value must not be mistaken for a colour (ARGB 0 is fully
 *    transparent black).
 *
 * [hsvFromArgb], [argbFromHsv], [accentForIndex] and [customAccentColorOrNull]
 * are pure, so those are pinned directly; the wiring - that the picker mixes on
 * bars, that the store writes the ARGB and pushes it to the synced display
 * blob, and that the theme mirrors it - is pinned from the source, the way the
 * other screen contract tests do it.
 */
class ThemeAccentTest {

    // ── the bars ──────────────────────────────────────────────────────────

    @Test
    fun `the primaries come off the bars exactly`() {
        assertEquals(0xFFFF0000.toInt(), argbFromHsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), argbFromHsv(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), argbFromHsv(240f, 1f, 1f))
    }

    @Test
    fun `a bar that is out of range is clamped, and the hue wraps`() {
        // A step's arithmetic lands wherever it lands; the bars have to absorb
        // it rather than paint something nobody asked for.
        assertEquals(0xFFFF0000.toInt(), argbFromHsv(360f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), argbFromHsv(720f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), argbFromHsv(0f, 4f, 4f))
        assertEquals(argbFromHsv(300f, 1f, 1f), argbFromHsv(-60f, 1f, 1f))
        // Zero brightness is black at any hue - the bar's own left end.
        assertEquals(0xFF000000.toInt(), argbFromHsv(200f, 1f, 0f))
    }

    @Test
    fun `a grey has no hue and reports its brightness alone`() {
        val grey = hsvFromArgb(0xFF808080.toInt())
        assertEquals(0f, grey.hue, 0.0001f)
        assertEquals(0f, grey.saturation, 0.0001f)
        assertEquals(0xFF808080.toInt(), argbFromHsv(grey.hue, grey.saturation, grey.brightness))
    }

    @Test
    fun `every palette colour survives the bars and back`() {
        // The bars are seeded from whatever accent is live, so a palette colour
        // that did not round trip would drift the moment the viewer touched a
        // bar after picking it.
        KBAccentPalette.forEach { entry ->
            val argb = argbOf(entry.color)
            val bars = hsvFromArgb(argb)
            assertEquals(
                "${entry.name} must come home unchanged",
                argb,
                argbFromHsv(bars.hue, bars.saturation, bars.brightness)
            )
        }
    }

    // ── the stored value ──────────────────────────────────────────────────

    @Test
    fun `zero is unset, not transparent black`() {
        assertNull(customAccentColorOrNull(0))
        assertEquals(Color(0xFFE8A33D), customAccentColorOrNull(0xFFE8A33D.toInt()))
    }

    @Test
    fun `the bars round-trip a fixed colour, and the read-out names it`() {
        val argb = 0xFF6A3D2F.toInt()
        val bars = hsvFromArgb(argb)
        assertEquals(argb, argbFromHsv(bars.hue, bars.saturation, bars.brightness))
        // Shown, never typed into: it is there for the viewer who does know
        // colour codes, not as the way to choose one.
        assertEquals("#6A3D2F", customAccentHexText(argb))
        assertEquals("", customAccentHexText(0))
        assertTrue(customAccentHexText(argb).startsWith("#"))
    }

    // ── the resolution ────────────────────────────────────────────────────

    @Test
    fun `a palette index resolves to its palette colour`() {
        assertEquals(KBAccentPalette[0].color, accentForIndex(0, null))
        assertEquals(KBAccentPalette[3].color, accentForIndex(3, null))
    }

    @Test
    fun `the custom index resolves to the mixed colour`() {
        val custom = Color(0xFF123456)
        assertEquals(custom, accentForIndex(CUSTOM_ACCENT_INDEX, custom))
    }

    @Test
    fun `the custom index without a colour falls back to the default`() {
        // An index synced from a device that has a custom colour must not leave
        // this one with no accent at all.
        assertEquals(
            KBAccentPalette[DEFAULT_ACCENT_INDEX].color,
            accentForIndex(CUSTOM_ACCENT_INDEX, null)
        )
    }

    @Test
    fun `an index past the palette falls back instead of throwing`() {
        assertEquals(
            KBAccentPalette[DEFAULT_ACCENT_INDEX].color,
            accentForIndex(KBAccentPalette.size + 5, null)
        )
        assertEquals(
            KBAccentPalette[DEFAULT_ACCENT_INDEX].color,
            accentForIndex(-1, null)
        )
    }

    @Test
    fun `the sentinel sits one past the palette so stored indices keep their meaning`() {
        assertEquals(KBAccentPalette.size, CUSTOM_ACCENT_INDEX)
    }

    // ── the wiring ────────────────────────────────────────────────────────

    @Test
    fun `the picker mixes the colour on bars instead of typing a colour code`() {
        val settings = read(SETTINGS)
        assertTrue(
            "the picker must offer a Custom swatch that selects the sentinel",
            settings.contains("onSelect(CUSTOM_ACCENT_INDEX)")
        )
        assertTrue(
            "left and right must step the focused bar rather than move focus",
            settings.contains("Key.DirectionLeft") && settings.contains("Key.DirectionRight")
        )
        assertTrue(
            "the colour must come from the pure bar maths",
            settings.contains("argbFromHsv(hue.toFloat(), saturation / 100f, brightness / 100f)")
        )
        assertTrue(
            "a bar step must store the mixed colour, not just hold it in state",
            settings.contains("AppPreferences.setCustomAccent(context, mixedColor())")
        )
        assertFalse(
            "the picker must no longer ask the viewer to type a colour code",
            settings.contains("parseHexColor")
        )
    }

    @Test
    fun `the custom colour is stored, synced, and resolved by the XML path too`() {
        val prefs = read(PREFS)
        assertTrue(
            "the ARGB must be persisted through the sync-safe Int reader",
            prefs.contains("readIntPref(context, KEY_CUSTOM_ACCENT, 0)")
        )
        assertTrue(
            "riding the synced display blob is what puts the colour on the " +
                "viewer's other TVs - a setter that never pushes is a silent no-op",
            functionBody(prefs, "fun setCustomAccent(").contains("syncDisplayPrefsBlob(context)")
        )
        val theme = read(THEME)
        assertTrue(
            "the live theme state must mirror the stored custom colour",
            theme.contains("customAccentColorOrNull(AppPreferences.getCustomAccent(context))")
        )
        assertTrue(
            "the XML-tint path (themeAccentColor) must resolve the custom index " +
                "as well, or the player chrome and guide rows keep the old accent",
            theme.contains("if (index == CUSTOM_ACCENT_INDEX)")
        )
    }

    /**
     * The ARGB of a Compose colour, read out without android.graphics in the
     * way: the packed value keeps the 8-bit colour in its top word, which is
     * exactly what `Color.toArgb()` reads for an sRGB colour - and unlike that
     * call this cannot run into a platform API the unit tests have stubbed out.
     */
    private fun argbOf(color: Color): Int = (color.value shr 32).toInt()

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val THEME = "com/kennyb1201/kbstream/ui/theme/Theme.kt"
    }
}
