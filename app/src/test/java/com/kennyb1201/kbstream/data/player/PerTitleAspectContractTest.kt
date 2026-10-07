package com.kennyb1201.kbstream.data.player

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aspect/zoom remembered per show, in both engines.
 *
 * The split is the one the languages already use: an in-player aspect control
 * writes the SHOW's override, while Settings' aspect row keeps writing the
 * global default that shows without an override follow. The parts that fail
 * silently are pinned here by reading the source - a player that still writes
 * the global pref (so one show's Fill becomes every show's Fill), a blob that
 * loses its override on the next track change, and a legacy blob that fails to
 * read. The default is checked for real, by deserializing one.
 */
class PerTitleAspectContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a legacy blob without the field reads as follow-the-global`() {
        // Every blob written before this feature, and every new show.
        val prefs = json.decodeFromString(
            PlayerTitlePrefs.Prefs.serializer(),
            """{"audioLang":"en"}"""
        )
        assertEquals(-1, prefs.aspectRatio)
    }

    @Test
    fun `an aspect-only override is not dropped as empty`() {
        val prefs = PlayerTitlePrefs.Prefs(aspectRatio = 2)
        assertFalse(
            "a show whose only remembered choice is its aspect must persist",
            prefs.isEmpty
        )
        assertTrue(PlayerTitlePrefs.Prefs().isEmpty)
    }

    @Test
    fun `aspect round-trips through the blob`() {
        val encoded = json.encodeToString(
            PlayerTitlePrefs.Prefs.serializer(),
            PlayerTitlePrefs.Prefs(aspectRatio = 1)
        )
        val decoded = json.decodeFromString(PlayerTitlePrefs.Prefs.serializer(), encoded)
        assertEquals(1, decoded.aspectRatio)
    }

    @Test
    fun `the ExoPlayer session opens on the remembered mode, else the global`() {
        val native = read(NATIVE_ACTIVITY)
        assertTrue(
            "the title key is the show's own id, so an episode inherits its show",
            native.contains("PlayerTitlePrefs.titleKeyFor(parentId, historyId)")
        )
        assertTrue(
            "an override wins; anything else follows Settings",
            native.contains("PlayerTitlePrefs.get(this, titleKey)?.aspectRatio") &&
                native.contains("?.takeIf { it >= 0 }") &&
                native.contains("?: AppPreferences.getDefaultAspectRatio(this)")
        )
    }

    @Test
    fun `the ExoPlayer aspect controls write the show, never the global default`() {
        val native = read(NATIVE_ACTIVITY)
        assertFalse(
            "an in-player aspect press must not overwrite every other show's default",
            native.contains("AppPreferences.setDefaultAspectRatio(this, resizeModeIndex)")
        )
        assertEquals(
            "both the cycle button and the pill selection remember per title",
            2,
            Regex("\\.copy\\(aspectRatio = resizeModeIndex\\)").findAll(native).count()
        )
    }

    @Test
    fun `the MPV session opens on the remembered mode and writes it per title`() {
        val mpv = read(MPV_ACTIVITY)
        assertTrue(
            "an override wins; anything else follows Settings",
            mpv.contains("remembered?.aspectRatio?.takeIf { it >= 0 }")
        )
        assertTrue(
            "choosing an aspect remembers it for the show",
            mpv.contains(".copy(aspectRatio = index)")
        )
        assertFalse(
            "the in-player picker must not overwrite the global default",
            mpv.contains("AppPreferences.setDefaultAspectRatio(this, index)")
        )
    }

    @Test
    fun `a later track change does not wipe the aspect override`() {
        // Both persistence paths rebuild the Prefs object from their own state,
        // which knows nothing about aspect - so each must carry the stored
        // value through instead of resetting it to -1.
        assertTrue(read(BRIDGE).contains("val storedAspect = PlayerTitlePrefs.get(context, key)?.aspectRatio ?: -1"))
        assertTrue(read(BRIDGE).contains("aspectRatio = storedAspect"))

        val mpv = read(MPV_ACTIVITY)
        assertTrue(mpv.contains("val storedAspect = PlayerTitlePrefs.get(this, key)?.aspectRatio ?: -1"))
        assertTrue(mpv.contains("aspectRatio = storedAspect"))
    }

    @Test
    fun `the per-show store is still profile-scoped and never synced`() {
        // The aspect override rides in the same device-local, profile-scoped
        // blob as the languages; nothing here pushes it anywhere.
        val prefs = read(PREFS)
        assertTrue(prefs.contains("ProfileStorage.prefsName(context.applicationContext, PREFS_BASE)"))
    }

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
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
        const val PREFS = "com/kennyb1201/kbstream/data/player/PlayerTitlePrefs.kt"
        const val BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val NATIVE_ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
