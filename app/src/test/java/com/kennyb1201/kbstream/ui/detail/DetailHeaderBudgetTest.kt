package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 6 of the 2026-11-21 Detail spec: the fixed header must never push the
 * tab strip off the screen, and its collapsed height must leave the first
 * content row.
 *
 * The overview is the only unbounded part of that header, so its line budget is
 * the rule. These are the numbers a 960x540dp television gets.
 */
class DetailHeaderBudgetTest {

    @Test
    fun `a 540dp screen keeps four collapsed lines, the strip and a row`() {
        assertEquals(4, DetailHeaderBudget.collapsedLines(540))
        assertTrue(
            "the collapsed header plus the overview leaves the first content row",
            DetailHeaderBudget.CHROME_DP +
                DetailHeaderBudget.TOGGLE_DP +
                DetailHeaderBudget.collapsedLines(540) * DetailHeaderBudget.LINE_DP +
                DetailHeaderBudget.FIRST_CONTENT_DP <= 540
        )
    }

    @Test
    fun `expanding may push content down but never the strip off screen`() {
        listOf(480, 540, 720, 1080).forEach { height ->
            val expanded = DetailHeaderBudget.expandedLines(height)
            assertTrue(
                "expanded $height dp: header ${DetailHeaderBudget.CHROME_DP} + toggle " +
                    "${DetailHeaderBudget.TOGGLE_DP} + $expanded lines fits the viewport",
                DetailHeaderBudget.CHROME_DP +
                    DetailHeaderBudget.TOGGLE_DP +
                    expanded * DetailHeaderBudget.LINE_DP <= height
            )
            assertTrue(
                "expanding never shows fewer lines than collapsing at $height dp",
                expanded >= DetailHeaderBudget.collapsedLines(height)
            )
        }
    }

    @Test
    fun `short screens shrink the overview instead of the strip`() {
        assertTrue(
            "480dp gets the minimum collapsed summary",
            DetailHeaderBudget.collapsedLines(480) < DetailHeaderBudget.collapsedLines(540)
        )
        assertEquals(
            DetailHeaderBudget.MIN_COLLAPSED,
            DetailHeaderBudget.collapsedLines(360)
        )
        assertEquals(
            "a tall screen keeps the page's eight-line expanded cap",
            DetailHeaderBudget.MAX_EXPANDED,
            DetailHeaderBudget.expandedLines(1080)
        )
    }
}
