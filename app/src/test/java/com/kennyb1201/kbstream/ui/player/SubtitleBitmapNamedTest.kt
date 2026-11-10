package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F1/F2 of the first-play bitmap fix: the track's own NAME names the format.
 *
 * The 2026-11-10 report (Kenny, build 0.9.215, a movie's first play) is the hole
 * the bitmap-last-resort rule left open: an embedded PGS track carries
 * `application/x-media3-cues` on a first play, which proves nothing, so it
 * counted as text and its SDH kind step beat the regular text track beside it.
 * The proven-bitmap record only demotes it from the second play on.
 *
 * The label is the one first-play signal the chooser already had in hand: an SDH
 * PGS track usually says so (`English (PGS)`), and
 * [SubtitleTrackRules.namedBitmapFormat] reads it as a whole token. These tests
 * are the report itself, the two guards that keep the signal safe, the stated
 * remainder (an unlabelled bitmap), and the picker's badge.
 */
class SubtitleBitmapNamedTest {

    private fun srt(
        language: String? = null,
        title: String? = null,
        forced: Boolean = false,
        position: Int = -1
    ) = SubtitleTrackRules.Candidate(
        language = language,
        mimeType = MimeTypes.APPLICATION_SUBRIP,
        forced = forced,
        supported = true,
        title = title,
        position = position
    )

    /** A PGS track whose format is stated in the mime. */
    private fun pgs(
        language: String? = null,
        title: String? = null,
        position: Int = -1
    ) = SubtitleTrackRules.Candidate(
        language = language,
        mimeType = MimeTypes.APPLICATION_PGS,
        forced = false,
        supported = true,
        title = title,
        position = position
    )

    /**
     * The mime media3 gives every subtitle after extraction - text AND bitmap -
     * so it proves nothing; on a first play only the track's name can.
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

    // ── the report ──────────────────────────────────────────────────────────

    @Test
    fun `an SDH bitmap named PGS no longer takes the first play from the text track`() {
        val choice = SubtitleTrackRules.choose(
            listOf(
                cues("en", title = "English SDH (PGS)", position = 0),
                srt("en", title = "English", position = 1)
            ),
            "en"
        )

        assertEquals(
            "the label puts the bitmap in the SDH bitmap half, so the regular text " +
                "track wins step 1 - the second play's answer, on the first play",
            SubtitleTrackRules.Choice.Show(1),
            choice
        )
    }

    @Test
    fun `the re-assert pass reads the same signal, so it cannot hand the bitmap back`() {
        val candidates = listOf(
            cues("en", title = "English SDH (PGS)", position = 0),
            srt("en", title = "English", position = 1)
        )

        assertEquals(
            "every READY and rebuffer runs this pass; if it did not see the name it " +
                "would re-arm the bitmap moments after choose() avoided it",
            1,
            SubtitleTrackRules.bestLanguageMatchIndex(candidates)
        )
    }

    @Test
    fun `the stated remainder - a bare label keeps the old behavior`() {
        assertEquals(
            "a bitmap labelled only `English SDH` carries no first-play signal, so it " +
                "still wins its kind step until the proven record exists",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(
                listOf(
                    cues("en", title = "English SDH", position = 0),
                    srt("en", title = "English", position = 1)
                ),
                "en"
            )
        )
    }

    // ── the token set ───────────────────────────────────────────────────────

    @Test
    fun `every token the disc and the muxer use names its format`() {
        listOf(
            "English (PGS)",
            "PGS",
            "English PGS",
            "PGSUB",
            "S_HDMV/PGS",
            "English HDMV"
        ).forEach { name ->
            assertEquals("$name is a PGS label", "PGS", SubtitleTrackRules.namedBitmapFormat(name))
        }
        listOf("English VobSub", "VOBSUB").forEach { name ->
            assertEquals("$name", "VOBSUB", SubtitleTrackRules.namedBitmapFormat(name))
        }
        listOf("English DVB", "DVBSUB", "DVB subtitles").forEach { name ->
            assertEquals("$name", "DVB", SubtitleTrackRules.namedBitmapFormat(name))
        }
    }

    @Test
    fun `whole tokens only, so a word containing the letters is not a format`() {
        assertNull(SubtitleTrackRules.namedBitmapFormat("PGSystem"))
        assertNull(SubtitleTrackRules.namedBitmapFormat("Subpgs"))
        assertNull(SubtitleTrackRules.namedBitmapFormat("English SDH"))
        assertNull(SubtitleTrackRules.namedBitmapFormat(""))
        assertNull(SubtitleTrackRules.namedBitmapFormat("   "))
        assertNull(SubtitleTrackRules.namedBitmapFormat(null))
    }

    @Test
    fun `a name works on the stated bitmap mimes too, and agrees with them`() {
        assertEquals(
            "no disagreement: a stated PGS mime and a named PGS label are the same half",
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(
                    pgs("en", title = "English SDH", position = 0),
                    srt("en", title = "English", position = 1)
                ),
                "en"
            )
        )
    }

    // ── the guards ──────────────────────────────────────────────────────────

    @Test
    fun `a mime that proves text wins over the name`() {
        assertEquals(
            "the name never speaks for a track whose mime states a text format",
            "SRT",
            SubtitleTrackRules.formatLabel(MimeTypes.APPLICATION_SUBRIP, "English (PGS)")
        )
        assertEquals(
            "ASS",
            SubtitleTrackRules.formatLabel(MimeTypes.TEXT_SSA, "PGS")
        )
        assertEquals(
            "and the chooser agrees: a text track mislabelled PGS is still text, so " +
                "SDH kind order is untouched",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(
                listOf(
                    srt("en", title = "English SDH (PGS)", position = 0),
                    srt("en", title = "English", position = 1)
                ),
                "en"
            )
        )
    }

    @Test
    fun `nothing is promoted and nothing is dropped`() {
        assertEquals(
            "a lone named bitmap still wins its bucket - the signal only demotes",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(listOf(cues("en", title = "English (PGS)")), "en")
        )
        assertEquals(
            "and in the untagged bucket the text track wins, as in the tagged one",
            SubtitleTrackRules.Choice.Show(1),
            SubtitleTrackRules.choose(
                listOf(cues(title = "SDH (PGS)"), srt(title = "English")),
                "en"
            )
        )
    }

    @Test
    fun `forced stays a demoted block, with the same split inside it`() {
        assertEquals(
            "a forced bitmap named PGS does not move above an untagged SDH text track",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(
                listOf(
                    cues(title = "English SDH", position = 0),
                    cues("en", title = "Signs (PGS)", forced = true, position = 1)
                ),
                "en"
            )
        )
        assertEquals(
            "and inside the forced block the text half is still scanned first",
            SubtitleTrackRules.Choice.Show(0),
            SubtitleTrackRules.choose(
                listOf(
                    srt("en", title = "Forced", forced = true, position = 0),
                    cues("en", title = "Signs (PGS)", forced = true, position = 1)
                ),
                "en"
            )
        )
    }

    // ── F2: the picker says it before the viewer presses it ─────────────────

    @Test
    fun `the picker badges the name on a first play`() {
        assertEquals(
            "EN · SDH · PGS",
            SubtitleTrackRules.pickerLabel(
                "en",
                MimeTypes.APPLICATION_MEDIA3_CUES,
                "Track 1",
                "English SDH (PGS)"
            )
        )
        assertEquals(
            "EN · PGS",
            SubtitleTrackRules.pickerLabel(
                "en",
                MimeTypes.APPLICATION_MEDIA3_CUES,
                "Track 1",
                "English (PGS)"
            )
        )
        assertEquals(
            "a cues-mime track with no name in it stays unbadged",
            "EN",
            SubtitleTrackRules.pickerLabel("en", MimeTypes.APPLICATION_MEDIA3_CUES, "Track 1")
        )
    }

    @Test
    fun `the picker never badges text as a bitmap from its name`() {
        assertEquals(
            "EN · SRT",
            SubtitleTrackRules.pickerLabel(
                "en",
                MimeTypes.APPLICATION_SUBRIP,
                "Track 1",
                "English (PGS)"
            )
        )
        assertNull(SubtitleTrackRules.formatLabel(MimeTypes.APPLICATION_MEDIA3_CUES))
        assertEquals(
            "VOBSUB",
            SubtitleTrackRules.formatLabel(MimeTypes.APPLICATION_MEDIA3_CUES, "English VobSub")
        )
    }
}
