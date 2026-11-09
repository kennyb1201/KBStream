package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bitmap is last resort across the honest kinds (F1), and the re-assert pass
 * re-arms by the same rule (F2).
 *
 * The landed F3 rule only ordered proof INSIDE a kind step, so a preferred-
 * language SDH bitmap still took the step from the preferred-language regular
 * text track beside it - the SDH scan returned before the regular scan ran.
 * The report (Kenny, 2026-11-09) is exactly that file: English SDH in PGS,
 * English text regular.
 *
 * "Bitmap" here is the same evidence the ranking has: a mime that states a
 * bitmap format, or a track this device has SEEN draw images. An embedded PGS
 * track carries `application/x-media3-cues` on a first play, which proves
 * nothing - those cases are asserted as the stated limitation, not papered over.
 */
class SubtitleBitmapLastResortTest {

    private fun srt(
        language: String? = null,
        title: String? = null,
        forced: Boolean = false
    ) = SubtitleTrackRules.Candidate(
        language = language,
        mimeType = MimeTypes.APPLICATION_SUBRIP,
        forced = forced,
        supported = true,
        title = title
    )

    /** PGS: a bitmap format this engine draws, so its row reads `supported`. */
    private fun pgs(
        language: String? = null,
        title: String? = null,
        forced: Boolean = false,
        position: Int = -1
    ) = SubtitleTrackRules.Candidate(
        language = language,
        mimeType = MimeTypes.APPLICATION_PGS,
        forced = forced,
        supported = true,
        title = title,
        position = position
    )

    /**
     * The mime media3 gives every subtitle after extraction - text AND bitmap -
     * so it proves nothing; only the proven-bitmap record can demote it.
     */
    private fun cues(
        language: String? = null,
        title: String? = null,
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

    // ── (a) preferred SDH bitmap + preferred regular text → regular text ────

    @Test
    fun `a preferred sdh bitmap loses the step to the regular text track beside it`() {
        val choice = SubtitleTrackRules.choose(
            listOf(pgs("en", title = "English SDH"), srt("en", title = "English")),
            "en"
        )
        assertEquals(
            "the reported file: the SDH image is last resort behind the text translation",
            SubtitleTrackRules.Choice.Show(1),
            choice
        )
    }

    @Test
    fun `a proven sdh bitmap loses the step to the regular text track beside it`() {
        val choice = SubtitleTrackRules.choose(
            listOf(
                cues("en", title = "English SDH", position = 0),
                cues("en", title = "English", position = 1)
            ),
            "en",
            setOf(0)
        )
        assertEquals(
            "same file, but only the memory says which one is the image",
            SubtitleTrackRules.Choice.Show(1),
            choice
        )
    }

    @Test
    fun `an unproven embedded bitmap is indistinguishable from text on a first play`() {
        // The stated limitation: both tracks say x-media3-cues, so the bitmap
        // still wins its kind step on a first play. Nothing here guesses from
        // the mime; the proven record demotes it from the next play on.
        val choice = SubtitleTrackRules.choose(
            listOf(
                cues("en", title = "English SDH", position = 0),
                cues("en", title = "English", position = 1)
            ),
            "en"
        )
        assertEquals(SubtitleTrackRules.Choice.Show(0), choice)
    }

    // ── (b) preferred SDH bitmap + preferred SDH text → SDH text ────────────

    @Test
    fun `an sdh text track beats an sdh bitmap in the same language`() {
        assertEquals(
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(pgs("en", title = "English SDH"), srt("en", title = "English SDH")),
                "en"
            )
        )
        assertEquals(
            "and it wins whether or not the bitmap has been seen drawing",
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(
                    cues("en", title = "English SDH", position = 0),
                    srt("en", title = "English SDH")
                ),
                "en",
                setOf(0)
            )
        )
    }

    // ── (c) only a preferred SDH bitmap → the bitmap ────────────────────────

    @Test
    fun `a bitmap sdh track is still chosen when the language has nothing else`() {
        assertEquals(
            "last resort is still picked when there is nothing to prefer",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(listOf(pgs("en", title = "English SDH")), "en")
        )
        assertEquals(
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(
                listOf(cues("en", title = "English SDH", position = 0)),
                "en",
                setOf(0)
            )
        )
    }

    // ── (e) the untagged bucket splits the same way ─────────────────────────

    @Test
    fun `the untagged bucket takes its text track before its bitmap`() {
        assertEquals(
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(listOf(pgs(null, title = "SDH"), srt(null)), "en")
        )
        assertEquals(
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(pgs(null, title = "SDH"), srt(null, title = "SDH")),
                "en"
            )
        )
        assertEquals(
            "and a lone untagged bitmap is still the answer",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(listOf(pgs(null, title = "SDH")), "en")
        )
    }

    // ── Forced stays a block, but splits inside it ──────────────────────────

    @Test
    fun `a forced bitmap does not take the forced step from a forced text track`() {
        assertEquals(
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(pgs("en", forced = true), srt("en", forced = true)),
                "en"
            )
        )
    }

    @Test
    fun `a forced text track in another language still loses to an honest bitmap`() {
        // The recorded product call: forced stays demoted as a block, below
        // bitmap, because the flag is distrusted and a full bitmap track beats
        // a signs-only one for a viewer with subtitles on.
        assertEquals(
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(srt("fr", forced = true), pgs("de", title = "German SDH")),
                "en"
            )
        )
    }

    // ── F2: the re-assert pass re-arms by the same rule ─────────────────────

    @Test
    fun `the re-assert pass picks the text track over an sdh bitmap`() {
        assertEquals(
            SubtitleTrackRules.bestLanguageMatchIndex(
                listOf(pgs("en", title = "English SDH"), srt("en"))
            ),
            1
        )
        assertEquals(
            "and over one only the memory knows is a bitmap",
            SubtitleTrackRules.bestLanguageMatchIndex(
                listOf(
                    cues("en", title = "English SDH", position = 0),
                    cues("en", title = "English", position = 1)
                ),
                setOf(0)
            ),
            1
        )
    }

    @Test
    fun `the re-assert pass keeps a lone sdh bitmap and prefers forced text`() {
        assertEquals(
            SubtitleTrackRules.bestLanguageMatchIndex(listOf(pgs("en", title = "English SDH"))),
            0
        )
        assertEquals(
            SubtitleTrackRules.bestLanguageMatchIndex(
                listOf(pgs("en", forced = true), srt("en", forced = true))
            ),
            1
        )
    }
}
