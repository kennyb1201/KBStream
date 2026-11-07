package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import com.kennyb1201.kbstream.data.player.BitmapTrackProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3's two rules (test T5), and the fact that neither of them can be answered
 * from a mime type.
 *
 * A track's mime cannot say text-or-image in this build: media3 transcodes SRT,
 * VTT, ASS, TTML and TX3G to `application/x-media3-cues` exactly as it does PGS,
 * so the rules use the two kinds of evidence that DO exist - a mime that proves
 * text (the narrow case of a track that kept its own mime), and the record of a
 * track this device has SEEN draw an image.
 *
 * Everything here stays INSIDE one ranking step: neither rule can move a track
 * across a language or a kind, so a proven bitmap track still loses to a
 * same-language SDH text track and still beats nothing at all.
 */
class SubtitleTrackRulesProofTest {

    private fun unknown(
        language: String?,
        title: String?,
        forced: Boolean = false,
        position: Int = -1
    ) = SubtitleTrackRules.Candidate(
        language = language,
        mimeType = MimeTypes.APPLICATION_MEDIA3_CUES,
        forced = forced,
        supported = true,
        title = title,
        position = position
    )

    private fun text(
        language: String?,
        mime: String,
        title: String? = null,
        forced: Boolean = false,
        position: Int = -1
    ) = SubtitleTrackRules.Candidate(
        language = language,
        mimeType = mime,
        forced = forced,
        supported = true,
        title = title,
        position = position
    )

    // ── Rule A: a mime that proves text wins inside the step ────────────────

    @Test
    fun `a text track proven by its mime outranks a pgs sdh track listed before it`() {
        val candidates = listOf(
            text("en", MimeTypes.APPLICATION_PGS, title = "English SDH"),
            text("en", MimeTypes.APPLICATION_SUBRIP, title = "English SDH")
        )
        assertEquals(
            "the SRT track is the one Clean SDH and the text sizing can act on",
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(candidates, "en")
        )
    }

    @Test
    fun `a pgs track is still chosen when it is the only track in its step`() {
        val candidates = listOf(
            text("en", MimeTypes.APPLICATION_PGS, title = "English SDH")
        )
        assertEquals(SubtitleTrackRules.Choice.Show(0), SubtitleTrackRules.choose(candidates, "en"))
    }

    @Test
    fun `rule a never crosses a language or a kind`() {
        // PGS SDH in the viewer's language, SRT regular in another language: the
        // language match wins, exactly as before Rule A existed.
        val candidates = listOf(
            text("en", MimeTypes.APPLICATION_PGS, title = "English SDH"),
            text("fr", MimeTypes.APPLICATION_SUBRIP)
        )
        assertEquals(SubtitleTrackRules.Choice.Show(0), SubtitleTrackRules.choose(candidates, "en"))
    }

    // ── Rule B: a track this device has seen draw images ────────────────────

    @Test
    fun `a track proven to draw bitmaps is skipped when a same-kind alternative exists`() {
        val candidates = listOf(
            unknown("en", "English SDH"),
            unknown("en", "English SDH")
        )
        assertEquals(
            "the first row drew images on an earlier play",
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(candidates, "en", setOf(0))
        )
        assertEquals(
            "and with nothing proven the file order is untouched",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(candidates, "en")
        )
    }

    @Test
    fun `a proven bitmap track is still chosen when it is all the step has`() {
        val candidates = listOf(unknown("en", "English SDH"))
        assertEquals(
            "a bitmap track beats no subtitles",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(candidates, "en", setOf(0))
        )
    }

    @Test
    fun `the re-assert pass skips a proven bitmap track too`() {
        val candidates = listOf(
            unknown("en", "English SDH"),
            unknown("en", "English SDH")
        )
        assertEquals(1, SubtitleTrackRules.bestLanguageMatchIndex(candidates, setOf(0)))
        assertEquals(0, SubtitleTrackRules.bestLanguageMatchIndex(candidates))
    }

    // ── Proof matching: position AND language AND kind ──────────────────────

    @Test
    fun `a proof is matched on position, language and kind`() {
        val candidates = listOf(unknown("en", "English SDH"))

        val right = listOf(
            BitmapTrackProof(
                position = 0,
                language = "eng",
                kind = BitmapTrackProof.KIND_SDH
            )
        )
        assertEquals(setOf(0), SubtitleTrackRules.provenBitmapPositions(candidates, right))

        val otherLanguage = listOf(
            BitmapTrackProof(
                position = 0,
                language = "fr",
                kind = BitmapTrackProof.KIND_SDH
            )
        )
        assertEquals(
            "a record from a different language cannot mark this track",
            emptySet<Int>(),
            SubtitleTrackRules.provenBitmapPositions(candidates, otherLanguage)
        )

        val otherKind = listOf(
            BitmapTrackProof(
                position = 0,
                language = "en",
                kind = BitmapTrackProof.KIND_REGULAR
            )
        )
        assertEquals(
            "a record made for a regular track cannot mark an SDH one",
            emptySet<Int>(),
            SubtitleTrackRules.provenBitmapPositions(candidates, otherKind)
        )
    }

    @Test
    fun `a proof addresses the track's position, not its slot in the list`() {
        // The bridge hands in a SUBSET of the file's tracks, so a record has to
        // be matched on the track's own flat position.
        val candidates = listOf(
            unknown("en", null, position = 4),
            unknown("en", "English SDH", position = 5)
        )
        val proofs = listOf(
            BitmapTrackProof(
                position = 5,
                language = "en",
                kind = BitmapTrackProof.KIND_SDH
            )
        )
        assertEquals(setOf(1), SubtitleTrackRules.provenBitmapPositions(candidates, proofs))
    }

    @Test
    fun `no proofs leaves the ranking exactly as it was`() {
        val candidates = listOf(
            unknown("en", "English SDH"),
            text("en", MimeTypes.APPLICATION_SUBRIP)
        )
        assertEquals(
            "SDH outranks a regular track of the same language, as always",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(candidates, "en", emptySet<Int>())
        )
    }

    // ── The mime cannot answer the question ─────────────────────────────────

    @Test
    fun `an x-media3-cues track is never labelled bitmap from its mime`() {
        assertNull(
            "a factory-parsed SRT track carries this mime too, so there is no branch for it",
            SubtitleTrackRules.formatLabel(MimeTypes.APPLICATION_MEDIA3_CUES)
        )
        assertFalse(SubtitleTrackRules.isProvenTextFormat(MimeTypes.APPLICATION_MEDIA3_CUES))
        assertFalse(SubtitleTrackRules.isProvenTextFormat(null))
    }

    @Test
    fun `a stated text mime proves text, and a stated bitmap mime does not`() {
        listOf(
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.TEXT_VTT,
            MimeTypes.APPLICATION_MP4VTT,
            MimeTypes.TEXT_SSA,
            MimeTypes.APPLICATION_TX3G,
            MimeTypes.APPLICATION_TTML
        ).forEach { mime ->
            assertTrue("$mime is a text format", SubtitleTrackRules.isProvenTextFormat(mime))
        }
        assertFalse(SubtitleTrackRules.isProvenTextFormat(MimeTypes.APPLICATION_PGS))
        assertFalse(SubtitleTrackRules.isProvenTextFormat(MimeTypes.APPLICATION_VOBSUB))
        assertFalse(SubtitleTrackRules.isProvenTextFormat(MimeTypes.APPLICATION_DVBSUBS))
    }

    @Test
    fun `the kind a record stores is the kind the ranking reads`() {
        assertEquals(
            BitmapTrackProof.KIND_SDH,
            SubtitleTrackRules.kindOf("English SDH", forced = false)
        )
        assertEquals(
            "an SDH-named track is SDH even when the forced flag is also set",
            BitmapTrackProof.KIND_SDH,
            SubtitleTrackRules.kindOf("English SDH", forced = true)
        )
        assertEquals(
            BitmapTrackProof.KIND_FORCED,
            SubtitleTrackRules.kindOf("Signs and Songs", forced = true)
        )
        assertEquals(
            BitmapTrackProof.KIND_REGULAR,
            SubtitleTrackRules.kindOf("English", forced = false)
        )
        assertEquals(
            BitmapTrackProof.KIND_REGULAR,
            SubtitleTrackRules.kindOf(null, forced = false)
        )
    }
}
