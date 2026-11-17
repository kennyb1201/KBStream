package com.kennyb1201.kbstream.data.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a player trims the bitmap cache instead of clearing it (dig four,
 * 2026-11-16, §3f rank 7).
 *
 * Both engines ran a FULL `releaseImageMemoryCache` in `onCreate`, so every
 * playback - not just one taken under memory pressure - made the return to Home
 * re-decode the hero and the rails from disk. The player's own comment says why
 * it wanted the headroom: media3's sample buffer and the codec's native
 * allocations land on top of whatever browsing left resident.
 *
 * Half a 64MB cap's worth, evicted by Coil's LRU (eldest first), is the same
 * order of headroom and keeps the recently used art - exactly the art the
 * return draws. The browsing caches still go: they are cheap to rebuild and the
 * player loads what art it needs itself.
 */
class PlayerOpenTrimContractTest {

    private val native by lazy { source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt") }
    private val mpv by lazy { source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt") }

    private fun onCreateOpening(source: String): String =
        source.substringAfter("override fun onCreate(")
            .substringBefore("setContentView(")

    @Test
    fun `both engines trim rather than clear before playback`() {
        listOf("exo" to native, "mpv" to mpv).forEach { (name, source) ->
            val opening = onCreateOpening(source)
            assertTrue(
                "$name: the graded trim, so the return does not re-decode everything",
                opening.contains("trimImageMemoryCache(this)")
            )
            assertFalse(
                "$name: a full clear on every playback is the resume cost this replaces",
                opening.contains("releaseImageMemoryCache(this)")
            )
            assertTrue(
                "$name: the browsing release stays - cheap to rebuild, not on screen",
                opening.contains("MemoryPressure.releaseBrowsingCaches()")
            )
            assertFalse(
                "$name: and the full-clear helper is not even imported any more",
                source.contains("import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache")
            )
        }
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
