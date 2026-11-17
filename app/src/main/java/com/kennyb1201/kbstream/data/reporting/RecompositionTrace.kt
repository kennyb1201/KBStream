package com.kennyb1201.kbstream.data.reporting

/**
 * How often the Home rail items recompose, in the words the diagnostics report
 * prints (spec 2026-11-16, scrolling round three F4 / dig four's S1).
 *
 * Round three's F4 was gated on a measurement: if the rail items recompose only
 * when focus or their data changes, wrapping the rails' lambdas in `remember`
 * buys nothing and risks stale captures; if they recompose per step, the scoping
 * work is owed. Nothing in the app could produce that count - no recomposition
 * hook existed anywhere - so the number could not be taken. This is that hook.
 *
 * What it counts, and what it deliberately leaves as words rather than a
 * number:
 *
 *  - one tick per recomposition of a Home rail ITEM (the item's own composable
 *    scope, via a `SideEffect`, so a skipped item does not tick);
 *  - the ticks are bucketed into 16 ms windows, and the report prints the total
 *    and the WORST window. On a 60 Hz box a window is about a frame, so
 *    `peak=7 per 16ms` reads as "seven rail items recomposed in one frame". It
 *    is stated as a 16 ms window, not as a frame, because at other refresh
 *    rates the two differ and this code cannot see the display.
 *  - only windows that actually saw a tick are counted as busy, so a long idle
 *    stretch does not dilute the number.
 *
 * The clock is passed in rather than read here: the arithmetic is the part worth
 * testing, and it is testable only if it needs no Android.
 *
 * Counts only, never an id or a title - the same rule [ImageLoadTrace] and
 * [DetailTrace] follow.
 */
internal object RecompositionTrace {

    /**
     * The bucket width. One frame at 60 Hz; see the class note for why the report
     * calls this a 16 ms window rather than a frame.
     */
    const val WINDOW_MS = 16L

    private val lock = Any()

    private var total = 0L
    private var peakPerWindow = 0
    private var busyWindows = 0
    private var windowIndex = Long.MIN_VALUE
    private var windowCount = 0

    /**
     * One recomposition of one rail item, at [nowMs] on a monotonic clock.
     *
     * Called from a `SideEffect`, i.e. after the composition it counts has been
     * applied, so a composition that Compose skips is not counted.
     */
    fun noteRailItem(nowMs: Long) {
        synchronized(lock) {
            val index = nowMs / WINDOW_MS
            if (index != windowIndex) {
                // The window that just ended held `windowCount` ticks; a window
                // with none was idle and is not counted at all.
                if (windowCount > 0) busyWindows++
                windowIndex = index
                windowCount = 0
            }
            windowCount++
            total++
            if (windowCount > peakPerWindow) peakPerWindow = windowCount
        }
    }

    /**
     * The report's lines, or empty when nothing has recomposed yet - a session
     * that never scrolled Home keeps the report length it had.
     */
    fun lines(): List<String> = synchronized(lock) {
        if (total <= 0L) return emptyList()
        val busy = busyWindows + if (windowCount > 0) 1 else 0
        listOf(
            "recomp: railItems=$total peak=$peakPerWindow per ${WINDOW_MS}ms " +
                "busyWindows=$busy"
        )
    }

    /** Clears the counters (tests). */
    fun reset() {
        synchronized(lock) {
            total = 0L
            peakPerWindow = 0
            busyWindows = 0
            windowIndex = Long.MIN_VALUE
            windowCount = 0
        }
    }
}
