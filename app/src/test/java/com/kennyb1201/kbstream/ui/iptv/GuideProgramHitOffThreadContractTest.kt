package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's program-search rows must be built off the main thread (HD-P2-8).
 *
 * `programSearchHits` maps every EPG search hit onto its visible channel, and
 * doing that needs an epg-id -> channel map over the whole visible channel list.
 * That map used to be built inside a `remember` - on the main thread, during
 * composition, on every recomposition triggered by either input - and a
 * 10k-channel playlist paid for the entire scan exactly when the results were
 * about to be drawn. The channel-name filter right above it had already been
 * moved to `Dispatchers.Default` for the same reason; this one had not.
 */
class GuideProgramHitOffThreadContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    @Test
    fun `the epg-id map is built on a background dispatcher`() {
        val source = source(GUIDE)
        val start = source.indexOf("val programSearchHits by produceState(")
        assertTrue(
            "programSearchHits must be produced from a coroutine, not computed " +
                "in a remember on the UI thread",
            start >= 0
        )
        val end = source.indexOf("\n    }\n", start)
        assertTrue("produceState block end not found", end > start)
        val block = source.substring(start, end)
        assertTrue(
            "the whole scan moves off the main thread",
            block.contains("withContext(Dispatchers.Default) {")
        )
        assertTrue(
            "and the epg-id map is the part that moves",
            block.contains("val byEpgId = unhiddenChannels")
        )
        assertTrue(
            "the inputs are the two states it depends on",
            block.contains("programSearchRows,") && block.contains("unhiddenChannels")
        )
    }

    @Test
    fun `the main-thread remember is gone`() {
        val source = source(GUIDE)
        assertFalse(
            "a remember here would put the full channel scan back on the UI thread",
            source.contains("remember(programSearchRows, unhiddenChannels)")
        )
    }

    private companion object {
        const val GUIDE = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
    }
}
