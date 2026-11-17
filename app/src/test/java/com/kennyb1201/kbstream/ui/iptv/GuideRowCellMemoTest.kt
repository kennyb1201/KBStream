package com.kennyb1201.kbstream.ui.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The grid's per-channel cell memo (dig four, 2026-11-16, §2b).
 *
 * The bug it fixes: `rows` is one `remember(items, programsByChannel, window)`
 * that maps EVERY channel through `rowCells`, and `programsByChannel` changes on
 * each page answer - so a page of programs landing rebuilt the cells for the
 * whole lineup. These cases pin the reuse rule that stops it, and the three
 * things that must still invalidate a channel's cells.
 */
class GuideRowCellMemoTest {

    private val window = GridWindow(
        startUtcMillis = 1_700_000_000_000L,
        endUtcMillis = 1_700_000_000_000L + 6 * 60 * 60 * 1000L
    )

    @Before
    fun clean() {
        GuideRowCellMemo.clear()
    }

    private fun program(
        channelKey: String,
        title: String,
        startMinute: Int,
        lengthMinutes: Int = 30
    ) = GridProgram(
        channelKey = channelKey,
        title = title,
        description = null,
        startUtcMillis = window.startUtcMillis + startMinute * 60_000L,
        endUtcMillis = window.startUtcMillis + (startMinute + lengthMinutes) * 60_000L
    )

    @Test
    fun `an unchanged channel reuses the very list it had`() {
        val programs = listOf(
            program("bbc1", "News", 0),
            program("bbc1", "Film", 30)
        )
        val first = GuideRowCellMemo.cells("bbc1", programs, window)
        // A different list instance with equal contents is what a map merge that
        // copied every channel would hand over.
        val second = GuideRowCellMemo.cells("bbc1", programs.toList(), window)

        assertSame(
            "equal programs mean the same cells: no rebuild, no allocation, and " +
                "the same instance reaching Compose",
            first,
            second
        )
        assertEquals(
            "and the answer is what rowCells would have built",
            rowCells("bbc1", programs, window),
            first
        )
    }

    @Test
    fun `new programs, a new window or another channel all rebuild`() {
        val programs = listOf(program("bbc1", "News", 0))
        val first = GuideRowCellMemo.cells("bbc1", programs, window)

        val changedPrograms = listOf(program("bbc1", "News at Noon", 0))
        val afterPrograms = GuideRowCellMemo.cells("bbc1", changedPrograms, window)
        assertTrue(
            "a program's own change is a rebuild",
            afterPrograms != first &&
                afterPrograms.any { it.title == "News at Noon" }
        )

        val nextWindow = GridWindow(
            startUtcMillis = window.endUtcMillis,
            endUtcMillis = window.endUtcMillis + 6 * 60 * 60 * 1000L
        )
        GuideRowCellMemo.cells("bbc1", changedPrograms, nextWindow)
        val back = GuideRowCellMemo.cells("bbc1", changedPrograms, window)
        assertEquals(
            "and coming back to a previous window is a rebuild, not stale cells",
            rowCells("bbc1", changedPrograms, window),
            back
        )

        assertTrue(
            "another channel keeps its own list",
            GuideRowCellMemo.cells("itv1", programs, window) !== first
        )
    }

    @Test
    fun `a page landing elsewhere leaves this channel's cells alone`() {
        val bbc = listOf(program("bbc1", "News", 0))
        val itv = listOf(program("itv1", "Quiz", 0))
        val bbcBefore = GuideRowCellMemo.cells("bbc1", bbc, window)
        GuideRowCellMemo.cells("itv1", itv, window)
        // The merged map now carries a changed ITV page; BBC is untouched.
        GuideRowCellMemo.cells(
            "itv1",
            listOf(program("itv1", "Quiz", 0), program("itv1", "Film", 30)),
            window
        )

        assertSame(
            "the channel the page did not mention is not rebuilt - the whole " +
                "lineup used to be",
            bbcBefore,
            GuideRowCellMemo.cells("bbc1", bbc.toList(), window)
        )
    }

    @Test
    fun `a channel with no programs is remembered too`() {
        val empty = GuideRowCellMemo.cells("empty", emptyList(), window)
        assertTrue(
            "no programs still draws the window's gap cells, so there is real work " +
                "here to reuse",
            empty.isNotEmpty() && empty.all { it.isGap }
        )
        assertSame(
            "and it is reused, so a page answer does not re-derive the gap rows",
            empty,
            GuideRowCellMemo.cells("empty", emptyList(), window)
        )
    }
}
