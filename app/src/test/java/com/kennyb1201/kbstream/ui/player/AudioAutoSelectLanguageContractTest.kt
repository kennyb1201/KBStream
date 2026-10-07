package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.player.LanguageMatch
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported failure this pins: a multi-language file — Russian first, English
 * also present — opened in Russian while the Language setting was English.
 *
 * Every audio path in the app already matched a stored two-letter code ("en")
 * against a file's three-letter track tag ("eng") through [LanguageMatch] — the
 * manual picks, the per-title memory re-apply, and the MPV engine's `alang`.
 * The ONE path that did not was the automatic initial selection in
 * [NativePlayerActivity.autoSelectPreferredLanguages], which compared the two as
 * raw strings. "eng" != "en", so no override was ever set and ExoPlayer kept the
 * file's own default track, which in that release was the Russian one.
 *
 * The matcher is a pure object and is covered by LanguageMatchTest. What is left
 * is WIRING, and it is invisible: the string compare compiled cleanly and its
 * miss was silent (no exception, no log worth reading). Read from the source, the
 * way this repo pins player wiring (see PlayerBackgroundReturnContractTest and
 * EpisodeSchemeWiringContractTest) — the activity builds ExoPlayer and takes a
 * video surface, so it cannot be launched in a JVM test.
 */
class AudioAutoSelectLanguageContractTest {

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

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /**
     * The text of the member starting at [signature] up to the next member: a
     * line indented by exactly four spaces (see EpisodeSchemeWiringContractTest).
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * The failure itself, at the level the reader cares about: an English
     * preference must pick the English track and not the Russian one. This is
     * the behavior the automatic pick must inherit by matching through
     * [LanguageMatch] rather than by comparing strings.
     */
    @Test
    fun `an English preference selects the eng track and not rus`() {
        assertTrue(
            "the reported file tagged its English track \"eng\"; an \"en\" preference " +
                "must still match it",
            LanguageMatch.matches("en", "eng")
        )
        assertFalse(
            "the same preference must never fall onto the Russian track, which is what " +
                "leaving the file's default in place did",
            LanguageMatch.matches("en", "rus")
        )
    }

    @Test
    fun `the automatic audio pick matches through LanguageMatch`() {
        val body = functionBody(
            read(NATIVE),
            "private fun autoSelectPreferredLanguages() {"
        )
        assertTrue(
            "the automatic initial audio pick must canonicalize both sides (\"en\" vs " +
                "\"eng\"), or a multi-language file opens on the muxer's first track " +
                "however the Language setting reads",
            body.contains("LanguageMatch.matches(preferredAudioLang, fmt.language)")
        )
    }

    @Test
    fun `the automatic audio pick no longer compares the tags as raw strings`() {
        val body = functionBody(
            read(NATIVE),
            "private fun autoSelectPreferredLanguages() {"
        )
        assertFalse(
            "the raw-string compare is exactly what missed \"eng\" for an \"en\" " +
                "preference and left the Russian default playing",
            body.contains("== preferredAudioLang.lowercase()")
        )
    }

    @Test
    fun `NativePlayerActivity imports the shared matcher it now relies on`() {
        assertTrue(
            "NativePlayerActivity must import the same matcher every other audio path " +
                "uses, so the two cannot drift apart again",
            read(NATIVE).contains("import com.kennyb1201.kbstream.data.player.LanguageMatch")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
