package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external-subtitle parser, at the one shape it used to drop.
 *
 * WebVTT allows `MM:SS.mmm` (no hours), and real sidecars use it. The timing
 * regex required hours on both sides, so such a file parsed to ZERO cues: the
 * position-driven renderer never showed the subtitles, and
 * `SubtitleSearchHelper.isUsableSubtitleBody` judged the download unusable.
 */
class SubtitleFileParserTest {

    @Test
    fun `srt with hours parses`() {
        val cues = SubtitleFileParser.parse(
            """
            1
            00:00:01,500 --> 00:00:03,000
            Hello there

            2
            00:01:02,250 --> 00:01:04,000
            Second line
            """.trimIndent()
        )
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals("Hello there", cues[0].text)
        assertEquals(62_250L, cues[1].startMs)
    }

    @Test
    fun `webvtt without hours parses`() {
        val cues = SubtitleFileParser.parse(
            """
            WEBVTT

            01:15.300 --> 01:18.100
            Short form
            """.trimIndent()
        )
        assertEquals("a short-form VTT cue must not be dropped", 1, cues.size)
        assertEquals(75_300L, cues[0].startMs)
        assertEquals(78_100L, cues[0].endMs)
        assertEquals("Short form", cues[0].text)
    }

    @Test
    fun `webvtt with hours still parses`() {
        val cues = SubtitleFileParser.parse(
            """
            WEBVTT

            01:00:15.300 --> 01:00:18.100
            Long form
            """.trimIndent()
        )
        assertEquals(1, cues.size)
        assertEquals(3_615_300L, cues[0].startMs)
    }

    @Test
    fun `cues come back in start order`() {
        val cues = SubtitleFileParser.parse(
            """
            00:00:10.000 --> 00:00:11.000
            Later

            00:00:01.000 --> 00:00:02.000
            Earlier
            """.trimIndent()
        )
        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
        assertTrue(cues[0].startMs < cues[1].startMs)
    }
}
