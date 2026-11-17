package com.kennyb1201.kbstream.data.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Opening a player frees nothing (round four F4, 2026-11-16).
 *
 * Both engines ran a FULL `releaseImageMemoryCache` + `releaseBrowsingCaches()`
 * in `onCreate`, so every playback - not just one taken under memory pressure -
 * made the return to Home re-decode the hero and the rails from disk. The
 * player's own comment says why it wanted the headroom: media3's sample buffer
 * and the codec's native allocations land on top of whatever browsing left
 * resident.
 *
 * A graded half trim was the first answer, and it still evicted half of the art
 * the return draws - which is half a re-decode storm on the very screen the
 * viewer comes back to. The player does not need the browse caches freed to
 * start: playback is the app's memory peak, and the pressure path (which does
 * trim, and clears on backgrounding) is what guards the peak. A hard stop on the
 * spec: S5 reads "Home's art is still warm" after a playback round-trip.
 */
class PlayerOpenTrimContractTest {

    private val native by lazy { source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt") }
    private val mpv by lazy { source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt") }

    private fun onCreateOpening(source: String): String =
        source.substringAfter("override fun onCreate(")
            .substringBefore("setContentView(")

    @Test
    fun `neither engine frees the browse caches on open`() {
        listOf("exo" to native, "mpv" to mpv).forEach { (name, source) ->
            val opening = onCreateOpening(source)
            assertFalse(
                "$name: no bitmap trim on open - nothing is under pressure yet",
                opening.contains("trimImageMemoryCache")
            )
            assertFalse(
                "$name: no full clear either",
                opening.contains("releaseImageMemoryCache")
            )
            assertFalse(
                "$name: and the browsing caches are not released for a playback",
                opening.contains("MemoryPressure.releaseBrowsingCaches()")
            )
            assertFalse(
                "$name: none of the three helpers is even imported any more",
                source.contains("import com.kennyb1201.kbstream.data.memory.trimImageMemoryCache") ||
                    source.contains("import com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache") ||
                    source.contains("import com.kennyb1201.kbstream.data.memory.MemoryPressure")
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
