package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KIND tags on a subtitle row: which of the three rows that all read
 * "ENGLISH" is the SDH one, which is the forced / signs one, which is a
 * commentary.
 *
 * The evidence a row actually has is small - a language tag, and then whatever
 * the container, the add-on or the file name says - so these pin the reading of
 * every marker, and the one thing the reading must NOT do: turn a language code
 * into a kind ("hi" is Hindi in the language field, which is why that field is
 * never passed in here).
 */
class SubtitleKindRulesTest {

    @Test
    fun `an sdh track is named by any of the words that mean it`() {
        assertEquals(listOf("SDH"), SubtitleKindRules.of("English SDH"))
        assertEquals(listOf("SDH"), SubtitleKindRules.of("Show.S01E02.English.HI.srt"))
        assertEquals(listOf("SDH"), SubtitleKindRules.of("Hearing Impaired"))
        assertEquals(listOf("SDH"), SubtitleKindRules.of("hard of hearing"))
        assertEquals(listOf("SDH"), SubtitleKindRules.of("Closed Captions"))
        assertEquals(listOf("SDH"), SubtitleKindRules.of("English CC"))
    }

    @Test
    fun `the signs and the foreign-parts tracks read as forced`() {
        assertEquals(listOf("FORCED"), SubtitleKindRules.of("Forced"))
        assertEquals(listOf("FORCED"), SubtitleKindRules.of("Signs & Songs"))
        assertEquals(listOf("FORCED"), SubtitleKindRules.of("Signs / Foreign parts"))
    }

    @Test
    fun `a commentary and a machine translation say so`() {
        assertEquals(listOf("COMMENTARY"), SubtitleKindRules.of("Audio Commentary"))
        assertEquals(listOf("MT"), SubtitleKindRules.of("English machine translated"))
    }

    @Test
    fun `the tags read in one string, in a fixed order`() {
        assertEquals("SDH · FORCED", SubtitleKindRules.tagText("English SDH forced"))
    }

    @Test
    fun `text that names no kind stays untagged`() {
        assertNull(SubtitleKindRules.tagText(null))
        assertNull(SubtitleKindRules.tagText(""))
        assertNull(SubtitleKindRules.tagText("English"))
        assertNull(SubtitleKindRules.tagText("Show.S01E02.1080p.WEB-DL.srt"))
        // Tokens, not substrings: "this" is not HI and "format" is not MT.
        assertNull(SubtitleKindRules.tagText("This is not a caption format"))
        assertTrue(SubtitleKindRules.of("English").isEmpty())
    }

    @Test
    fun `the picker row carries the kind between the language and the format`() {
        assertEquals(
            "EN · SDH · SRT",
            SubtitleTrackRules.pickerLabel("en", MimeTypes.APPLICATION_SUBRIP, "Track 1", "English SDH")
        )
        assertEquals(
            "EN · FORCED · PGS",
            SubtitleTrackRules.pickerLabel("en", MimeTypes.APPLICATION_PGS, "Track 2", forced = true)
        )
    }

    @Test
    fun `a row with no title and no forced flag reads exactly as it did before`() {
        assertEquals("EN · SRT", SubtitleTrackRules.pickerLabel("en", MimeTypes.APPLICATION_SUBRIP, "Track 1"))
        assertEquals("EN", SubtitleTrackRules.pickerLabel("en", null, "Track 1"))
        assertEquals("Track 1", SubtitleTrackRules.pickerLabel(null, null, "Track 1"))
    }
}
