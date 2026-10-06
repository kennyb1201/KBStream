package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live TV must do nothing with subtitles on its own.
 *
 * A channel is a stream, not an episode: there is no title to search and no
 * per-episode sidecar to attach. Left ungated, an IPTV channel whose stream
 * carried no text track reached the "no subtitle track" branch, which pulled a
 * subtitle from OpenSubtitles, rebuilt the player to attach it (the ~1s
 * rebuffer the viewer reported) and raised the "Subtitles: ..." toast - none of
 * it asked for. [NativePlayerActivity.maybeAutoFetchSubtitle] now refuses live
 * outright, and the auto-select pass skips live entirely (its sibling
 * `prefetchNextEpisodeSubtitle` already did).
 *
 * This is wiring inside an Android activity, so a device cannot be avoided; the
 * contract test pins the gate and its order.
 */
class LiveSubtitleSuppressionContractTest {

    private fun readSource(path: String): String {
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

    /** The text of the function starting at [signature] up to the next member. */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `live tv never auto-fetches a subtitle`() {
        val body = functionBody(NATIVE, "private fun maybeAutoFetchSubtitle() {")
        val guard = body.indexOf("if (isLiveChannel) return")
        assertTrue("the live guard is missing", guard >= 0)
        // The guard must precede the network call, the attach and the toast, so
        // none of them can run for a channel.
        val search = body.indexOf("SubtitleSearchHelper.search(")
        val attach = body.indexOf("attachExternalSubtitle(")
        val toast = body.indexOf("\"Subtitles: ")
        assertTrue("the guard must precede the search", guard < search)
        assertTrue("the guard must precede the attach", guard < attach)
        assertTrue("the guard must precede the announcement", guard < toast)
    }

    @Test
    fun `the subtitle auto-select pass is skipped for live`() {
        val player = readSource(NATIVE)
        assertTrue(
            "the subtitle branch must exclude live channels",
            player.contains("if (preferredSubtitleLang.isNotBlank() && !isLiveChannel) {")
        )
    }

    @Test
    fun `audio auto-selection is untouched by the live subtitle gate`() {
        // The gate is for subtitles only: a live channel still honours the
        // viewer's preferred audio language, so the audio branch must stay
        // unconditional.
        val player = readSource(NATIVE)
        assertTrue(
            "the audio branch must not have been swept up in the live guard",
            player.contains("if (preferredAudioLang.isNotBlank()) {")
        )
        assertFalse(
            "the audio branch must not be gated on live",
            player.contains("if (preferredAudioLang.isNotBlank() && !isLiveChannel) {")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
