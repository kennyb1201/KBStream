package com.kennyb1201.kbstream.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Material 3 Expressive on the phone, and the TV's identity contract around it.
 *
 * The pass (spec 2026-11-22) has one non-negotiable: the TV must render exactly
 * as it does today. Five things make that true, and each is one careless commit
 * away from being false:
 *
 *  - material3 1.5.0 is the only dependency that moves, and the BOM is not
 *    bumped with it (F1). If the BOM moved, every Compose library would move and
 *    the TV's rendering would be recompiled against a different foundation;
 *  - `MobileTheme` is the ONLY Material 3 theme provider in the app (F2/F3).
 *    The TV composes inside tv-material3's `KBStreamTheme` with no M3 provider
 *    above it - which is exactly why a shared component that reads M3's
 *    typography, `KBQrCode`, keeps M3's defaults there;
 *  - the expressive slots are additions, and the five slots that exist today
 *    keep their KB radii, so nothing that asks the theme now changes corner;
 *  - the emphasized type is DERIVED from the TV scale, so a change to the brand
 *    scale cannot leave the phone's headline styles behind;
 *  - `Theme.kt` - the TV theme - does not name any of it.
 *
 * Pinned as source because the surfaces need a phone and a TV; the strings are
 * the shapes the compiler checks, not prose.
 */
class Material3ExpressiveContractTest {

    private val mobileTheme by lazy { source("com/kennyb1201/kbstream/ui/theme/MobileTheme.kt") }
    private val tvTheme by lazy { source("com/kennyb1201/kbstream/ui/theme/Theme.kt") }
    private val catalog by lazy { rootFile("gradle/libs.versions.toml").readText() }

    // ── F1: the dependency gate ──────────────────────────────────────────

    @Test
    fun `material3 is pinned to 1_5_0 and the BOM is not bumped with it`() {
        assertTrue(
            "1.5.0 is where the expressive theme, the motion scheme and the " +
                "emphasized type / increased shape slots became public",
            catalog.contains("material3 = \"1.5.0\"")
        )
        assertTrue(
            "the module names that version instead of taking the BOM's",
            catalog.contains(
                "androidx-compose-material3 = { module = \"androidx.compose.material3:material3\", " +
                    "version.ref = \"material3\" }"
            )
        )
        assertTrue(
            "and the BOM keeps the version the TV is compiled against: 1.5.0 asks " +
                "compose-ui 1.12.0, which this BOM already resolves past",
            catalog.contains("compose-bom = \"2026.09.00\"")
        )
    }

    // ── F2: the theme core ───────────────────────────────────────────────

    @Test
    fun `the phone composes the expressive wrapper with its own scheme and motion`() {
        val call = mobileTheme
            .substringAfter("MaterialExpressiveTheme(")
            .substringBefore("content = content")
        assertTrue(
            "the scheme above it is untouched and is what is passed in",
            call.contains("colorScheme = scheme,")
        )
        assertTrue(
            "the expressive motion scheme, so F10's call sites can read it",
            call.contains("motionScheme = MotionScheme.expressive(),")
        )
        assertTrue(
            "and the app's own type and shapes",
            call.contains("typography = rememberBrandTypography(),") &&
                call.contains("shapes = KBStreamMobileShapes,")
        )
        assertFalse(
            "no module-wide opt-in: 1.5.0 does not require one for what is used here",
            mobileTheme.contains("@file:OptIn")
        )
    }

    @Test
    fun `MobileTheme is the only Material 3 theme provider in the app`() {
        // The TV's provider is tv-material3's - a different MaterialTheme class -
        // and it must stay the only one there is.
        val providers = mainSources()
            .filter { (_, text) ->
                Regex("(?m)^\\s*MaterialExpressiveTheme\\(").containsMatchIn(text) ||
                    (Regex("(?m)^\\s*MaterialTheme\\(").containsMatchIn(text) &&
                        Regex("import androidx\\.compose\\.material3\\.MaterialTheme").containsMatchIn(text))
            }
            .map { (name, _) -> name }
        assertEquals(
            "exactly one M3 provider, in MobileTheme.kt",
            listOf("MobileTheme.kt"),
            providers
        )
        assertTrue(
            "the TV theme really is the tv-material3 one, so it is not a second M3 " +
                "provider: adding one at the shell root is what would move the TV's type",
            tvTheme.contains("import androidx.tv.material3.MaterialTheme") &&
                !tvTheme.contains("import androidx.compose.material3.MaterialTheme")
        )
    }

    @Test
    fun `the expressive shapes are additions and the existing radii do not move`() {
        // Read from the declaration, which is the only place these slot names
        // can appear in this file.
        assertTrue(
            "the five slots the theme already answered with keep their KB radii",
            mobileTheme.contains("extraSmall = KBShapeSmall,") &&
                mobileTheme.contains("small = KBShapeCard,") &&
                mobileTheme.contains("medium = KBShapeCard,") &&
                mobileTheme.contains("large = KBShapePanel,") &&
                mobileTheme.contains("extraLarge = KBShapePanel,")
        )
        assertTrue(
            "and the three increased slots step up from the app's panel radius",
            mobileTheme.contains("largeIncreased = RoundedCornerShape(24.dp),") &&
                mobileTheme.contains("extraLargeIncreased = RoundedCornerShape(28.dp),") &&
                mobileTheme.contains("extraExtraLarge = RoundedCornerShape(32.dp)")
        )
        assertFalse(
            "Theme.kt is not edited by this pass: the TV's corner scale is shared",
            tvTheme.contains("largeIncreased") || tvTheme.contains("extraExtraLarge")
        )
    }

    @Test
    fun `the emphasized type is derived from the brand scale, never restated`() {
        val emphasizedSlots = listOf(
            "displayLargeEmphasized", "displayMediumEmphasized", "displaySmallEmphasized",
            "headlineLargeEmphasized", "headlineMediumEmphasized", "headlineSmallEmphasized",
            "titleLargeEmphasized", "titleMediumEmphasized", "titleSmallEmphasized",
            "bodyLargeEmphasized", "bodyMediumEmphasized", "bodySmallEmphasized",
            "labelLargeEmphasized", "labelMediumEmphasized", "labelSmallEmphasized"
        )
        emphasizedSlots.forEach { slot ->
            assertTrue("$slot is filled from the brand scale", mobileTheme.contains("$slot = tv."))
        }
        assertTrue(
            "display/headline/title are the emphasized cut of their TV slot",
            mobileTheme.contains("displayLargeEmphasized = tv.displayLarge.emphasized(),") &&
                mobileTheme.contains("headlineLargeEmphasized = tv.headlineLarge.emphasized(),") &&
                mobileTheme.contains("titleLargeEmphasized = tv.titleLarge.emphasized(),") &&
                // Body and label keep the brand style verbatim: M3's own
                // emphasized body is heavier than the plate it sits on was
                // designed for, and metadata should read the same at any distance.
                mobileTheme.contains("bodyMediumEmphasized = tv.bodyMedium,") &&
                mobileTheme.contains("labelLargeEmphasized = tv.labelLarge,")
        )
        assertFalse(
            "no literal type sizes: the fork reads the brand scale or nothing",
            mobileTheme.contains("fontSize =") || mobileTheme.contains("lineHeight = ")
        )
        assertTrue(
            "and the TV scale itself is untouched",
            !tvTheme.contains("Emphasized") && !tvTheme.contains("MotionScheme")
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Every `.kt` file under the main source set, by file name. */
    private fun mainSources(): List<Pair<String, String>> {
        val root = mainSourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }

    private fun source(relative: String): String {
        val file = File(mainSourceRoot(), relative)
        assertTrue("source missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun rootFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found walking up from ${System.getProperty("user.dir")}")
    }
}
