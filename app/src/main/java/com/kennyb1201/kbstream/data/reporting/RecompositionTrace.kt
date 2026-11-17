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

    /** The TV Home rails; the counter this object was first built for. */
    const val SURFACE_HOME = "home"

    /** Detail's episode / people / media rails (round four F8b). */
    const val SURFACE_DETAIL = "detail"

    /** The phone's Home rails, where a fling is the per-step mover. */
    const val SURFACE_PHONE_HOME = "phone"

    /**
     * The bucket width. One frame at 60 Hz; see the class note for why the report
     * calls this a 16 ms window rather than a frame.
     */
    const val WINDOW_MS = 16L

    private val lock = Any()

    /**
     * One bucket set per surface. A single set could not answer the question the
     * instrument exists for: "which surface recomposes per step" - a Detail walk
     * and a Home walk counted together are one number that names neither.
     */
    private val surfaces = LinkedHashMap<String, Counters>()

    private class Counters {
        var total = 0L
        var peakPerWindow = 0
        var busyWindows = 0
        var windowIndex = Long.MIN_VALUE
        var windowCount = 0
    }

    private fun counters(surface: String): Counters = surfaces.getOrPut(surface) { Counters() }

    /**
     * One recomposition of one rail item, at [nowMs] on a monotonic clock.
     *
     * Called from a `SideEffect`, i.e. after the composition it counts has been
     * applied, so a composition that Compose skips is not counted.
     */
    fun noteRailItem(nowMs: Long) = noteItem(SURFACE_HOME, nowMs)

    /**
     * One recomposition of one item on [surface] at [nowMs] - the same tick,
     * counted apart from the other surfaces (see the class note).
     */
    fun noteItem(surface: String, nowMs: Long) {
        synchronized(lock) {
            val c = counters(surface)
            val index = nowMs / WINDOW_MS
            if (index != c.windowIndex) {
                // The window that just ended held `windowCount` ticks; a window
                // with none was idle and is not counted at all.
                if (c.windowCount > 0) c.busyWindows++
                c.windowIndex = index
                c.windowCount = 0
            }
            c.windowCount++
            c.total++
            if (c.windowCount > c.peakPerWindow) c.peakPerWindow = c.windowCount
        }
    }

    /**
     * The report's lines, or empty when nothing has recomposed yet - a session
     * that never scrolled Home keeps the report length it had.
     */
    fun lines(): List<String> = synchronized(lock) {
        if (surfaces.isEmpty()) return emptyList()
        surfaces.mapNotNull { (surface, c) ->
            if (c.total <= 0L) return@mapNotNull null
            val busy = c.busyWindows + if (c.windowCount > 0) 1 else 0
            // Home keeps the sentence the round-three report introduced, so a
            // paste read against an older one still lines up; the other surfaces
            // name themselves instead of pretending to be rail items.
            val what = if (surface == SURFACE_HOME) "railItems" else "$surface items"
            "recomp: $what=${c.total} peak=${c.peakPerWindow} per ${WINDOW_MS}ms " +
                "busyWindows=$busy"
        }
    }

    /** Clears the counters (tests). */
    fun reset() {
        synchronized(lock) { surfaces.clear() }
    }
}
