package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's pre-playback splash must be sideways from the moment it appears.
 *
 * The splash is a screen of MainActivity's own window, not the player's, so the
 * viewing orientation has to be held by MainActivity for the splash's lifetime
 * and released on every way out. Two things make that possible and neither is
 * visible to a JVM test any other way:
 *
 *  1. MainActivity declares the rotation in its manifest `configChanges`, or the
 *     request re-creates the activity mid-resolve and drops the in-flight
 *     autoplay (its pending target is composition state) - the flip comes back;
 *  2. the hold follows the splash's visibility (apply while it shows, release
 *     when it goes), so a tap / back / failed resolve cannot strand it either
 *     way.
 */
class AutoplaySplashOrientationContractTest {

    private val mainRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(mainRoot, "java/$path")
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun mainActivityBlock(): String {
        val file = File(mainRoot, "AndroidManifest.xml")
        assertTrue("manifest missing: $file", file.isFile)
        return file.readText()
            .replace(Regex("\\s+"), " ")
            .substringAfter("<activity android:name=\".MainActivity\"")
            .substringBefore("</activity>")
    }

    @Test
    fun `the splash's rotation does not re-create the activity`() {
        val activity = mainActivityBlock()
        val configChanges = activity.substringAfter("android:configChanges=\"")
            .substringBefore("\"")
        listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout").forEach {
            assertTrue(
                "MainActivity must handle $it itself: it asks for playback " +
                    "orientation while its splash is up, and a re-create there " +
                    "aborts the resolve the splash is waiting on",
                configChanges.split("|").contains(it)
            )
        }
        assertTrue(
            "and the manifest landscape lock stays: that is the TV policy",
            activity.contains("android:screenOrientation=\"landscape\"")
        )
    }

    @Test
    fun `the hold follows the splash's visibility and is released on the way out`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "the hold must key on the splash itself - both MainActivity-hosted " +
                "shapes and the player cover - not on a tap handler",
            activity.contains(
                "val splashShowing = " +
                    "detailAutoPlay != null || pending != null || playerSplash != null"
            )
        )

        val effect = activity.indexOf("DisposableEffect(splashActivity, splashShowing)")
        assertTrue("no splash-driven orientation effect", effect >= 0)
        val body = activity.substring(effect).substringBefore("\n    }")

        val apply = body.indexOf("FormFactor.applyPlaybackOrientation(splashActivity)")
        val dispose = body.indexOf("onDispose")
        val release = body.indexOf("FormFactor.releasePlaybackOrientation(splashActivity)")
        assertTrue("the splash must ask for landscape while it shows", apply >= 0)
        assertTrue("the hold must be released when the splash goes", dispose >= 0)
        assertTrue(
            "the release belongs on the exit, after the apply",
            release > dispose && dispose > apply
        )
    }
}
