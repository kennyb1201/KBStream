package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which surfaces may be pure black, and which have to be the app's own void.
 *
 * KBVoid is a state-backed token: it is `#0A0E14` normally and pure black while
 * the AMOLED toggle is on, so every screen that paints it follows the toggle
 * for free. A screen that hardcodes black instead stops following anything, and
 * the WINDOW background (a static theme resource, painted before Compose
 * exists) could not follow it either - so an ordinary launch put a black
 * surface under every screen, and an AMOLED launch flashed the ordinary tone
 * first.
 *
 * Home is the one deliberate exception: it paints its own black, toggle or not.
 * That is a design decision, and it is exactly why it has to be the ONLY one -
 * the moment a second screen hardcodes black, the toggle silently stops
 * covering it and the two screens disagree about what "black" means.
 *
 * Source-level on purpose; a television is the only place this shows up.
 */
class BackgroundToneContractTest {

    private val mainDir: File by lazy { findMainDir() }

    /**
     * Resolves `…/src/main` from the test's working directory, which is the
     * module dir under Gradle (`app/`) but the repo root under some runners.
     * Walking up covers both; a miss is loud rather than a silent skip, because
     * a green run that read nothing is worse than no test.
     */
    private fun findMainDir(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun uiSources(): List<File> =
        File(mainDir, "java/com/kennyb1201/kbstream/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun screenSource(relative: String): String {
        val file = File(mainDir, "java/com/kennyb1201/kbstream/ui/$relative")
        assertTrue("screen source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `home is the only screen that paints black on its own`() {
        // Only the plain background call: `Color.Black.copy(alpha = …)` is a
        // scrim over artwork, which is a different thing and stays allowed.
        val blackRoots = uiSources()
            .filter { it.readText().contains(".background(Color.Black)") }
            .map { it.name }
            .distinct()
            .sorted()
        assertEquals(
            "without AMOLED, Home is the app's only black screen - every other " +
                "surface has to paint KBVoid so the toggle can reach it",
            listOf("HomeScreen.kt"),
            blackRoots
        )
    }

    @Test
    fun `the library paints the app background`() {
        assertTrue(
            "Library's root must use KBVoid, not pure black: a black root is " +
                "invisible to the AMOLED toggle (black either way)",
            screenSource("library/LibraryScreen.kt").contains(".background(KBVoid)")
        )
    }

    @Test
    fun `the launch window follows the AMOLED toggle`() {
        val themes = File(mainDir, "res/values/themes.xml")
        assertTrue("theme resource missing: $themes", themes.isFile)
        assertFalse(
            "a static pure-black window puts a black surface under every screen " +
                "at launch, which is the rule this guards (Home only)",
            themes.readText().contains("#000000")
        )

        val activity = File(mainDir, "java/com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue("MainActivity missing: $activity", activity.isFile)
        val activitySource = activity.readText()
        assertTrue(
            "MainActivity must re-point the window background at the theme's tone, " +
                "before the first composition",
            activitySource.contains("window.setBackgroundDrawable(") &&
                activitySource.contains("themeVoidWindowColor(this)")
        )

        val theme = File(mainDir, "java/com/kennyb1201/kbstream/ui/theme/Theme.kt")
            .readText()
        assertTrue(
            "themeVoidWindowColor must mirror KBVoid's palette off the STORED " +
                "AMOLED toggle (the window is painted before Compose exists)",
            theme.contains("AppPreferences.getAmoledBlack(context)") &&
                theme.contains("KBVoidAmoled.toArgb()") &&
                theme.contains("KBVoidDefault.toArgb()")
        )
    }
}
