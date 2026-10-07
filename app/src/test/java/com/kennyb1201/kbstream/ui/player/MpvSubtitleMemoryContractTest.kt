package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPV's half of the per-show subtitle memory ExoPlayer got in 3405c97a.
 *
 * The engine is a View-based activity with no unit harness, so the wiring is
 * pinned by reading the source - the same approach the repo's other MPV
 * contracts take - while the cross-engine half is a real serialization test,
 * because both engines share one [PlayerTitlePrefs] entry.
 *
 * What must hold: an OFF pick is a remembered fact (not a session-only state),
 * a hand-picked track is remembered by signature, a language pick clears both,
 * and every file open re-states the memory in the same priority order the
 * bridge uses. Each of those fails silently on a TV otherwise.
 */
class MpvSubtitleMemoryContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `both facts are loaded and persisted`() {
        val mpv = read(MPV_ACTIVITY)
        assertTrue(
            "an OFF remembered by the other engine must load here",
            mpv.contains("subtitlesOff = remembered?.subtitleOff == true")
        )
        assertTrue(
            "and the specific track too",
            mpv.contains("subtitleTrackSignature = remembered?.subtitleTrackSignature.orEmpty()")
        )
        assertTrue(
            "both must be written back for the next session",
            mpv.contains("subtitleOff = subtitlesOff,") &&
                mpv.contains("subtitleTrackSignature = subtitleTrackSignature,")
        )
    }

    @Test
    fun `an OFF pick is remembered, not just applied`() {
        val mpv = read(MPV_ACTIVITY)
        val off = functionBody(mpv, "internal fun chooseSubtitlesOff()")
        assertTrue("OFF sets the remembered fact", off.contains("subtitlesOff = true"))
        assertTrue("and drops any specific track", off.contains("subtitleTrackSignature = \"\""))
        assertTrue("persisting so the next episode keeps it", off.contains("persistTitlePreferences()"))
        assertTrue("and turning the text off now", off.contains("surface?.clearSubtitles()"))
        assertTrue(
            "the picker's OFF row goes through it",
            mpv.contains("chooseSubtitlesOff()")
        )
    }

    @Test
    fun `a hand-picked track is remembered by signature`() {
        val mpv = read(MPV_ACTIVITY)
        // The embedded-track row's press: clears the OFF fact it supersedes,
        // stores the signature, and persists before the picker closes.
        val row = " ".repeat(32)
        assertTrue(
            "the embedded-track row must remember its pick",
            mpv.contains(
                row + "subtitlesOff = false\n" +
                    row + "subtitleTrackSignature = track.signature\n" +
                    row + "persistTitlePreferences()"
            )
        )
    }

    @Test
    fun `a language pick drops the OFF and the specific track`() {
        val language = functionBody(read(MPV_ACTIVITY), "internal fun chooseSubtitleLanguage(")
        assertTrue(language.contains("subtitlesOff = false"))
        assertTrue(language.contains("subtitleTrackSignature = \"\""))
    }

    @Test
    fun `file open re-states the memory in the bridge's priority order`() {
        val remembered = functionBody(read(MPV_ACTIVITY), "private fun applyRememberedTracks()")
        // OFF first, then the specific track, then the global On-mode language.
        val offIndex = remembered.indexOf("if (subtitlesOff)")
        val signatureIndex = remembered.indexOf("else if (subtitleTrackSignature.isNotBlank())")
        val languageIndex = remembered.indexOf("else if (SubtitleModeRules.normalized")
        assertTrue("OFF arm missing", offIndex >= 0)
        assertTrue("signature arm missing", signatureIndex > offIndex)
        assertTrue("language arm missing", languageIndex > signatureIndex)
        assertTrue(
            "OFF must actually turn subtitles off on this file open",
            remembered.substring(offIndex, signatureIndex).contains("view.clearSubtitles()")
        )
        assertTrue(
            "the signature arm applies the remembered track",
            remembered.substring(signatureIndex, languageIndex)
                .contains("view.applyRememberedSubtitleTrack(subtitleTrackSignature)")
        )
    }

    @Test
    fun `forgetThisTitle resets both facts`() {
        val forget = functionBody(read(MPV_ACTIVITY), "internal fun forgetThisTitle()")
        assertTrue(forget.contains("subtitlesOff = false"))
        assertTrue(forget.contains("subtitleTrackSignature = \"\""))
    }

    @Test
    fun `the view can apply a remembered subtitle signature`() {
        val view = read(MPV_VIEW)
        val apply = functionBody(view, "fun applyRememberedSubtitleTrack(")
        assertTrue(
            "the exact track wins, else its language",
            apply.contains("firstOrNull { it.signature == signature }") &&
                apply.contains("LanguageMatch.matches(language, it.language)")
        )
        assertTrue(
            "and the match is selected through mpv's sid",
            apply.contains("mpv.setPropertyInt(\"sid\", match.id)")
        )
    }

    @Test
    fun `the entry is shared with ExoPlayer, so the two engines agree`() {
        // A real round-trip: both engines read and write the same two fields of
        // the same blob, so an OFF picked on one is honored on the other.
        val prefs = PlayerTitlePrefs.Prefs(
            subtitleOff = true,
            subtitleTrackSignature = "en|subrip|0"
        )
        val decoded = json.decodeFromString(
            PlayerTitlePrefs.Prefs.serializer(),
            json.encodeToString(PlayerTitlePrefs.Prefs.serializer(), prefs)
        )
        assertTrue(decoded.subtitleOff)
        assertEquals("en|subrip|0", decoded.subtitleTrackSignature)
        assertFalse("an OFF-only entry must not be treated as empty", decoded.isEmpty)

        // The bridge (Exo) already writes these fields, which is what makes the
        // MPV fields the same memory rather than a second copy.
        val bridge = read(BRIDGE)
        assertTrue(bridge.contains("subtitleOff = subtitlesOff,"))
        assertTrue(bridge.contains("subtitleTrackSignature = subtitleTrackSignature,"))
    }

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the member function whose signature contains [signature]. */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
    }
}
