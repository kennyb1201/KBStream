package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a cached debrid link is retired, and by whom.
 *
 * PB-P2-2: the in-app engine used to forget the cached entry on ANY player error
 * before a first frame, including decoder and container failures. Those say
 * something about the box, not the link, so a perfectly alive entry was
 * discarded and the next replay re-resolved for nothing. The forget is now
 * gated on [PlaybackRecoveryRules.isLinkFailure].
 *
 * PB-P2-3: the external-player engine never forgot at all, so a link that the
 * handed-off app refused stayed cached for its whole TTL and every replay of
 * that title bounced off the same corpse. It now retires the entry on the one
 * piece of evidence it can actually collect - a hand-off that came straight back
 * with no playhead reported.
 */
class PlayedLinkForgetCoverageContractTest {

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

    /** The text from [startMarker] up to the first [endMarker] after it. */
    private fun window(path: String, startMarker: String, endMarker: String): String {
        val source = source(path)
        val start = source.indexOf(startMarker)
        assertTrue("marker missing: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("end marker missing after $startMarker", end >= 0)
        return source.substring(start, end)
    }

    @Test
    fun `the in-app forget is gated on a failure about the link`() {
        val errorPath = window(NATIVE, "override fun onPlayerError(error: PlaybackException) {", "lastPlaybackError = error")
        assertTrue(
            "the retirement must be conditional, not run for every error",
            errorPath.contains("if (PlaybackRecoveryRules.isLinkFailure(error))")
        )
        assertTrue(
            "and it is still the same retirement",
            errorPath.contains("invalidateCachedLinkBeforeFirstFrame()")
        )
    }

    @Test
    fun `the external engine retires the link it bounced off`() {
        val external = source(EXTERNAL)
        assertTrue(
            "it has to read the cache key the launch carries",
            external.contains("playedLinkKey = intent.getStringExtra(\"played_link_key\")")
        )
        assertTrue(
            "and forget on the launch profile, like the in-app engines (SD-2)",
            external.contains("PlayedLinkCache.forgetForProfile(this, cacheKey, sessionProfileId)")
        )
        val bounce = window(
            EXTERNAL,
            "if (resultCode != RESULT_OK &&",
            "val label = ExternalPlayer.target(this)?.label"
        )
        assertTrue(
            "the straight-back hand-off is the evidence that retires the entry",
            bounce.contains("invalidateCachedLink()")
        )
        val helper = window(EXTERNAL, "private fun invalidateCachedLink() {", "\n    }")
        assertTrue("once per session", helper.contains("if (linkCacheInvalidated) return"))
        assertTrue(
            "and never for a fresh resolve - there is nothing cached to forget",
            helper.contains("val cacheKey = playedLinkKey ?: return")
        )
    }

    @Test
    fun `the launch that never happened does not retire the link`() {
        // No player ever saw the URL (the chosen app was uninstalled between the
        // query and the start), so nothing was learned about the link itself.
        val failedLaunch = window(EXTERNAL, "} else {\n            // No activity answered after all", "showRefused(")
        assertTrue(
            "a launch failure must not forget the entry",
            !failedLaunch.contains("invalidateCachedLink()")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
