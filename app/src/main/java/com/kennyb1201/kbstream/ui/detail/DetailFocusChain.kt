package com.kennyb1201.kbstream.ui.detail

/**
 * The direction of a vertical D-pad press on the detail page.
 *
 * Named apart from Compose's own `FocusDirection` on purpose: that one is the
 * platform's, with four directions and movement as the answer, and this is the
 * page's own spelling of the two presses its rails answer.
 */
internal enum class DetailFocusDirection { UP, DOWN }

/**
 * Every row of the detail page that takes part in its vertical focus chain, in
 * the order they are painted top to bottom.
 *
 * This list IS the page's row order, and it is the only place that order is
 * written down: [DetailFocusChain.nextRow] walks it, so UP and DOWN from every
 * rail agree with each other by construction instead of each rail carrying its
 * own idea of what is above and below it. That is what the reported bug was -
 * a fix applied to one transition (network UP, then production UP) moved the
 * stranding to the next one, because there was no single answer to "what is
 * below this row".
 *
 * The order is the PAINTED order, which is not the order the rails are listed
 * in the source: the people rail renders above the network rail, which renders
 * above the production companies (see the `peoplerow`, `networkrow` and
 * `productionrow` items). A chain that did not mirror the screen would send
 * UP into a row the viewer can see is below them.
 *
 * Rows that carry no vertical handler of their own are deliberately absent:
 * the overview block, the ratings strip, the fact cards and the review /
 * collection / recommendation rails are leaf surfaces reached by the default
 * search, and naming them here would change behaviour the chain is not about.
 */
internal enum class DetailRow {
    /** The fixed title/logo and PLAY row above the list - always composed. */
    HEADER,

    /** The tag rail, the first focusable row inside the list. */
    KEYWORDS,

    /** The season chips and episode cards, which share one section. */
    EPISODES,

    /** The writer/director/cast rail. */
    PEOPLE,

    /** The network chips (series with networks only). */
    NETWORK,

    /** The production-company chips. */
    PRODUCTION
}

/**
 * Which rows this page actually has.
 *
 * Every field is the SAME expression that renders its row, so "the row exists"
 * and "the row is on screen" cannot drift apart - which is the second half of
 * the reported bug: a rail the scroll had disposed used to read as a rail that
 * was not there, and the press then skipped it.
 */
internal data class DetailRowPresence(
    val hasKeywordsRow: Boolean,
    val hasEpisodesRow: Boolean,
    val hasPeopleRow: Boolean,
    val hasNetworkRow: Boolean,
    val hasProductionRow: Boolean
)

/**
 * The detail page's vertical focus chain: which row a press leaves for, decided
 * once, from one list.
 *
 * Kept pure and separate from the screen so the rule can be pinned without a
 * device: as with [DetailChipColumns], what uses it is a key handler, which no
 * unit test can press.
 */
internal object DetailFocusChain {

    /**
     * Whether [row] is on this page at all.
     *
     * [DetailRow.HEADER] is the one row that always exists: it lives in the
     * fixed header above the list, so it is composed whatever the payload, and
     * it is what makes the chain incapable of stranding a press - UP always has
     * somewhere to end.
     */
    fun exists(row: DetailRow, presence: DetailRowPresence): Boolean = when (row) {
        DetailRow.HEADER -> true
        DetailRow.KEYWORDS -> presence.hasKeywordsRow
        DetailRow.EPISODES -> presence.hasEpisodesRow
        DetailRow.PEOPLE -> presence.hasPeopleRow
        DetailRow.NETWORK -> presence.hasNetworkRow
        DetailRow.PRODUCTION -> presence.hasProductionRow
    }

    /**
     * The nearest row to [from] in [direction] that this page actually has, or
     * null when there is none that way.
     *
     * Rows that do not exist are stepped over rather than landed on, which is
     * what makes the move honest about a page that is missing a rail: UP out of
     * a series' network row with no cast reaches the episode rail, not a row
     * that is not there.
     *
     * Null is only ever reached going DOWN out of the bottom row of the page -
     * [DetailRow.HEADER] always exists and sits at the top, so an upward walk
     * always ends on it. The caller reads null as "the default search may have
     * this press" rather than as a move (see the screen's handler).
     */
    fun nextRow(
        from: DetailRow,
        direction: DetailFocusDirection,
        presence: DetailRowPresence
    ): DetailRow? {
        val rows = DetailRow.entries
        val fromIndex = rows.indexOf(from)
        val step = if (direction == DetailFocusDirection.UP) -1 else 1

        var index = fromIndex + step
        while (index in rows.indices) {
            val candidate = rows[index]
            if (exists(candidate, presence)) {
                return candidate
            }
            index += step
        }

        return null
    }
}
