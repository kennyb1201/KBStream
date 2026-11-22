package com.kennyb1201.kbstream.ui.detail

/**
 * How many lines the overview may paint in the Detail page's Details tab.
 *
 * The overview scrolls with the tab now (2026-11-22), so its height is no
 * longer a fixed-header budget. The caps still matter on a 960x540dp
 * television: they keep the collapsed summary short enough to scan and the
 * expanded form from becoming a page of its own.
 *
 * Two budgets, because the two states make different promises:
 *
 *  - COLLAPSED is the summary the tab opens with.
 *  - EXPANDED is the viewer asking for more text; it never shows fewer lines
 *    than the collapsed state.
 *
 * Pure and kept out of the screen so a JVM test can pin the numbers. The
 * constants are the measured chrome around the overview at this page's type
 * scale; [FIRST_CONTENT_DP] is roughly one episode card, retained so the
 * collapsed cap still leaves useful content visible on a short screen.
 */
internal object DetailHeaderBudget {

    /** One line of the overview's body text at this page's scale. */
    const val LINE_DP = 20

    /** The "View more"/"View less" toggle and its margin. */
    const val TOGGLE_DP = 34

    /** The fixed page chrome around the scrolling tab content. */
    const val CHROME_DP = 300

    /** The content row the collapsed overview should leave visible. */
    const val FIRST_CONTENT_DP = 120

    /** Below this the overview is not worth showing; it stays a summary. */
    const val MIN_COLLAPSED = 2

    /** The collapsed cap the page has always used. */
    const val MAX_COLLAPSED = 4

    /** The expanded cap the spec allows on a tall screen. */
    const val MAX_EXPANDED = 8

    /** Lines the collapsed overview may paint, with the strip and a row below it. */
    fun collapsedLines(screenHeightDp: Int): Int {
        val room = screenHeightDp - CHROME_DP - TOGGLE_DP - FIRST_CONTENT_DP
        return (room / LINE_DP).coerceIn(MIN_COLLAPSED, MAX_COLLAPSED)
    }

    /**
     * Lines the expanded overview may paint, keeping the strip on screen.
     *
     * Never fewer than [collapsedLines]: expanding a block is a request for
     * MORE text, and a budget that answered with less would make the toggle a
     * press that shrinks what it expands.
     */
    fun expandedLines(screenHeightDp: Int): Int {
        val room = screenHeightDp - CHROME_DP - TOGGLE_DP
        val lines = (room / LINE_DP).coerceIn(MIN_COLLAPSED, MAX_EXPANDED)
        return maxOf(lines, collapsedLines(screenHeightDp))
    }
}
