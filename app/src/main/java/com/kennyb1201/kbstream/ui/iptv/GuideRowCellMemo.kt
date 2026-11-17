package com.kennyb1201.kbstream.ui.iptv

/**
 * Remembers a channel's grid cells between window answers (dig four, 2026-11-16,
 * §2b).
 *
 * `GuideGridView` derives its rows with one `remember(items, programsByChannel,
 * window)` that maps EVERY channel through [rowCells]. `programsByChannel`
 * changes on each page answer - `mergeWindowAnswer` republishes the map - so a
 * page of channels landing re-ran the cell build for the whole lineup, allocating
 * every cell again, even though only the requested page gained programs.
 *
 * The page request itself is bounded and deduped; this is the other half. A cell
 * list is a pure function of its channel key, its programs and the window, so it
 * can be remembered per channel and reused until one of those three changes.
 *
 * `GridProgram` and `GridWindow` are data classes, so equality is structural:
 * a merge that touches one page leaves every other channel's program list equal,
 * and those channels reuse the list they had - no rebuild, no allocation, and the
 * same instances reaching Compose (which can then skip those rows).
 *
 * Bounded by an LRU of channels: a five-figure lineup must not pin a cell list
 * for every channel it ever showed. Pure - it needs no Context, no database and
 * no clock, so the reuse rule is testable on its own.
 */
internal object GuideRowCellMemo {

    /**
     * Channels whose cells are remembered. A page is 80 rows and the viewport
     * holds a few, so a few hundred covers everything a session scrolls through
     * with room to spare.
     */
    private const val MAX_CHANNELS = 512

    private data class Entry(
        val programs: List<GridProgram>,
        val window: GridWindow,
        val cells: List<GridCell>
    )

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_CHANNELS
    }

    /**
     * [rowCells] for one channel, from the memo when nothing that shapes the
     * result has changed. The returned list is the memo's own instance: callers
     * must not mutate it.
     */
    fun cells(
        channelKey: String,
        programs: List<GridProgram>,
        window: GridWindow
    ): List<GridCell> {
        synchronized(entries) {
            val hit = entries[channelKey]
            if (hit != null && hit.window == window && hit.programs == programs) {
                return hit.cells
            }
            val fresh = rowCells(channelKey, programs, window)
            entries[channelKey] = Entry(programs, window, fresh)
            return fresh
        }
    }

    /** Drops every remembered list (a different playlist, or tests). */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
