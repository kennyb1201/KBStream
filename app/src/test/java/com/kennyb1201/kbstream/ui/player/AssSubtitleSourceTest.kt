package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The ASS/SSA sidecar preparation rules.
 *
 * These are the parts of the libass feature that can be wrong without a
 * device to see it. A header section libass rejects, a `Format:` line
 * inserted at the wrong column order, or a layout rectangle that loses the
 * video's aspect ratio all produce the same user-visible result: subtitles
 * that are missing or misplaced, on a television, with no error anywhere.
 */
class AssSubtitleSourceTest {

    // --- Detection ---------------------------------------------------------

    @Test
    fun `a script with the script info header is ASS`() {
        assertTrue(
            AssSubtitleSource.isAssContent(
                "[Script Info]\nScriptType: v4.00+\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
            )
        )
    }

    @Test
    fun `a script with only a dialogue line is still ASS`() {
        // A stripped-down sidecar with the header sections removed is exactly
        // what a converter or download service produces.
        assertTrue(
            AssSubtitleSource.isAssContent("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello\n")
        )
    }

    @Test
    fun `a subtitle that is not ASS is not mistaken for one`() {
        assertFalse(AssSubtitleSource.isAssContent("1\n00:00:01,000 --> 00:00:02,000\nHello\n"))
        assertFalse(AssSubtitleSource.isAssContent("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"))
        assertFalse(AssSubtitleSource.isAssContent(""))
        assertFalse(AssSubtitleSource.isAssContent("   \n\n"))
    }

    @Test
    fun `an ASS file name is recognized by extension`() {
        assertTrue(AssSubtitleSource.isAssFileName("episode.ass"))
        assertTrue(AssSubtitleSource.isAssFileName("Episode.SSA"))
        assertFalse(AssSubtitleSource.isAssFileName("episode.srt"))
        assertFalse(AssSubtitleSource.isAssFileName(null))
        // A numeric OpenSubtitles file name must not be read as an extension.
        assertFalse(AssSubtitleSource.isAssFileName("1234567"))
    }

    // --- Header repair -----------------------------------------------------

    @Test
    fun `a complete script is left with its own sections`() {
        val script = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n" +
            "Format: Name, Fontname\nStyle: Default,Whatever\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"

        val out = AssSubtitleSource.normalize(script)

        assertEquals(script, out)
        assertEquals(1, out.split("ScriptType:").size - 1)
        assertEquals(1, out.split("[Events]").size - 1)
    }

    @Test
    fun `a script with no script info header gets one`() {
        val out = AssSubtitleSource.normalize("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n")

        assertTrue(out.startsWith("[Script Info]\nScriptType: v4.00+\n"))
        assertTrue(out.contains("Dialogue: 0,0:00:01.00"))
    }

    @Test
    fun `a script info section without a script type gets one`() {
        val out = AssSubtitleSource.normalize(
            "[Script Info]\nTitle: Something\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
        )

        assertTrue(out.startsWith("[Script Info]\nScriptType: v4.00+\nTitle: Something\n"))
        assertEquals(1, out.split("ScriptType:").size - 1)
    }

    @Test
    fun `a script with no styles section gets the default style`() {
        val out = AssSubtitleSource.normalize(
            "[Script Info]\nScriptType: v4.00+\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
        )

        assertTrue(out.contains("[V4+ Styles]"))
        assertTrue(out.contains("Style: Default,Arial,48,"))
    }

    @Test
    fun `a v4 styles section is left alone`() {
        val out = AssSubtitleSource.normalize(
            "[Script Info]\nScriptType: v4.00\n\n[V4 Styles]\n" +
                "Format: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
        )

        assertFalse(out.contains("[V4+ Styles]"))
        assertTrue(out.contains("Style: Default,Arial,20"))
    }

    @Test
    fun `an events section with no format line gets the v4+ column order`() {
        val out = AssSubtitleSource.normalize(
            "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n" +
                "Format: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n[Events]\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
        )

        val events = out.substringAfter("[Events]")
        assertTrue(
            events.startsWith(
                "\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            )
        )
        assertTrue(events.contains("Dialogue: 0,0:00:01.00"))
    }

    @Test
    fun `an events section with no format line has it inserted once`() {
        val out = AssSubtitleSource.normalize("[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,D,,0,0,0,,Hi\n")

        assertEquals(1, out.split("Format: Layer, Start").size - 1)
    }

    @Test
    fun `the format line of a later section is not mistaken for the events one`() {
        // The styles block's own Format line must not satisfy the events
        // section: doing so would leave libass with Dialogue lines it cannot
        // align, which is the "subtitles selected, nothing drawn" failure.
        val out = AssSubtitleSource.normalize(
            "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n" +
                "Format: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n[Events]\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\n"
        )

        assertEquals(2, out.split("Format:").size - 1)
    }

    @Test
    fun `a byte order mark and windows line endings are removed`() {
        val out = AssSubtitleSource.normalize(
            "\uFEFF[Script Info]\r\nScriptType: v4.00+\r\n\r\n[Events]\r\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi\r\n"
        )

        assertFalse(out.contains("\uFEFF"))
        assertFalse(out.contains("\r"))
        assertTrue(out.endsWith("\n"))
    }

    // --- Fonts -------------------------------------------------------------

    @Test
    fun `only font files are listed`() {
        val dir = Files.createTempDirectory("ass-fonts").toFile()
        try {
            File(dir, "b.ttf").writeText("x")
            File(dir, "a.otf").writeText("x")
            File(dir, "c.ttc").writeText("x")
            File(dir, "readme.txt").writeText("x")
            File(dir, "noext").writeText("x")
            File(dir, "subdir").mkdirs()

            val found = AssSubtitleSource.collectFonts(listOf(dir))

            assertEquals(listOf("a.otf", "b.ttf", "c.ttc"), found.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing font directory is not an error`() {
        assertEquals(emptyList<File>(), AssSubtitleSource.collectFonts(listOf(File("/no/such/dir"))))
    }

    @Test
    fun `the font list is capped`() {
        val dir = Files.createTempDirectory("ass-fonts-limit").toFile()
        try {
            repeat(10) { File(dir, "font$it.ttf").writeText("x") }

            assertEquals(4, AssSubtitleSource.collectFonts(listOf(dir), limit = 4).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Viewport ----------------------------------------------------------

    @Test
    fun `a video already inside the cap is laid out at its own size`() {
        assertEquals(1280, AssSubtitleSource.viewport(1280, 720)[0])
        assertEquals(720, AssSubtitleSource.viewport(1280, 720)[1])
    }

    @Test
    fun `a 4K frame is scaled down and keeps its aspect ratio`() {
        val viewport = AssSubtitleSource.viewport(3840, 2160)
        assertEquals(1920, viewport[0])
        assertEquals(1080, viewport[1])
    }

    @Test
    fun `an ultra wide frame is scaled on its long edge`() {
        val viewport = AssSubtitleSource.viewport(3840, 1600)
        assertEquals(1920, viewport[0])
        assertEquals(800, viewport[1])
    }

    @Test
    fun `an unknown video size falls back to the cap`() {
        assertEquals(1920, AssSubtitleSource.viewport(0, 0)[0])
        assertEquals(1080, AssSubtitleSource.viewport(-1, 720)[1])
    }
}
