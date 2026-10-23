package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's pre-playback splash keeps the browsing orientation.
 *
 * The splash is a screen of MainActivity's own window, not the player's. It used
 * to ask for landscape for its whole lifetime so it matched the player about to
 * cover it, and the field read that as a fault: a Play press threw the loading
 * cover up sideways - widescreen art cropped into a frame the rest of the app
 * never shows - and it then had to rotate back once the hold was released.
 *
 * So the splash now follows the device exactly as every browse screen does
 * ([FormFactor.applyOrientation]): portrait on a phone held upright, sideways
 * only when the phone itself is. The single rotation belongs at the hand-off to
 * the player, which asks for landscape before its own window exists. Two things
 * make that possible and neither is visible to a JVM test any other way:
 *
 *  1. MainActivity does NOT call `applyPlaybackOrientation` for its splash, so
 *     no code path can turn the loading cover sideways;
 *  2. MainActivity declares the rotation in its manifest `configChanges`, so
 *     turning the phone while the splash is up (or while its resolve is in
 *     flight) does not re-create the activity and drop the pending autoplay.
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
    fun `the browsing activity never asks for the playback orientation`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        assertFalse(
            "the pre-playback splash is a screen of the browsing window and " +
                "must keep the browsing orientation: holding landscape for it is " +
                "what put the loading cover sideways on a phone held upright",
            activity.contains("applyPlaybackOrientation")
        )
        assertFalse(
            "and nothing there may release it either - a release without a hold " +
                "is a request of its own",
            activity.contains("releasePlaybackOrientation")
        )
        assertTrue(
            "the browse policy itself stays: follows the device on a phone, " +
                "before super.onCreate so no window rotates a frame in",
            activity.contains("FormFactor.applyOrientation(this)")
        )
    }

    @Test
    fun `a rotation under the splash does not re-create the activity`() {
        val activity = mainActivityBlock()
        val configChanges = activity.substringAfter("android:configChanges=\"")
            .substringBefore("\"")
        listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout").forEach {
            assertTrue(
                "MainActivity must handle $it itself: the splash follows the " +
                    "device now, and a re-create mid-resolve aborts the autoplay " +
                    "the splash is waiting on",
                configChanges.split("|").contains(it)
            )
        }
        assertTrue(
            "and the manifest landscape lock stays: that is the TV policy",
            activity.contains("android:screenOrientation=\"landscape\"")
        )
    }

    @Test
    fun `the player still asks for landscape of its own`() {
        listOf(
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            assertTrue(
                "$path must still request the playback orientation, or the " +
                    "picture arrives in a portrait window",
                source(path).contains("FormFactor.applyPlaybackOrientation(this)")
            )
        }
    }
}
