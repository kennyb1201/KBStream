package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import com.kennyb1201.kbstream.ui.player.SubtitleTrackRules.Candidate
import com.kennyb1201.kbstream.ui.player.SubtitleTrackRules.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitle auto-select rules.
 *
 * Every failure mode here is silent on a television: a bitmap track that is
 * "selected" and draws nothing, or a fallback that lands on the one track no
 * renderer claims. Nothing throws, so nothing is catchable — these tests are
 * the only thing standing between the rules and that.
 */
class SubtitleTrackRulesTest {

    private fun srt(
        language: String? = null,
        forced: Boolean = false,
        supported: Boolean = true
    ) = Candidate(language, MimeTypes.APPLICATION_SUBRIP, forced, supported)

    private fun pgs(language: String? = null, forced: Boolean = false) =
        Candidate(language, MimeTypes.APPLICATION_PGS, forced, supported = false)

    // ── The silent failure: a bitmap track that draws nothing ──────────────

    @Test
    fun `a lone PGS track asks for the MPV engine instead of arming a dead track`() {
        val choice = SubtitleTrackRules.choose(listOf(pgs("en")), "en")
        assertEquals(Choice.NeedsMpv(0), choice)
    }

    @Test
    fun `a bitmap track is never chosen even if it is reported supported`() {
        // media3 reports PGS unsupported today. If that ever changes, choosing
        // it would silently render nothing, so the format is refused outright.
        val lying = Candidate("en", MimeTypes.APPLICATION_PGS, forced = false, supported = true)
        assertEquals(Choice.NeedsMpv(0), SubtitleTrackRules.choose(listOf(lying), "en"))
    }

    @Test
    fun `a renderable track wins over a bitmap one in the same language`() {
        val choice = SubtitleTrackRules.choose(listOf(pgs("en"), srt("en")), "en")
        assertEquals(Choice.Show(1), choice)
    }

    @Test
    fun `a bitmap track is only a fallback once nothing renderable matches`() {
        val choice = SubtitleTrackRules.choose(listOf(srt("zz"), pgs("en")), "en")
        // "zz" is renderable but the wrong language; the language match is the
        // bitmap track, which this engine cannot draw — so the answer is the
        // other engine, not the wrong-language track.
        assertEquals(Choice.NeedsMpv(1), choice)
    }

    // ── Untagged tracks ────────────────────────────────────────────────────

    @Test
    fun `an untagged track is used when the preferred language has no tag to match`() {
        val choice = SubtitleTrackRules.choose(listOf(srt(null), srt(null)), "en")
        assertEquals(Choice.Show(0), choice)
    }

    @Test
    fun `und counts as untagged`() {
        val choice = SubtitleTrackRules.choose(listOf(srt("und")), "en")
        assertEquals(Choice.Show(0), choice)
    }

    @Test
    fun `an untagged track is preferred over one tagged for another language`() {
        val choice = SubtitleTrackRules.choose(listOf(srt("fr"), srt(null)), "en")
        assertEquals(Choice.Show(1), choice)
    }

    @Test
    fun `a precisely tagged match still beats an untagged track`() {
        val choice = SubtitleTrackRules.choose(listOf(srt(null), srt("en")), "en")
        assertEquals(Choice.Show(1), choice)
    }

    // ── Forced tracks ──────────────────────────────────────────────────────

    @Test
    fun `forced wins a tie in the preferred language`() {
        val choice = SubtitleTrackRules.choose(listOf(srt("en"), srt("en", forced = true)), "en")
        assertEquals(Choice.Show(1), choice)
    }

    @Test
    fun `forced wins the untagged fallback`() {
        val choice = SubtitleTrackRules.choose(listOf(srt(null), srt(null, forced = true)), "en")
        assertEquals(Choice.Show(1), choice)
    }

    @Test
    fun `a blank preference is subtitles off even when a forced track exists`() {
        // An explicit OFF is not overridden. Forced subs are meant to always
        // show on a disc, but silently switching an explicit OFF back on is the
        // behavior viewers complain about more than a missing translation.
        assertEquals(Choice.Off, SubtitleTrackRules.choose(listOf(srt("en", forced = true)), ""))
        assertEquals(Choice.Off, SubtitleTrackRules.choose(listOf(srt("en", forced = true)), "   "))
    }

    // ── Nothing to choose ──────────────────────────────────────────────────

    @Test
    fun `no text tracks is subtitles off`() {
        assertEquals(Choice.Off, SubtitleTrackRules.choose(emptyList(), "en"))
    }

    @Test
    fun `an unsupported non-bitmap track is skipped rather than chosen`() {
        val unsupported = Candidate("en", MimeTypes.TEXT_SSA, forced = false, supported = false)
        assertEquals(Choice.Off, SubtitleTrackRules.choose(listOf(unsupported), "en"))
    }

    @Test
    fun `the language match ignores case and padding`() {
        val choice = SubtitleTrackRules.choose(listOf(srt(" EN ")), "en")
        assertEquals(Choice.Show(0), choice)
    }

    // ── Format badges ──────────────────────────────────────────────────────

    @Test
    fun `bitmap formats are recognized`() {
        assertTrue(SubtitleTrackRules.isBitmapFormat(MimeTypes.APPLICATION_PGS))
        assertTrue(SubtitleTrackRules.isBitmapFormat(MimeTypes.APPLICATION_VOBSUB))
        assertTrue(SubtitleTrackRules.isBitmapFormat(MimeTypes.APPLICATION_DVBSUBS))
        assertFalse(SubtitleTrackRules.isBitmapFormat(MimeTypes.APPLICATION_SUBRIP))
        assertFalse(SubtitleTrackRules.isBitmapFormat(MimeTypes.TEXT_SSA))
        assertFalse(SubtitleTrackRules.isBitmapFormat(null))
    }

    @Test
    fun `format labels name the engine-relevant formats`() {
        assertEquals("PGS", SubtitleTrackRules.formatLabel(MimeTypes.APPLICATION_PGS))
        assertEquals("ASS", SubtitleTrackRules.formatLabel(MimeTypes.TEXT_SSA))
        assertEquals("SRT", SubtitleTrackRules.formatLabel(MimeTypes.APPLICATION_SUBRIP))
        assertEquals("VTT", SubtitleTrackRules.formatLabel(MimeTypes.TEXT_VTT))
        assertNull(SubtitleTrackRules.formatLabel("application/unknown"))
        assertNull(SubtitleTrackRules.formatLabel(null))
    }

    @Test
    fun `a picker row shows the language and the format, and survives either being absent`() {
        assertEquals(
            "EN \u00b7 ASS",
            SubtitleTrackRules.pickerLabel("en", MimeTypes.TEXT_SSA, "Track 1")
        )
        assertEquals(
            "PGS",
            SubtitleTrackRules.pickerLabel(null, MimeTypes.APPLICATION_PGS, "Track 1")
        )
        assertEquals("EN", SubtitleTrackRules.pickerLabel("en", null, "Track 1"))
        assertEquals("Track 1", SubtitleTrackRules.pickerLabel(null, null, "Track 1"))
    }
}
