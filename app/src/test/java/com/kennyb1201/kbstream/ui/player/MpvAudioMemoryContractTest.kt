package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.player.PlayerTitlePrefs
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio half of MPV's per-show memory, and the failure it was hiding.
 *
 * Reported: "mpv player keeps playing foreign language over english". The
 * engine applies its audio language in exactly ONE place — the activity's
 * [MpvPlayerActivity.applyRememberedTracks], from the file-loaded callback —
 * and that place chose between the two facts instead of layering them: a
 * remembered track signature meant the language arm never ran at all.
 *
 * A signature only matches while the file still carries that exact track
 * ("language|codec|channels"). The next episode of a show is usually another
 * release, so its codec or channel count differs, both matches miss, and the
 * engine was left on mpv's own pick — the muxer's DEFAULT flag, which on a
 * dual-audio EN/RU release is the Russian track. The stored signature is kept
 * for the next source, but it can no longer suppress the language; that is
 * also where the ExoPlayer engine already ends up, so the two agree.
 *
 * The engine is a View-based activity with no unit harness, so the wiring is
 * pinned by reading the source — the same approach the repo's other MPV
 * contracts take — while the cross-engine half is a real serialization and
 * signature-format test, because both engines share one [PlayerTitlePrefs]
 * entry and one signature shape.
 */
class MpvAudioMemoryContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `both facts are loaded and persisted`() {
        val mpv = read(MPV_ACTIVITY)
        assertTrue(
            "the language the other engine stored must load here, canonicalized the " +
                "way every other audio path canonicalizes it",
            mpv.contains("audioLanguage = LanguageMatch.canonical(remembered?.audioLang).orEmpty()")
        )
        assertTrue(
            "and the specific track too",
            mpv.contains("audioTrackSignature = remembered?.audioTrackSignature.orEmpty()")
        )
        assertTrue(
            "both must be written back for the next session",
            mpv.contains("audioLang = audioLanguage,") &&
                mpv.contains("audioTrackSignature = audioTrackSignature,")
        )
    }

    @Test
    fun `a hand-picked track is remembered by signature and applied by id`() {
        val mpv = read(MPV_ACTIVITY)
        val pick = functionBody(mpv, "private fun chooseAudioTrackById(")
        assertTrue("the pick stores its signature", pick.contains("audioTrackSignature = track.signature"))
        assertTrue("remembered, not just applied", pick.contains("persistTitlePreferences()"))
        assertTrue(
            "one file can list the same language and codec twice (5.1 and stereo), so the " +
                "exact track is selected by mpv's own id",
            pick.contains("surface?.selectAudioTrack(track.id)")
        )
    }

    @Test
    fun `a language pick drops the specific track, so the two cannot disagree`() {
        val language = functionBody(read(MPV_ACTIVITY), "internal fun chooseAudioLanguage(")
        assertTrue(language.contains("audioLanguage = code"))
        assertTrue(language.contains("audioTrackSignature = \"\""))
        assertTrue("and is persisted, so the next episode keeps it", language.contains("persistTitlePreferences()"))
        assertTrue(
            "applied now, through the same language pass the file open uses",
            language.contains("surface?.selectAudioLanguage(effective)")
        )
    }

    /**
     * The reported failure itself: the language arm has to be reachable when a
     * remembered track is present but does not resolve in this file.
     */
    @Test
    fun `file open falls through to the language when the remembered track is not here`() {
        val remembered = functionBody(read(MPV_ACTIVITY), "private fun applyRememberedTracks()")
        assertTrue(
            "the remembered track has to be ATTEMPTED and its miss has to fall through to the " +
                "language preference; skipping the language whenever a signature exists is what " +
                "left the muxer's DEFAULT - the Russian track - playing",
            remembered.contains(
                "if (audioTrackSignature.isBlank() || " +
                    "!view.applyRememberedAudioTrack(audioTrackSignature)) {"
            )
        )
        val guard = remembered.indexOf("audioTrackSignature.isBlank() ||")
        val language = remembered.indexOf("view.selectAudioLanguage(effectiveAudioLanguage())")
        assertTrue("the language arm must be inside the file open", language > guard)
        assertFalse(
            "the either/or that swallowed the language arm must not come back",
            remembered.contains("if (audioTrackSignature.isNotBlank()) {")
        )
    }

    @Test
    fun `the view reports the miss instead of deciding what to play`() {
        val apply = functionBody(read(MPV_VIEW), "fun applyRememberedAudioTrack(")
        assertTrue(
            "the exact signature wins",
            apply.contains("firstOrNull { it.signature == signature }")
        )
        assertTrue(
            "else the same language in the same layout",
            apply.contains("LanguageMatch.matches(language, it.language)") &&
                apply.contains("channels <= 0 || it.channels == channels")
        )
        assertTrue(
            "a miss is a false, which is the caller's cue to apply the language",
            apply.contains("?: return false")
        )
        assertTrue(
            "and a hit is applied through mpv's own aid",
            apply.contains("MpvBackend.setPropertyInt(\"aid\", match.id)")
        )
    }

    /**
     * Why the fall-through is parity rather than a new preference: the
     * ExoPlayer engine's own automatic pass applies the preferred language
     * without consulting the remembered signature at all, so an unresolvable
     * pick cannot suppress the language there either.
     */
    @Test
    fun `the ExoPlayer engine already ends on the language in this case`() {
        val auto = functionBody(read(NATIVE), "private fun autoSelectPreferredLanguages()")
        assertTrue(
            "the automatic pass runs off the preferred language alone",
            auto.contains("if (preferredAudioLang.isNotBlank()) {")
        )
        assertTrue(
            "and overrides the audio track itself",
            auto.contains("TrackSelectionOverride(group.mediaTrackGroup, i)")
        )
        assertFalse(
            "nothing in it consults the remembered signature, which is exactly why the MPV " +
                "engine has to fall through rather than stop",
            auto.contains("audioTrackSignature")
        )
    }

    @Test
    fun `forgetThisTitle resets both facts`() {
        val forget = functionBody(read(MPV_ACTIVITY), "internal fun forgetThisTitle()")
        assertTrue(forget.contains("audioLanguage = \"\""))
        assertTrue(forget.contains("audioTrackSignature = \"\""))
    }

    /**
     * The entry and the signature shape are shared, so a pick made on one
     * engine resolves on the other rather than reading as a second memory.
     */
    @Test
    fun `the entry and the signature are shared with ExoPlayer`() {
        // A real round-trip: both engines read and write the same two fields of
        // the same blob.
        val prefs = PlayerTitlePrefs.Prefs(
            audioLang = "en",
            audioTrackSignature = "en|eac3|6"
        )
        val decoded = json.decodeFromString(
            PlayerTitlePrefs.Prefs.serializer(),
            json.encodeToString(PlayerTitlePrefs.Prefs.serializer(), prefs)
        )
        assertEquals("en", decoded.audioLang)
        assertEquals("en|eac3|6", decoded.audioTrackSignature)
        assertFalse("an audio-only entry must not be treated as empty", decoded.isEmpty)

        val bridge = read(BRIDGE)
        assertTrue(bridge.contains("audioLang = audioLanguage,"))
        assertTrue(bridge.contains("audioTrackSignature = audioTrackSignature,"))

        // ...and the MPV engine writes the same three-field shape the bridge
        // writes and reads, so a pick remembered by one is a pick the other can
        // resolve: "language|codec|channels", lowercased, no position.
        val view = read(MPV_VIEW)
        assertTrue(
            "the MPV signature must be the bridge's three identity fields",
            view.contains("language.orEmpty().lowercase(),") &&
                view.contains("codec.orEmpty().lowercase(),") &&
                view.contains("(channels ?: 0).toString()") &&
                view.contains("joinToString(\"|\")")
        )
        assertEquals(
            "and an MPV-written pick is one the bridge reads back as its own form",
            PlayerTrackBridge.signatureOf(language = "en", codecs = "eac3", channelCount = 6),
            "en|eac3|6"
        )
        assertEquals(
            "including the untagged case, where the language field is empty",
            PlayerTrackBridge.signatureOf(language = "", codecs = "ac3", channelCount = 2),
            "|ac3|2"
        )
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
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
