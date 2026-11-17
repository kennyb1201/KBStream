package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ASS overlay's event boundaries (scrolling dig five, §4a).
 *
 * The overlay used to re-render a full viewport ten times a second for a held
 * line, because libass can only report "changed" after the render that costs the
 * money. The fix holds to the next moment the script CAN change, and these tests
 * pin that the boundaries are read off the script's own `Dialogue:` lines - once
 * per attach, in order, in milliseconds.
 */
class AssSubtitleBoundariesTest {

    private val script = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:05.00,0:00:09.50,Default,,0,0,0,,A line
        Dialogue: 0,0:00:00.50,0:00:02.00,Default,,0,0,0,,An earlier line
        Comment: 0,0:00:20.00,0:00:25.00,Default,,0,0,0,,not drawn
    """.trimIndent()

    @Test
    fun `the boundaries are every dialogue start and end, ascending`() {
        assertArrayEquals(
            longArrayOf(500L, 2_000L, 5_000L, 9_500L),
            AssSubtitleSource.eventBoundariesMs(script)
        )
    }

    @Test
    fun `a comma decimal separator is a time too`() {
        val comma = "Dialogue: 0,0:00:03,25,0:00:04,75,Default,,0,0,0,,x"
        assertArrayEquals(
            longArrayOf(3_250L, 4_750L),
            AssSubtitleSource.eventBoundariesMs(comma)
        )
    }

    @Test
    fun `hours and minutes are not dropped`() {
        val long = "Dialogue: 0,1:02:03.00,1:02:04.00,Default,,0,0,0,,x"
        assertArrayEquals(
            longArrayOf(3_723_000L, 3_724_000L),
            AssSubtitleSource.eventBoundariesMs(long)
        )
    }

    @Test
    fun `a script with no timing yields nothing, so the tick keeps its old cadence`() {
        assertEquals(0, AssSubtitleSource.eventBoundariesMs("").size)
        assertEquals(0, AssSubtitleSource.eventBoundariesMs("[Script Info]\nScriptType: v4.00+").size)
    }

    @Test
    fun `a malformed line is skipped rather than throwing`() {
        val broken = """
            Dialogue: 0
            Dialogue: 0,,,Default,,0,0,0,,x
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,good
        """.trimIndent()
        assertArrayEquals(longArrayOf(1_000L, 2_000L), AssSubtitleSource.eventBoundariesMs(broken))
    }

    @Test
    fun `boundaries are strictly usable by the hold`() {
        // The tick asks for the first boundary AFTER the playhead; an event that
        // starts exactly at the playhead must not be skipped.
        val boundaries = AssSubtitleSource.eventBoundariesMs(script)
        val atStart = boundaries.firstOrNull { it > 500L }
        assertEquals(2_000L, atStart)
        assertTrue("the list is ascending", boundaries.toList() == boundaries.sorted())
    }
}
