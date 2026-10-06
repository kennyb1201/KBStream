package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SD-2: a failed launch must forget the cached link in the SESSION's profile,
 * not the currently-active one - a mid-playback profile switch otherwise leaves
 * the launching profile's dead entry to loop until the TTL. Plus the PB-P2-8
 * doc correction on the buffering splash.
 */
class PlayedLinkProfileContractTest {

    @Test
    fun `the cache can forget by an explicit profile`() {
        val src = source(PLAYED)
        assertTrue(
            "the cache must offer a profile-scoped forget",
            src.contains("fun forgetForProfile(context: Context, key: String, profileId: String?)")
        )
        assertTrue(
            "and resolve that profile's own store",
            src.contains("ProfileStorage.prefsName(profileId, PREFS_BASE)")
        )
    }

    @Test
    fun `both players forget against the session's launch profile`() {
        listOf(NATIVE, MPV).forEach { path ->
            assertTrue(
                "$path must forget in the pinned session profile",
                source(path).contains(
                    "PlayedLinkCache.forgetForProfile(this, cacheKey, sessionProfileId)"
                )
            )
        }
    }

    @Test
    fun `the buffering doc no longer claims every switch resets the latch`() {
        assertTrue(
            "the doc must match the auto-switch behaviour",
            source(NATIVE).contains("a MANUAL source switch resets the")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
        const val PLAYED = "com/kennyb1201/kbstream/data/player/PlayedLinkCache.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
