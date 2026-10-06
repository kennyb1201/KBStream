package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV addon-offer path must refuse a download that is not a subtitle - a
 * truncated file, a plain-text limit notice, binary junk - exactly as its
 * online-pick and auto-fetch siblings do (see
 * [SubtitleSearchHelper.isUsableSubtitleBody]). Handing one to mpv drew
 * nothing while the session claimed success.
 *
 * Pinned at the source: it needs a real subtitle addon and a live mpv session
 * to drive, but the ordering is the fix.
 */
class AddonSubtitleValidationContractTest {

    @Test
    fun `an addon offer that is not a subtitle is refused before it reaches mpv`() {
        val body = functionBody("private fun downloadAddonSubtitle(")
        assertTrue(
            "the downloaded body must be read back for validation",
            body.contains("readAddonSubtitleBody(cached)")
        )
        val check = body.indexOf(
            "SubtitleSearchHelper.isUsableSubtitleBody(body, assRenderable = true)"
        )
        assertTrue("mpv renders ASS itself, so the check must allow ASS bodies", check >= 0)
        val attach = body.indexOf("attachExternalSubtitle(cached)")
        assertTrue(
            "the validation must precede the attach, not follow it",
            check in 0 until attach
        )
        assertTrue(
            "and the refusal has to be visible, not silent",
            body.contains("no readable subtitles")
        )
    }

    @Test
    fun `the validation read is bounded`() {
        val body = functionBody("private suspend fun readAddonSubtitleBody(")
        assertTrue(
            "a mislabeled multi-gigabyte download must not be read into memory",
            body.contains("MAX_ADDON_SUBTITLE_BYTES")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), MPV)
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

    /**
     * The body of the function starting at [signature], up to its closing brace
     * (a line indented by exactly four spaces).
     */
    private fun functionBody(signature: String): String {
        val src = readSource()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
