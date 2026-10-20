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
    fun `every window keeps the tagged build's landscape manifest lock`() {
        listOf(
            ".MainActivity",
            ".ui.player.NativePlayerActivity",
            ".ui.player.MpvPlayerActivity",
            ".ui.player.ExternalPlayerActivity"
        ).forEach { name ->
            assertTrue(
                "$name must keep android:screenOrientation=\"landscape\": that " +
                    "manifest value is the TV window policy, and the TV path is " +
                    "meant to be identical to the tagged build",
                activityBlock(name).contains("android:screenOrientation=\"landscape\"")
            )
        }
    }

    @Test
    fun `every window sets its orientation before the window exists`() {
        // Browsing follows the device; playback asks for landscape. Both are
        // phone-only and both must land BEFORE super.onCreate, or the window is
        // created in the old orientation and rotates a frame in.
        listOf(
            "com/kennyb1201/kbstream/MainActivity.kt" to
                "FormFactor.applyOrientation(this)",
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt" to
                "FormFactor.applyPlaybackOrientation(this)",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt" to
                "FormFactor.applyPlaybackOrientation(this)",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt" to
                "FormFactor.applyPlaybackOrientation(this)"
        ).forEach { (path, callText) ->
            val activity = source(path)
            val call = activity.indexOf(callText)
            val superCall = activity.indexOf("super.onCreate(savedInstanceState)")
            assertTrue(
                "$path must call $callText, or a phone has no orientation " +
                    "policy of its own",
                call >= 0
            )
            assertTrue(
                "$path must call it BEFORE super.onCreate; setting it after the " +
                    "window is created is what played a rotation on every screen",
                superCall >= 0 && call < superCall
            )
        }
    }

    @Test
    fun `the players release the landscape request on the way out`() {
        listOf(
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            val activity = source(path)
            val destroy = activity.indexOf("override fun onDestroy()")
            val release = activity.indexOf("FormFactor.releasePlaybackOrientation(this)")
            assertTrue("$path must have an onDestroy", destroy >= 0)
            assertTrue(
                "$path must release the playback orientation on exit, or Home " +
                    "stays landscape after the film",
                release > destroy
            )
        }
    }

    @Test
    fun `the helpers never touch a tv window`() {
        val formFactor = source("com/kennyb1201/kbstream/FormFactor.kt")

        fun body(name: String): String {
            val start = formFactor.indexOf("fun $name(activity: Activity)")
            assertTrue("FormFactor must declare $name", start >= 0)
            return formFactor.substring(start).substringBefore("\n    }")
        }

        listOf(
            "applyOrientation",
            "applyPlaybackOrientation",
            "releasePlaybackOrientation"
        ).forEach { name ->
            val function = body(name)
            val tvGuard = function.indexOf("if (isTv) return")
            val platformWrite = function.indexOf("activity.requestedOrientation")
            assertTrue("$name must return before touching the activity on TV", tvGuard >= 0)
            assertTrue(
                "$name's TV return must come before the platform write",
                platformWrite > tvGuard
            )
        }

        assertFalse(
            "browsing must not request landscape - that is the player's job, and " +
                "the TV manifest owns the television's lock",
            body("applyOrientation").contains("SCREEN_ORIENTATION_LANDSCAPE")
        )
        assertTrue(
            "the browse override is what clears the manifest lock",
            body("applyOrientation").contains("SCREEN_ORIENTATION_UNSPECIFIED")
        )
        assertTrue(
            "playback asks for sensor landscape, so hold-to-rotate still reaches portrait",
            body("applyPlaybackOrientation").contains("SCREEN_ORIENTATION_SENSOR_LANDSCAPE")
        )
        assertTrue(
            "and exiting hands the window back to the device's own sensor",
            body("releasePlaybackOrientation").contains("SCREEN_ORIENTATION_UNSPECIFIED")
        )
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
