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
 *  - one tick per composition of a Home rail ITEM (the item's own composable
 *    scope, via a `SideEffect`, so a skipped item does not tick);
 *  - the ticks are bucketed into 16 ms windows, and the report prints the total
 *    and the WORST window. On a 60 Hz box a window is about a frame, so
 *    `peak=7 per 16ms` reads as "seven rail items composed in one frame". It
 *    is stated as a 16 ms window, not as a frame, because at other refresh
 *    rates the two differ and this code cannot see the display.
 *  - only windows that actually saw a tick are counted as busy, so a long idle
 *    stretch does not dilute the number.
 *  - `first=` is the half the 0.9.393 paste could not separate (0.9.394). A
 *    `SideEffect` ticks on the item's FIRST composition as well as on every
 *    invalidation, so `railItems=194 peak=14 per 16ms` was ambiguous: fourteen
 *    cards being INVALIDATED in one frame is a bug, while fourteen cards being
 *    composed for the first time in one frame is a row streaming in - which is
 *    the design, not a defect. The call site passes `firstComposition` for the
 *    tick it knows is the first, so `peak - first` is the number a fix is owed
 *    on. `peak` itself still counts both, so it reads the same as it did in
 *    older pastes and the two can be compared.
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
     * The three phone surfaces dig five named as the place the count was still
     * owed (round-three S1 asked for it on TV Home; a phone fling is the same
     * question on a different mover). A Guide row, a Library tile and a Catalog
     * tile each cost real work per composition, and until they are counted the
     * per-tile wiring fixes stay capped at code shape.
     */
    const val SURFACE_GUIDE = "guide"
    const val SURFACE_LIBRARY = "library"
    const val SURFACE_CATALOG = "catalog"

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
        var firstCompositions = 0L
        var peakPerWindow = 0
        var busyWindows = 0
        var windowIndex = Long.MIN_VALUE
        var windowCount = 0
    }

    private fun counters(surface: String): Counters = surfaces.getOrPut(surface) { Counters() }

    /**
     * One composition of one rail item, at [nowMs] on a monotonic clock.
     *
     * Called from a `SideEffect`, i.e. after the composition it counts has been
     * applied, so a composition that Compose skips is not counted.
     *
     * [firstComposition] is the call site's answer to "has this card composed
     * before", so a row appearing is not read as a row invalidating (see the
     * class note). It defaults to false: a caller that cannot tell contributes
     * to the total exactly as it did before the split existed.
     */
    fun noteRailItem(nowMs: Long, firstComposition: Boolean = false) =
        noteItem(SURFACE_HOME, nowMs, firstComposition)

    /**
     * One composition of one item on [surface] at [nowMs] - the same tick,
     * counted apart from the other surfaces (see the class note).
     */
    fun noteItem(surface: String, nowMs: Long, firstComposition: Boolean = false) {
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
            if (firstComposition) c.firstCompositions++
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
            // `first=` sits between the total and the peak because it is what
            // makes the peak readable: peak is every composition in the worst
            // window, first is how many of those were a card composing for the
            // first time. See the class note.
            "recomp: $what=${c.total} first=${c.firstCompositions} " +
                "peak=${c.peakPerWindow} per ${WINDOW_MS}ms " +
                "busyWindows=$busy"
        }
    }

    /** Clears the counters (tests). */
    fun reset() {
        synchronized(lock) { surfaces.clear() }
    }
}
