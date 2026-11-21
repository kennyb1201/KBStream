package com.kennyb1201.kbstream.ui.detail

/**
 * How many lines the overview may paint in the Detail page's FIXED header
 * (spec 2026-11-21, rule 6).
 *
 * The overview lives above the tab strip on every tab, so its height is header
 * height: every line it takes is a line the content no longer has. Uncapped, an
 * expanded overview on a 960x540dp television could push the strip itself off
 * the bottom of the screen - and a strip the viewer cannot see is a press that
 * lands where they are not looking.
 *
 * Two budgets, because the two states make different promises:
 *
 *  - COLLAPSED must leave the strip AND at least one content row. It is what
 *    every tab opens with, so it is the height that must not eat the page.
 *  - EXPANDED is the viewer asking for the text; it may push the content down,
 *    but it still may not push the strip off the screen.
 *
 * Pure and kept out of the screen so a JVM test can pin the numbers. The
 * constants are the measured chrome around the overview (the top spacer, the
 * logo/title, the meta line, the action row, the genre chips and the strip
 * itself) at this page's type scale; [FIRST_CONTENT_DP] is roughly one episode
 * card, which is the row a viewer expects to see under the strip.
 */
internal object DetailHeaderBudget {

    /** One line of the overview's body text at this page's scale. */
    const val LINE_DP = 20

    /** The "View more"/"View less" toggle and its margin. */
    const val TOGGLE_DP = 34

    /** Everything above the strip that is not the overview: spacer, art, meta, actions, genres, strip. */
    const val CHROME_DP = 300

    /** The first content row the collapsed overview must leave visible. */
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
