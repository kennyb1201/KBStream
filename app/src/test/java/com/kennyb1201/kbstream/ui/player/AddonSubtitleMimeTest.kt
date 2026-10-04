package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveSubtitleMimeFrom] decides which parser Media3 hands an addon
 * subtitle to.
 *
 * The extension settles the common case. The content probe exists for the
 * case it cannot: a Stremio subtitle addon routinely serves a numeric file id
 * with no extension, and defaulting an ASS script to SubRip made the track the
 * viewer picked render nothing.
 */
class AddonSubtitleMimeTest {

    private val assScript = """
        [Script Info]
        Title: Example

        [Events]
        Format: Layer, Start, End, Style, Text
        Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hi
    """.trimIndent()

    private val srtScript = """
        1
        00:00:01,000 --> 00:00:03,000
        Hi
    """.trimIndent()

    @Test
    fun `the extension wins for the common formats`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, resolveSubtitleMimeFrom("https://x/a.srt", ""))
        assertEquals(MimeTypes.TEXT_VTT, resolveSubtitleMimeFrom("https://x/a.vtt", ""))
        assertEquals(MimeTypes.TEXT_SSA, resolveSubtitleMimeFrom("https://x/a.ass", ""))
        assertEquals(MimeTypes.TEXT_SSA, resolveSubtitleMimeFrom("https://x/a.ssa", ""))
    }

    @Test
    fun `the extension is read before a query string`() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            resolveSubtitleMimeFrom("https://x/a.ass?token=abc", "")
        )
    }

    @Test
    fun `an extension-less ASS script is sniffed as SSA`() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            resolveSubtitleMimeFrom("https://x/1234567", assScript)
        )
    }

    @Test
    fun `an extension-less SRT script still resolves to SubRip`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            resolveSubtitleMimeFrom("https://x/1234567", srtScript)
        )
    }

    @Test
    fun `an empty probe keeps the SubRip default`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            resolveSubtitleMimeFrom("https://x/1234567", "")
        )
    }
}
