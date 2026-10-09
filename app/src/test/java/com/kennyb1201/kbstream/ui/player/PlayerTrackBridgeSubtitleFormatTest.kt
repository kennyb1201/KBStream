package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this pins: the panel's subtitle-language picker (and the
 * ready-time reapply pass) armed the FIRST track whose language matched — with
 * no format check at all. On a release whose English track is PGS, that armed a
 * bitmap track this engine has no renderer for: it drew nothing, raised
 * nothing, and logged nothing, exactly like the "options listed, none display"
 * report.
 *
 * The fix is the answer the picker rows already give:
 * [SubtitleTrackRules.cannotDrawNote] is non-null for the tracks this engine
 * cannot draw, so [PlayerTrackBridge.matchingOverride] skips them. When nothing
 * drawable matches, the caller's "no track in this language" path stays in
 * charge — the activity's NeedsMpv handoff and the OpenSubtitles fetch decide
 * what happens, rather than a dead override.
 *
 * The rule is pinned here by building fake track groups, the way this repo pins
 * player logic it cannot launch (see [PlayerTrackBridgeSignatureTest]).
 */
class PlayerTrackBridgeSubtitleFormatTest {

    // ── the reported failure ───────────────────────────────────────────────

    /**
     * English PGS first, English SRT second: the SRT is armed, not the PGS.
     */
    @Test
    fun `an English PGS track is skipped for the English SRT behind it`() {
        val group = group(
            format("eng", MimeTypes.APPLICATION_PGS),
            format("eng", MimeTypes.APPLICATION_SUBRIP)
        )

        assertEquals(
            "the first track in the language is a bitmap this engine cannot draw; the SRT behind it is the one to arm",
            TrackSelectionOverride(group.mediaTrackGroup, 1),
            PlayerTrackBridge.matchingOverride(tracks(group), C.TRACK_TYPE_TEXT, "en")
        )
    }

    /**
     * English PGS only: nothing matches, so the caller keeps its "no subtitle
     * track for this language" behavior (false, text disabled). Falling back to
     * the bitmap track would be the bug.
     */
    @Test
    fun `a language that exists only as a PGS track matches nothing`() {
        val group = group(format("eng", MimeTypes.APPLICATION_PGS))

        assertNull(
            "arming the bitmap track draws nothing and reports nothing, so the NeedsMpv path must decide instead",
            PlayerTrackBridge.matchingOverride(tracks(group), C.TRACK_TYPE_TEXT, "en")
        )
    }

    @Test
    fun `a bitmap match in an earlier group does not hide a later group's SRT`() {
        val pgsGroup = group(format("eng", MimeTypes.APPLICATION_PGS))
        val srtGroup = group(format("eng", MimeTypes.APPLICATION_SUBRIP))

        assertEquals(
            TrackSelectionOverride(srtGroup.mediaTrackGroup, 0),
            PlayerTrackBridge.matchingOverride(tracks(pgsGroup, srtGroup), C.TRACK_TYPE_TEXT, "en")
        )
    }

    /**
     * A language present only as a bitmap must not drag the pass onto a track
     * tagged for a language the viewer did not ask for.
     */
    @Test
    fun `a later group in another language is never armed`() {
        val pgsGroup = group(format("eng", MimeTypes.APPLICATION_PGS))
        val russianGroup = group(format("rus", MimeTypes.APPLICATION_SUBRIP))

        assertNull(
            PlayerTrackBridge.matchingOverride(tracks(pgsGroup, russianGroup), C.TRACK_TYPE_TEXT, "en")
        )
    }

    /**
     * The same skip on this build's own answer: an SRT no renderer claims is as
     * dead on screen as a PGS track ([SubtitleTrackRules.cannotDrawNote] says
     * "not supported here").
     */
    @Test
    fun `a text track this build cannot draw is skipped too`() {
        val dead = group(
            format("eng", MimeTypes.APPLICATION_SUBRIP),
            support = C.FORMAT_UNSUPPORTED_SUBTYPE
        )
        val live = group(format("eng", MimeTypes.TEXT_VTT))

        assertEquals(
            TrackSelectionOverride(live.mediaTrackGroup, 0),
            PlayerTrackBridge.matchingOverride(tracks(dead, live), C.TRACK_TYPE_TEXT, "en")
        )
    }

    // ── the paths that must not change ─────────────────────────────────────

    @Test
    fun `an English SRT on its own is still armed`() {
        val group = group(format("eng", MimeTypes.APPLICATION_SUBRIP))

        assertEquals(
            TrackSelectionOverride(group.mediaTrackGroup, 0),
            PlayerTrackBridge.matchingOverride(tracks(group), C.TRACK_TYPE_TEXT, "en")
        )
    }

    /**
     * Regression pin: every text format this engine DOES draw has a null
     * cannotDrawNote and matches exactly as before. The embedded ASS track is
     * the deliberate degradation (plain text, no styling), not an exclusion.
     */
    @Test
    fun `the text formats this engine draws keep the old path`() {
        listOf(
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.TEXT_VTT,
            MimeTypes.TEXT_SSA
        ).forEach { mime ->
            assertNull(
                "[$mime] must look drawable, or the language pass starts skipping live tracks",
                SubtitleTrackRules.cannotDrawNote(mime, supported = true)
            )
            val group = group(format("eng", mime))
            assertEquals(
                "[$mime] must be armed as it always was",
                TrackSelectionOverride(group.mediaTrackGroup, 0),
                PlayerTrackBridge.matchingOverride(tracks(group), C.TRACK_TYPE_TEXT, "en")
            )
        }
    }

    /**
     * The bitmap verdict holds even if a future media3 claims the track: the
     * skip is on the format's own evidence as well as on `supported`.
     */
    @Test
    fun `a bitmap format is named even when a renderer claims it`() {
        assertNotNull(SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_PGS, supported = true))
        assertNotNull(SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_VOBSUB, supported = true))
        assertNotNull(SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_DVBSUBS, supported = true))
    }

    /**
     * Audio is untouched: an audio track is not a bitmap, and the untagged-audio
     * fallback spec owns what happens when nothing matches. The first match is
     * still armed even when this build reports it unsupported.
     */
    @Test
    fun `audio keeps its plain first match`() {
        val first = group(
            format("eng", MimeTypes.AUDIO_E_AC3),
            support = C.FORMAT_UNSUPPORTED_SUBTYPE
        )
        val second = group(format("eng", MimeTypes.AUDIO_AC3))

        assertEquals(
            "the bitmap skip is text only, so the audio side keeps its first-match behavior",
            TrackSelectionOverride(first.mediaTrackGroup, 0),
            PlayerTrackBridge.matchingOverride(tracks(first, second), C.TRACK_TYPE_AUDIO, "en")
        )
    }

    @Test
    fun `an audio language that is absent matches nothing`() {
        val group = group(format("rus", MimeTypes.AUDIO_E_AC3))

        assertNull(
            "no match means the caller's fallback decides, as before",
            PlayerTrackBridge.matchingOverride(tracks(group), C.TRACK_TYPE_AUDIO, "en")
        )
    }

    // ── the caller still owns "nothing matched" ────────────────────────────

    /**
     * [PlayerTrackBridge.applyLanguage] decides through the one rule above,
     * and when it matches nothing a subtitle language still leaves the text
     * type disabled and returns false — the path that lets the activity's
     * NeedsMpv handoff and the OpenSubtitles fetch run instead of a dead
     * override. Pinned from the source because this app has no JVM player to
     * call (see [AudioAutoSelectLanguageContractTest] for the same approach).
     */
    @Test
    fun `applyLanguage leaves text disabled when the language matched nothing`() {
        val body = memberBody(
            "fun applyLanguage(player: Player?, type: Int, language: String): Boolean {"
        )

        assertTrue(
            "the language pass must decide through the one rule this file pins",
            body.contains("matchingOverride(player.currentTracks, type, language)")
        )
        assertTrue(
            "a subtitle language the file does not carry still disables text",
            body.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        )
        assertTrue(body.contains("return false"))
        assertFalse(
            "no bitmap fallback: an armed track that draws nothing reports nothing",
            body.contains("isBitmapFormat")
        )
    }

    // ── fake player tracks ─────────────────────────────────────────────────

    private fun format(language: String?, mimeType: String?): Format {
        val builder = Format.Builder().setSampleMimeType(mimeType)
        if (language != null) builder.setLanguage(language)
        return builder.build()
    }

    /** One track group, every track reporting the same renderer answer. */
    private fun group(vararg formats: Format, support: Int = C.FORMAT_HANDLED): Tracks.Group =
        Tracks.Group(
            TrackGroup(*formats),
            /* adaptiveSupported = */ false,
            IntArray(formats.size) { support },
            BooleanArray(formats.size)
        )

    private fun tracks(vararg groups: Tracks.Group): Tracks = Tracks(groups.toList())

    // ── source reading (the wiring half, no player instance) ───────────────

    private fun memberBody(signature: String): String {
        val src = File(mainSourceRoot(), SOURCE).readText()
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        // applyLanguage is the object's last member: its body ends at the
        // object's closing brace, the first line that starts at column 0.
        val end = rest.indexOf("\n}")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun mainSourceRoot(): File {
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
        const val SOURCE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
    }
}
