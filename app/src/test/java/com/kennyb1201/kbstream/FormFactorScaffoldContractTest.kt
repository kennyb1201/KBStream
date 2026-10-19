package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 0 seams a JVM test cannot press: the manifest, the four activities
 * that own a window, and the one place the form factor is resolved.
 *
 * These are exactly the existing files the mobile port is allowed to touch, so
 * they are also the places a later edit could quietly take the TV app back to
 * a phone-only manifest or a rotating TV window. Source text is the only thing
 * that can see them (in the style of [LaunchSplashWiringContractTest]).
 */
class FormFactorScaffoldContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }
    private val mainRoot: File by lazy {
        requireNotNull(sourceRoot.parentFile) { "no src/main beside ${sourceRoot.path}" }
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun manifest(): String {
        val file = File(mainRoot, "AndroidManifest.xml")
        assertTrue("manifest missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    /** The activity block, so an assertion cannot match a neighbouring one. */
    private fun activityBlock(name: String): String =
        manifest()
            .substringAfter("<activity android:name=\"$name\"")
            .substringBefore("</activity>")

    @Test
    fun `no window is pinned to a landscape manifest orientation`() {
        assertFalse(
            "a manifest orientation applies to every form factor; orientation " +
                "is FormFactor.applyOrientation's job now",
            manifest().contains("android:screenOrientation")
        )
    }

    @Test
    fun `every window-owning activity asks the form factor for its orientation`() {
        listOf(
            "com/kennyb1201/kbstream/MainActivity.kt",
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            assertTrue(
                "$path must call FormFactor.applyOrientation(this), or that " +
                    "activity has no orientation policy at all",
                source(path).contains("FormFactor.applyOrientation(this)")
            )
        }
    }

    @Test
    fun `the form factor is resolved once at process start`() {
        assertTrue(
            "MainApplication must resolve the form factor before any activity " +
                "exists, so the cached answer is the process's answer",
            source("com/kennyb1201/kbstream/MainApplication.kt")
                .contains("FormFactor.init(this)")
        )
    }

    @Test
    fun `the launcher stays installable on a phone and a TV`() {
        val main = activityBlock(".MainActivity")
        assertTrue(
            "resizeableActivity is manifest-only; a phone needs it for " +
                "multi-window and foldable continuity",
            main.contains("android:resizeableActivity=\"true\"")
        )
        assertTrue(
            "the phone launcher entry must stay",
            main.contains("android.intent.category.LAUNCHER")
        )
        assertTrue(
            "the leanback entry must stay",
            main.contains("android.intent.category.LEANBACK_LAUNCHER")
        )
        assertTrue(
            "leanback must stay non-required, or Play filters the app off phones",
            manifest().contains(
                "<uses-feature android:name=\"android.software.leanback\" android:required=\"false\" />"
            )
        )
    }

    @Test
    fun `the television theme is still the tv-material one`() {
        // The mobile port adds a second theme; it must never replace this one.
        val theme = source("com/kennyb1201/kbstream/ui/theme/Theme.kt")
        assertTrue(
            "Theme.kt must keep importing androidx.tv.material3.MaterialTheme",
            theme.contains("import androidx.tv.material3.MaterialTheme")
        )
        assertTrue(
            "KBStreamTheme must still wrap the television MaterialTheme",
            theme.substringAfter("fun KBStreamTheme(").contains("MaterialTheme(")
        )
    }
}
