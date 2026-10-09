package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-track subtitle picker used to build each row's stored signature with
 * [PlayerTrackBridge.signatureOf]'s identity fields only. Two same-language
 * tracks with the same codec — two English SRT tracks — produced the same
 * signature, so the press collapsed to the first identity match and could arm
 * a track the viewer did not press; the persisted signature was ambiguous for
 * the next session too.
 *
 * The fix is at the call site: the picker now passes the flat text-track
 * position and the file's text-track total, the same pair the audio rows
 * already passed. [PlayerTrackBridge.resolveSignature] step 1 then has an exact
 * positional match to make, and step 3's count/known-wrong guards keep a stale
 * position from arming anything.
 *
 * The bridge itself is unchanged. The matching is pinned here through its pure
 * entry points ([signatureOf], [resolveSignature]) plus a source-level pin of
 * the picker wiring, which cannot be launched in a JVM test (see
 * [AudioAutoSelectLanguageContractTest] for the same approach).
 */
class SubtitlePickerSignatureTest {

    // ── two identical identities, told apart by position ───────────────────

    /**
     * The reported ambiguity, at the string level: the two rows no longer write
     * the same signature.
     */
    @Test
    fun `two same language SRT tracks get different signatures`() {
        val first = PlayerTrackBridge.signatureOf("eng", "srt", 0, index = 0, trackCount = 2)
        val second = PlayerTrackBridge.signatureOf("eng", "srt", 0, index = 1, trackCount = 2)

        assertEquals("eng|srt|0|0|2", first)
        assertEquals("eng|srt|0|1|2", second)
        assertNotEquals(
            "identical identities with no position is what armed the wrong track",
            first,
            second
        )
    }

    /**
     * Pressing the second row arms the second track: the signature the picker
     * builds for it resolves back to its own position, not to the first
     * identity match.
     */
    @Test
    fun `pressing the second identical row resolves to the second track`() {
        val tracks = listOf(identity("eng"), identity("eng"))

        assertEquals(1, PlayerTrackBridge.resolveSignature("eng|srt|0|1|2", tracks))
        assertEquals(0, PlayerTrackBridge.resolveSignature("eng|srt|0|0|2", tracks))
    }

    /**
     * The signature the call site actually builds comes from a media3 [Format]
     * (the picker holds one per row), so the Format overload must forward the
     * position exactly as the field overload writes it. The identity fields are
     * compared through the Format's own values rather than a literal: [Format]
     * normalizes its language through Android's TextUtils, which the JVM test
     * runtime stubs out. The position tail is the part this fix adds, and it is
     * not environment-dependent.
     */
    @Test
    fun `the format overload the picker calls carries the position`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("eng")
            .setCodecs("srt")
            .build()

        val signature = PlayerTrackBridge.signatureOf(format, index = 1, trackCount = 2)

        assertEquals(
            PlayerTrackBridge.signatureOf(
                format.language,
                format.codecs,
                format.channelCount,
                index = 1,
                trackCount = 2
            ),
            signature
        )
        assertTrue(
            "the picker's own overload must write the position, or every row of two identical tracks collapses again: $signature",
            signature.endsWith("|1|2")
        )
    }

    // ── the shapes that must keep working ─────────────────────────────────

    /**
     * A single-track file still writes its position, and it resolves exactly.
     */
    @Test
    fun `a single track file carries its own position`() {
        assertEquals(
            "eng|srt|0|0|1",
            PlayerTrackBridge.signatureOf("eng", "srt", 0, index = 0, trackCount = 1)
        )
        assertEquals(
            0,
            PlayerTrackBridge.resolveSignature("eng|srt|0|0|1", listOf(identity("eng")))
        )
    }

    /**
     * Regression pin: a signature written before the position existed has no
     * tail and is still resolved by identity, exactly as it was.
     */
    @Test
    fun `a three field legacy signature resolves by identity as before`() {
        val tracks = listOf(identity("rus"), identity("eng"))

        assertEquals(1, PlayerTrackBridge.resolveSignature("eng|srt|0", tracks))
    }

    // ── a position that no longer fits ─────────────────────────────────────

    /**
     * The file was re-muxed to three text tracks: the remembered count no
     * longer matches, so the position is not trusted at all and nothing is
     * armed — never a positional guess at a different track. (With a position
     * present there is no identity fallback; only a legacy three-field
     * signature gets that, because the identity compare in [resolveSignature]
     * is a literal string compare against the stored signature.)
     */
    @Test
    fun `a stale position in a re-muxed file never arms the wrong track`() {
        val tracks = listOf(identity("eng"), identity("eng"), identity("rus"))

        assertNull(
            "a guess at \"the second text track\" in a file of three is exactly the mis-arm this guards",
            PlayerTrackBridge.resolveSignature("eng|srt|0|1|2", tracks)
        )
    }

    /**
     * Same track count, but the remembered position now names a different
     * language: known-wrong, so it is refused rather than armed.
     */
    @Test
    fun `a position that now names another language is refused`() {
        val tracks = listOf(identity("eng"), identity("rus"))

        assertNull(PlayerTrackBridge.resolveSignature("eng|srt|0|1|2", tracks))
    }

    // ── the picker passes the flat position ────────────────────────────────

    /**
     * The wiring half: [NativePlayerActivity.showPicker]'s subtitle branch
     * enumerates every text track in one flat list, computes the position as a
     * prefix sum (matching the audio rows), and hands both the position and the
     * total to [PlayerTrackBridge.signatureOf].
     */
    @Test
    fun `the subtitle picker passes the flat position and the text track total`() {
        val body = functionBody("private fun showPicker(mode: PickerMode) {")
        val flat = body.lines().joinToString("\n") { it.trim() }

        assertTrue(
            "the total is what makes the position checkable against the next file",
            flat.contains("val totalTextTracks = textGroups.sumOf { it.length }")
        )
        assertTrue(
            "the position must be the flat one, exactly as the audio rows compute it",
            flat.contains("val textPrefixes = textGroups.runningFold(0) { sum, group -> sum + group.length }")
        )
        assertTrue(
            flat.contains("val position = textPrefixes[groupIdx] + trackIdx")
        )
        assertTrue(
            "a signature without the position is what collapsed two identical rows",
            flat.contains("index = position,")
        )
        assertTrue(
            flat.contains("trackCount = totalTextTracks")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun identity(language: String?): PlayerTrackBridge.TrackIdentity =
        PlayerTrackBridge.TrackIdentity(language = language, codecs = "srt", channels = 0)

    private fun functionBody(signature: String): String {
        val src = File(mainSourceRoot(), NATIVE).readText()
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        // The next member: a line indented by exactly four spaces (see
        // AudioAutoSelectLanguageContractTest).
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
