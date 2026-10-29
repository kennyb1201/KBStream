package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheme COMMIT guard, pinned where it cannot be exercised in a JVM test.
 *
 * `maybeDetectScheme()` runs from a player's tick against a live ExoPlayer or
 * libmpv instance, so the wiring is read from source the way this repo pins the
 * rest of the player (see [EpisodeSchemeWiringContractTest]). What is being
 * pinned is narrow and load-bearing: both engines must ask
 * [com.kennyb1201.kbstream.domain.streamengine.RowMediaType] whether the file
 * that just played is evidence about this episode BEFORE they store a scheme
 * from it, and neither may store anything when the answer is no.
 *
 * The failure this prevents is the field incident's second-order harm: the 1999
 * South Park film played under a series session id, 81 minutes against a
 * 22-minute episode runtime, reads as a 4x scheme - and the unguarded commit
 * would have replaced one wrong stored scheme with another, in the store that
 * poisons every viewer on the device (it is global by design).
 */
class SchemeCommitGuardWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
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

    @Test
    fun `the exo player asks the guard before it stores a scheme`() {
        val source = read(NATIVE)
        val guard = source.indexOf("RowMediaType.schemeCommitRefusal(")
        val put = source.indexOf("EpisodeSchemeStore.put(")

        assertTrue("the guard must be called at all", guard > 0)
        assertTrue("a scheme is still stored for a good file", put > 0)
        assertTrue(
            "the guard runs BEFORE the store write, or the guard does nothing",
            guard < put
        )
        assertTrue(
            "and a refusal must return without writing",
            source.contains("if (refusal != null) {")
        )
        assertTrue(
            "the note says a commit was skipped, so a capture can tell",
            source.contains("\"scheme not stored for ")
        )
    }

    @Test
    fun `the mpv player asks the same guard before it stores a scheme`() {
        val source = read(MPV)
        val guard = source.indexOf("RowMediaType.schemeCommitRefusal(")
        val put = source.indexOf("EpisodeSchemeStore.put(")

        assertTrue("the guard must be called at all", guard > 0)
        assertTrue("a scheme is still stored for a good file", put > 0)
        assertTrue(
            "the two engines may not diverge - one rule, checked in both",
            guard < put
        )
        assertTrue(
            "and a refusal must return without writing",
            source.contains("if (refusal != null) {")
        )
        assertTrue(source.contains("\"scheme not stored for "))
    }

    @Test
    fun `both engines pass the row, the runtime and the session episode`() {
        listOf(NATIVE to "firstFrameRendered", MPV to "fileLoaded").forEach { (path, flag) ->
            val source = read(path)
            assertTrue(
                "$path must classify the row that is playing",
                source.contains("stream = sources.firstOrNull { it.url == currentUrl }")
            )
            assertTrue(
                "$path must give the guard this session's episode",
                source.contains("sessionEpisode = episode")
            )
            assertTrue(
                "$path must give the guard the filename it detected from",
                source.contains("fileName = fileName")
            )
            assertTrue(
                "$path must not commit a duration read before the file played ($flag)",
                source.contains("if (fromName == null && !$flag) return")
            )
        }
    }

    @Test
    fun `the external player reads the scheme and never writes one`() {
        val source = read(EXTERNAL)
        assertFalse(
            "an external session must not poison the store: it never detects",
            source.contains("EpisodeSchemeStore.put(")
        )
        assertTrue(
            "but it does read the stored scheme, which is why the reset row exists for it",
            source.contains("EpisodeSchemeStore.get(")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
