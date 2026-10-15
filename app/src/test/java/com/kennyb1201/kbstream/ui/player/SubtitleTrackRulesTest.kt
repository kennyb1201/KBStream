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
        supported: Boolean = true,
        title: String? = null
    ) = Candidate(language, MimeTypes.APPLICATION_SUBRIP, forced, supported, title)

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

    // ── SDH, regular and forced ────────────────────────────────
    //
    // Language beats kind for the honest kinds (SDH, then a regular full
    // translation), but the forced flag is demoted as a block below the untagged
    // tracks. The flag being set on the one track a viewer wants subtitles for
    // is the report these cases exist to answer.

    @Test
    fun `SDH beats the regular and the forced track in the preferred language`() {
        // Field check 1: English SDH, English regular, English forced.
        val tracks = listOf(
            srt("en", forced = true),
            srt("en"),
            srt("en", title = "English SDH")
        )
        assertEquals(Choice.Show(2), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `a regular track beats the forced one in the preferred language`() {
        // Field check 2: no SDH in the file.
        val tracks = listOf(srt("en"), srt("en", forced = true))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `the forced track is still chosen when its language has nothing else`() {
        // Field check 3: better than nothing.
        val tracks = listOf(srt("en", forced = true))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `SDH is recognized from the words a track names itself with`() {
        listOf("English SDH", "SDH", "English (HI)", "English CC", "English - Hearing Impaired")
            .forEach { name ->
                val tracks = listOf(srt("en"), srt("en", title = name))
                assertEquals(
                    "an SDH track named \"$name\" has to beat the plain one",
                    Choice.Show(1),
                    SubtitleTrackRules.choose(tracks, "en")
                )
            }
    }

    @Test
    fun `a plain track name still beats the forced one`() {
        // Generous detection is not blind detection: nothing in "English"
        // names a kind, so the track is regular and outranks the forced one.
        val tracks = listOf(srt("en", title = "English"), srt("en", forced = true))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `an SDH-named track is taken at its name even when the forced flag is set`() {
        // The two signals contradict each other, and the flag is the one this
        // ordering exists to stop trusting.
        val tracks = listOf(srt("en"), srt("en", forced = true, title = "English SDH"))
        assertEquals(Choice.Show(1), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `the untagged bucket ranks by kind too`() {
        val tracks = listOf(
            srt(null, forced = true),
            srt(null),
            srt(null, title = "SDH")
        )
        assertEquals(Choice.Show(2), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `an untagged regular track beats an untagged forced one`() {
        val tracks = listOf(srt(null), srt(null, forced = true))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `an untagged SDH track beats a forced track tagged for the language`() {
        // The forced flag is the one signal a language tag does NOT let win on:
        // the untagged SDH track is the viewer's own language far more often
        // than a preferred-tagged forced flag is honest.
        val tracks = listOf(srt(null, title = "SDH"), srt("en", forced = true))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `a preferred-tagged forced track loses to an untagged SDH track`() {
        val tracks = listOf(srt("en", forced = true), srt(null, title = "English SDH"))
        assertEquals(Choice.Show(1), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `a preferred-tagged forced track loses to an untagged regular track`() {
        val tracks = listOf(srt("en", forced = true), srt(null))
        assertEquals(Choice.Show(1), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `another language's forced track still loses to an untagged SDH track`() {
        // Step 3 precedes step 4 regardless of the forced track's language.
        val tracks = listOf(srt("fr", forced = true), srt(null, title = "SDH"))
        assertEquals(Choice.Show(1), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `the other-language fallback does not lead with its forced track`() {
        val tracks = listOf(srt("fr", forced = true), srt("fr"))
        assertEquals(Choice.Show(1), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `a forced track in another language is the last thing chosen before off`() {
        val tracks = listOf(srt("fr", forced = true))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "en"))
    }

    @Test
    fun `a bitmap match in the preferred language still goes to the other engine`() {
        // Unchanged: which bitmap of several is a question for the engine that
        // can actually draw one.
        val tracks = listOf(srt(null, title = "SDH"), pgs("en"))
        assertEquals(Choice.NeedsMpv(1), SubtitleTrackRules.choose(tracks, "en"))
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

    // ── Forced-only mode ───────────────

    @Test
    fun `forced mode shows only a forced track`() {
        val tracks = listOf(srt("en"), srt("en", forced = true))
        assertEquals(Choice.Show(1), SubtitleTrackRules.chooseForced(tracks, "en"))
    }

    @Test
    fun `forced mode with no forced track is off, not a full translation`() {
        // A forced-only viewer asked for foreign dialogue only. Falling back to
        // the full track is the failure mode this mode exists to avoid.
        assertEquals(Choice.Off, SubtitleTrackRules.chooseForced(listOf(srt("en")), "en"))
    }

    @Test
    fun `forced mode without a preferred language still finds a forced track`() {
        assertEquals(
            Choice.Show(0),
            SubtitleTrackRules.chooseForced(listOf(srt("fr", forced = true)), "")
        )
    }

    @Test
    fun `forced mode prefers the preferred language's forced track`() {
        val tracks = listOf(srt("fr", forced = true), srt("en", forced = true))
        assertEquals(Choice.Show(1), SubtitleTrackRules.chooseForced(tracks, "en"))
    }

    @Test
    fun `forced mode prefers an untagged forced track over a tagged one`() {
        val tracks = listOf(srt("fr", forced = true), srt(null, forced = true))
        assertEquals(Choice.Show(1), SubtitleTrackRules.chooseForced(tracks, "en"))
    }

    @Test
    fun `forced mode can hand a bitmap forced track to the other engine`() {
        assertEquals(
            Choice.NeedsMpv(0),
            SubtitleTrackRules.chooseForced(listOf(pgs("en", forced = true)), "en")
        )
    }

    // ── Mode dispatch ───────────────

    @Test
    fun `off mode is off whatever the file carries`() {
        val tracks = listOf(srt("en"), srt("en", forced = true))
        assertEquals(Choice.Off, SubtitleTrackRules.choose(tracks, "en", SubtitleModeRules.OFF))
    }

    @Test
    fun `on mode is the language rules`() {
        val tracks = listOf(srt("fr"), srt("en"))
        assertEquals(
            SubtitleTrackRules.choose(tracks, "en"),
            SubtitleTrackRules.choose(tracks, "en", SubtitleModeRules.ON)
        )
    }

    @Test
    fun `forced mode dispatches to the forced rules`() {
        val tracks = listOf(srt("en"), srt("en", forced = true))
        assertEquals(
            SubtitleTrackRules.chooseForced(tracks, "en"),
            SubtitleTrackRules.choose(tracks, "en", SubtitleModeRules.FORCED)
        )
    }

    @Test
    fun `forced mode ignores the blank-preference off rule`() {
        // With the language rules a blank preference is Off even for a forced
        // track; forced mode is a positive request, so it still shows one.
        val tracks = listOf(srt("en", forced = true))
        assertEquals(Choice.Off, SubtitleTrackRules.choose(tracks, "", SubtitleModeRules.ON))
        assertEquals(Choice.Show(0), SubtitleTrackRules.choose(tracks, "", SubtitleModeRules.FORCED))
    }
}
