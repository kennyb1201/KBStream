package com.kennyb1201.kbstream.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic color: the switch retargets the app's accent (spec 2026-11-16, Part A).
 *
 * The report was that turning it on changed nothing. That was true, and not
 * because the switch was unwired: it swapped the phone's Material 3 scheme for
 * the wallpaper's palette, and nothing in the app reads `colorScheme.primary` -
 * every screen reads [KBAccent], the picked accent. So the switch changed an
 * object no screen consults.
 *
 * The rule that fixes it is [DynamicAccentRules.resolve], and its four ways to be
 * wrong are pinned here: the accent must FOLLOW the wallpaper while the switch is
 * on, and must come back to the picked accent EXACTLY when it is off; a device
 * below Android 12 must be a no-op; and a palette that has not been published
 * yet must fall back rather than paint something invisible.
 *
 * The wiring either side of the rule is pinned from the source, the way the other
 * screen contract tests do it: the phone theme publishes the scheme's primary and
 * follows wallpaper changes, [KBAccent] reads the published value, and the
 * non-Compose accent (player chrome, guide rows) consults the same switch.
 */
class DynamicAccentContractTest {

    private val picked = 0xFF35C56B.toInt() // Emerald, the default accent
    private val wallpaper = 0xFF2C6BAA.toInt()

    // ── F1: ON follows the wallpaper, OFF is exactly the picked accent ──

    @Test
    fun `with the switch on the accent is the wallpaper's primary`() {
        assertEquals(
            wallpaper,
            DynamicAccentRules.resolve(
                applies = true,
                wallpaperPrimaryArgb = wallpaper,
                pickedArgb = picked
            )
        )
    }

    @Test
    fun `with the switch off the accent is the picked one, unchanged`() {
        // The OFF path has to be byte-identical (the constraint): the stored
        // accent was never written while the switch was on, so this is the whole
        // of the restore.
        assertEquals(
            picked,
            DynamicAccentRules.resolve(
                applies = false,
                wallpaperPrimaryArgb = wallpaper,
                pickedArgb = picked
            )
        )
        // And it holds even if a stale wallpaper colour is still lying around.
        assertEquals(
            picked,
            DynamicAccentRules.resolve(applies = false, wallpaperPrimaryArgb = wallpaper, pickedArgb = picked)
        )
    }

    @Test
    fun `an unpublished palette falls back to the picked accent, not to nothing`() {
        // The first pass of a composition, and the moment the switch is turned
        // off, both see a null primary. Painting that as "no accent" would be a
        // visible flash.
        assertEquals(
            picked,
            DynamicAccentRules.resolve(applies = true, wallpaperPrimaryArgb = null, pickedArgb = picked)
        )
    }

    // ── F4: pre-Android-12 is a no-op ──────────────────────────────────

    @Test
    fun `below android 12 the switch applies to nothing`() {
        assertFalse(DynamicAccentRules.applies(30, switchOn = true))
        assertFalse(DynamicAccentRules.applies(28, switchOn = true))
        assertTrue("Android 12 is the first with a palette", DynamicAccentRules.applies(31, true))
        assertTrue(DynamicAccentRules.applies(34, true))
        assertFalse("the switch itself still has to be on", DynamicAccentRules.applies(34, false))
    }

    // ── the wiring ─────────────────────────────────────────────────────

    @Test
    fun `the accent getter reads the published wallpaper colour`() {
        val theme = source("com/kennyb1201/kbstream/ui/theme/Theme.kt")
        assertTrue(
            "KBAccent has to resolve through the rule, or the switch changes nothing again",
            theme.contains("DynamicAccentRules.resolve(")
        )
        assertTrue(
            "and it has to be the state the phone theme publishes",
            theme.contains("wallpaperPrimaryArgb = kbDynamicAccentState.value?.toArgb()")
        )
        assertTrue(
            "the switch off must be able to win over a stale colour",
            theme.contains("applies = kbDynamicColorState.value")
        )
    }

    @Test
    fun `the phone theme publishes the scheme's primary and follows the wallpaper`() {
        val mobile = source("com/kennyb1201/kbstream/ui/theme/MobileTheme.kt")
        assertTrue(
            "the accent published is the scheme's own primary",
            mobile.contains("kbDynamicAccentState.value = if (dynamic) scheme.primary else null")
        )
        assertTrue(
            "and the scheme is recomputed when the wallpaper changes (F2)",
            mobile.contains("rememberWallpaperRevision(")
        )
        assertTrue(
            "which is a real broadcast hook, not a placeholder",
            mobile.contains("Intent.ACTION_WALLPAPER_CHANGED")
        )
        assertTrue(
            "the publish is an effect, never a write during composition",
            mobile.contains("SideEffect {\n        kbDynamicAccentState.value")
        )
    }

    @Test
    fun `the non-compose accent follows the same switch`() {
        val theme = source("com/kennyb1201/kbstream/ui/theme/Theme.kt")
        val accentFn = theme.substringAfter("fun themeAccentColor(context: Context): Int {")
            .substringBefore("\n}\n")
        assertTrue(
            "the player chrome and guide rows read this int, so it has to move too",
            accentFn.contains("AppPreferences.getDynamicColor(context)")
        )
        assertTrue(
            "through the same rule, so the two halves cannot disagree",
            accentFn.contains("DynamicAccentRules")
        )
    }

    @Test
    fun `AMOLED still owns the black`() {
        // F3: the black policy is untouched - the wallpaper tints the accent and
        // nothing else, so these overrides have to still be there.
        val mobile = source("com/kennyb1201/kbstream/ui/theme/MobileTheme.kt")
        val amoledScheme = mobile.substringAfter("private fun dynamicScheme(")
            .substringBefore("\n}\n")
        listOf("background = KBVoid", "surface = KBSurface", "surfaceVariant = KBSurfaceRaised")
            .forEach { slot ->
                assertTrue("the AMOLED override must still force $slot", amoledScheme.contains(slot))
            }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun source(relative: String): String {
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
}
