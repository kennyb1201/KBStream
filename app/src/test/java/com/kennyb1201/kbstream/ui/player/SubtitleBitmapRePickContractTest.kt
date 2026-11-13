package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 - the same-session proof re-pick.
 *
 * The field case (2026-11-13): a file whose only EN bitmap track carries no
 * readable signal - mime `application/x-media3-cues`, label just `English` - is
 * ranked as a regular text track and wins on file order alone, ahead of six real
 * EN text tracks. The proof that it is a bitmap only exists once it DRAWS, so
 * this rule moves off it on the first bitmap line, in the same session, and
 * never at the cost of a manual pick.
 *
 * Two halves, the same as the sibling [SubtitleBitmapWiringContractTest]:
 *  - the wiring, read out of the source, because it needs a player and a surface
 *    (the cue handler, the override, the bridge callback);
 *  - the decision, exercised directly over the 21-row field shape.
 */
class SubtitleBitmapRePickContractTest {

    private val activity: String by lazy { read(NATIVE_PLAYER) }
    private val bridge: String by lazy { read(TRACK_BRIDGE) }

    private val showBitmapCues: String by lazy {
        activity.slice("private fun showBitmapCues(", "private fun clearBitmapCues(")
    }
    private val rePick: String by lazy {
        activity.slice("private fun rePickProvenBitmapTrack()", "private inner class SubtitleCueHandler")
    }
    private val languagePass: String by lazy {
        activity.slice("private fun applyChosenSubtitleLanguage(", "private fun applyChosenSubtitleOffset(")
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    @Test
    fun `the record comes first and the re-pick second`() {
        val record = showBitmapCues.indexOf("recordProvenBitmapTrack()")
        val rePickCall = showBitmapCues.indexOf("rePickProvenBitmapTrack()")
        assertTrue("the bitmap cue path must still record the proof", record >= 0)
        assertTrue("and then attempt F1's re-pick", rePickCall >= 0)
        assertTrue(
            "the proof has to be in the session set before the re-pick reads it",
            record < rePickCall
        )
    }

    @Test
    fun `the re-pick reads the proof after the record, session set included`() {
        assertTrue(
            "the same proven set the auto-pick and the re-assert use, not a second numbering",
            rePick.contains("provenBitmapPositionsForTextGroups(groups)")
        )
        assertTrue(
            "and that set is the stored records plus what THIS session has drawn",
            activity.contains("return proven + provenBitmapTrackPositions")
        )
        assertTrue(
            "the ranking is the re-assert's, with the proof in hand",
            rePick.contains("SubtitleTrackRules.sameSessionProofRePick(")
        )
        assertTrue(
            "and the switch applies through the same override the language pass uses",
            rePick.contains(".setOverrideForType(")
        )
        assertFalse(
            "the re-pick must never move subtitles into another language or off",
            rePick.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)") ||
                rePick.contains("clearOverridesOfType")
        )
    }

    @Test
    fun `a manual pick clears the auto-pick marker and the once-per-source flag holds`() {
        assertTrue(
            "the marker is what keeps a remembered signature and a hand pick out of F1",
            rePick.contains("!subtitleArmedByAutoPick")
        )
        assertTrue(
            "one attempt per track list, so a later PGS line cannot fight a manual pick",
            rePick.contains("if (bitmapRePickAttempted ||")
        )
        assertTrue(
            "the picker's own path clears it - including when the viewer picks the bitmap on purpose",
            bridge.slice("fun chooseSubtitleTrack(", "fun chooseAudioDelay(")
                .contains("onManualSubtitlePick?.invoke()")
        )
        assertTrue(
            "and the activity wires that callback to the marker",
            activity.contains("onManualSubtitlePick = {") &&
                activity.contains("subtitleArmedByAutoPick = false")
        )
        assertTrue(
            "live channels never re-pick (I4)",
            rePick.contains("|| isLiveChannel) return")
        )
    }

    @Test
    fun `the re-assert after a switch re-arms with the proof in hand`() {
        assertTrue(
            "the language pass keeps carrying the proven positions",
            languagePass.contains("provenBitmapPositionsForTextGroups(textGroups)")
        )
        assertTrue(
            "and its verdict is what the marker follows, so a text re-arm stays a text arm",
            languagePass.contains("subtitleArmedByAutoPick = armed && code.isNotBlank()")
        )
        assertTrue(
            "the bridge's ranking splits text before bitmap under the same proof",
            bridge.contains("provenBitmapPositions: Set<Int> = emptySet()")
        )
    }

    @Test
    fun `the marker is reset with the track list it describes`() {
        assertEquals(
            "the declaration plus one reset per proof-set clear (player build, live zap) " +
                "and the source switch",
            4,
            Regex("bitmapRePickAttempted = false").findAll(activity).count()
        )
        assertEquals(
            "and the marker is only SET by the two auto paths (Show branch and language pass)",
            2,
            Regex("subtitleArmedByAutoPick = (true|armed|PlayerTrackBridge)")
                .findAll(activity).count()
        )
        assertTrue(
            "a remembered signature is not an auto pick: the bridge re-arms that track right " +
                "after the pass, so the marker must stay false and F1 must not fire",
            activity.contains(
                "subtitleArmedByAutoPick = PlayerTrackBridge.subtitleTrackSignature.isBlank()"
            )
        )
    }

    // ── The decision over the field shape ─────────────────────────────────

    /**
     * The 21-row picker list, verbatim in shape: OFF (not a track), the EN
     * bitmap, thirteen other-language tracks, then six EN text tracks. Flat
     * text positions run from the bitmap at 0 to the last EN text track at 19 -
     * the picker's OFF row is not a track, so the bitmap is the first of the
     * twenty.
     */
    private fun fieldShape(): List<SubtitleTrackRules.Candidate> {
        val candidates = mutableListOf<SubtitleTrackRules.Candidate>()
        fun add(language: String?, label: String? = null) {
            candidates += SubtitleTrackRules.Candidate(
                language = language,
                mimeType = "application/x-media3-cues",
                forced = false,
                supported = true,
                title = label,
                position = candidates.size
            )
        }
        add("en", "English") // the EN PGS bitmap: no format token in its label
        listOf(
            "bg", "cs", "da", "nl", "fi", "fr", "de", "hu", "no", "sl", "es", "sv", "tr"
        ).forEach { add(it) }
        repeat(6) { add("en") }
        return candidates
    }

    @Test
    fun `the proven bitmap moves to the first EN text track in the same session`() {
        val candidates = fieldShape()
        val target = SubtitleTrackRules.sameSessionProofRePick(
            candidates = candidates,
            armedPosition = 0,
            preferredLanguage = "en",
            provenBitmapPositions = setOf(0)
        )
        assertEquals(
            "the first EN text track beside it, not one of the other thirteen languages",
            14,
            target
        )
        assertEquals(
            "and it is text in the ranking's eyes once the proof is in",
            "en",
            candidates[target!!].language
        )
    }

    @Test
    fun `the re-assert after the switch ranks the text track first`() {
        val candidates = fieldShape()
        val proven = setOf(0)
        val bucket = candidates.filter { it.language == "en" && it.supported }
        val best = SubtitleTrackRules.bestLanguageMatchIndex(bucket, proven)
        assertEquals(
            "a READY after the switch re-arms through this ranking, so it must name the EN text track",
            14,
            bucket[best!!].position
        )
        assertFalse(
            "and never walk the proven bitmap back in front of it",
            best == 0
        )
    }

    @Test
    fun `with no bitmap in the file the re-pick is a no-op`() {
        val withoutBitmap = fieldShape()
            .filterNot { it.position == 0 }
            .mapIndexed { index, candidate -> candidate.copy(position = index) }
        assertNull(
            "an armed text track has no proof to re-pick from",
            SubtitleTrackRules.sameSessionProofRePick(
                candidates = withoutBitmap,
                armedPosition = 13, // the first EN text track now
                preferredLanguage = "en",
                provenBitmapPositions = emptySet()
            )
        )
    }

    @Test
    fun `a file whose only EN track is the bitmap stays on the bitmap`() {
        val bitmapOnly = fieldShape()
            .filter { it.position == 0 || it.language != "en" }
            .mapIndexed { index, candidate -> candidate.copy(position = index) }
        assertNull(
            "there is no text track in the bucket, so subtitles must not be switched or turned off",
            SubtitleTrackRules.sameSessionProofRePick(
                candidates = bitmapOnly,
                armedPosition = 0,
                preferredLanguage = "en",
                provenBitmapPositions = setOf(0)
            )
        )
    }

    @Test
    fun `an untagged proven bitmap still moves to an untagged text track`() {
        val candidates = listOf(
            SubtitleTrackRules.Candidate(
                language = null,
                mimeType = "application/x-media3-cues",
                forced = false,
                supported = true,
                title = "English",
                position = 0
            ),
            SubtitleTrackRules.Candidate(
                language = "bg",
                mimeType = "application/x-media3-cues",
                forced = false,
                supported = true,
                title = null,
                position = 1
            ),
            SubtitleTrackRules.Candidate(
                language = null,
                mimeType = "application/x-media3-cues",
                forced = false,
                supported = true,
                title = null,
                position = 2
            )
        )
        assertEquals(
            "the untagged bucket is the armed track's own; bg is a different language and must stay out",
            2,
            SubtitleTrackRules.sameSessionProofRePick(
                candidates = candidates,
                armedPosition = 0,
                preferredLanguage = "en",
                provenBitmapPositions = setOf(0)
            )
        )
    }

    @Test
    fun `a bitmap with no text beside it in its bucket is left alone`() {
        val candidates = listOf(
            SubtitleTrackRules.Candidate(
                language = "en",
                mimeType = "application/x-media3-cues",
                forced = false,
                supported = true,
                title = null,
                position = 0
            ),
            SubtitleTrackRules.Candidate(
                language = "en",
                mimeType = "application/x-media3-cues",
                forced = false,
                supported = true,
                title = null,
                position = 1
            )
        )
        assertNull(
            "another proven bitmap is not a text track to move to",
            SubtitleTrackRules.sameSessionProofRePick(
                candidates = candidates,
                armedPosition = 0,
                preferredLanguage = "en",
                provenBitmapPositions = setOf(0, 1)
            )
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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

    private companion object {
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
    }
}
