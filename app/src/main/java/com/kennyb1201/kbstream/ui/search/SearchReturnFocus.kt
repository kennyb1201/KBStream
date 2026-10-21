package com.kennyb1201.kbstream.ui.search

/**
 * The search-result tile a Detail (or Actor/Studio/Collection) page was opened
 * from.
 *
 * Search's COLUMN scroll already survives the trip - its LazyListState is
 * hoisted in MainActivity - but the tile's focus requester lived in the
 * screen's own composition, which the screen swap disposes. Back therefore
 * restored the scroll offset and then left the D-pad on the search field: the
 * viewer's place was on screen but not focused, and one press of Down started
 * the page again from its first rail. The browse pages solved this with a
 * process-scoped record ([com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus]);
 * this is the same shape for the one screen that was left out.
 *
 * [railKey] is what keeps the record honest: the same title can appear on the
 * TMDB "Titles" rail and on any number of add-on rails, and the rail is part of
 * both the tile's focus key ([searchTileFocusKey]) and the record.
 */
internal data class SearchReturnTarget(
    /** The tile's key in the screen's focus-requester registry. */
    val tileKey: String,
    /** Which rail the tile sits in, spelled the way its rail keys it. */
    val railKey: String,
    /** Where the tile sits in its rail, so the rail can be put back on it. */
    val position: Int
)

/**
 * One slot, read once by the search screen as it composes after a drill-down.
 *
 * One slot rather than a map: search has one list, and a chain that leaves
 * search twice (Search -> Detail -> Studio -> Detail) still only ever comes
 * back to search once. The record is aged out like the browse one, so a Back
 * that never comes cannot focus a tile from a session ago.
 */
internal object SearchReturnFocus {

    private class Recorded(val target: SearchReturnTarget, val recordedAtMs: Long)

    private const val VALID_FOR_MS = 30 * 60 * 1000L

    private var recorded: Recorded? = null

    /** Records the tile a drill-down is being opened from. */
    @Synchronized
    fun record(target: SearchReturnTarget) {
        recorded = Recorded(target, System.currentTimeMillis())
    }

    /**
     * Reads the record WITHOUT consuming it, for a rail that wants its scroll
     * offset back while it composes. The screen consumes it afterwards, once
     * the focus has been handed back.
     */
    @Synchronized
    fun peek(): SearchReturnTarget? {
        prune()
        return recorded?.target
    }

    /** Reads the record once and clears it. */
    @Synchronized
    fun consume(): SearchReturnTarget? {
        val target = peek()
        recorded = null
        return target
    }

    private fun prune() {
        val entry = recorded ?: return
        if (System.currentTimeMillis() - entry.recordedAtMs > VALID_FOR_MS) recorded = null
    }
}
