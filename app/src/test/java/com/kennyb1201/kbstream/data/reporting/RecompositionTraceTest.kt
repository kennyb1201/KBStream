package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recomposition count S1 needs (spec 2026-11-16, scrolling round three F4;
 * dig four's "the counter is still the missing piece").
 *
 * F4 is gated on the measurement: if the rail items recompose only when focus or
 * their data changes, the scoping work is unnecessary, and wrapping the rails'
 * lambdas in `remember` without the count is how stale captures get introduced.
 * The app had no recomposition hook at all, so the number could not be taken.
 *
 * These cases pin the arithmetic of the counter, not the Compose behaviour that
 * feeds it: total ticks, the worst 16 ms window, and that idle gaps are not
 * counted as busy windows (which would dilute the number toward zero and
 * under-state a real per-step cost).
 */
class RecompositionTraceTest {

    @Before
    fun clean() {
        RecompositionTrace.reset()
    }

    private fun line(): String? =
        RecompositionTrace.lines().firstOrNull { it.startsWith("recomp:") }

    @Test
    fun `nothing is printed before a rail item recomposes`() {
        assertNull("a session that never scrolled Home keeps the report length", line())
    }

    @Test
    fun `a walk that recomposes one item per frame reads one per window`() {
        // 10 ticks, one every 16 ms: each lands in its own window.
        repeat(10) { i -> RecompositionTrace.noteRailItem(nowMs = i * RecompositionTrace.WINDOW_MS) }

        assertEquals(
            "ten items over ten windows: no window holds more than one",
            "recomp: railItems=10 first=0 peak=1 per 16ms busyWindows=10",
            line()
        )
    }

    @Test
    fun `a step that recomposes the whole rail reads as a peak, not an average`() {
        // The shape F4 is looking for: one focus change, every rail item
        // recomposing in the same 16 ms window (here 40 items), three times.
        var now = 0L
        repeat(3) {
            repeat(40) { RecompositionTrace.noteRailItem(nowMs = now) }
            now += RecompositionTrace.WINDOW_MS
        }

        assertTrue(
            "the peak is the number that decides F4: ${line()}",
            line() == "recomp: railItems=120 first=0 peak=40 per 16ms busyWindows=3"
        )
    }

    @Test
    fun `idle gaps are not counted as busy windows`() {
        RecompositionTrace.noteRailItem(nowMs = 0)
        // A minute of sitting still, then one more recomposition.
        RecompositionTrace.noteRailItem(nowMs = 60_000)

        assertEquals(
            "two ticks, not 'two ticks spread over 3750 frames': " +
                "the number is read as cost per step, so empty windows must not " +
                "appear at all",
            "recomp: railItems=2 first=0 peak=1 per 16ms busyWindows=2",
            line()
        )
    }

    @Test
    fun `a row appearing is counted apart from a row invalidating`() {
        // The 0.9.393 paste read `railItems=194 peak=14 per 16ms` and could not
        // say whether fourteen cards were invalidated in one frame (a bug) or
        // fourteen were composing for the first time (a row streaming in, which
        // is the design). The call site now says which, and only the difference
        // is a fix's target.
        var now = 0L
        repeat(14) { RecompositionTrace.noteRailItem(nowMs = now, firstComposition = true) }
        now += RecompositionTrace.WINDOW_MS
        repeat(3) { RecompositionTrace.noteRailItem(nowMs = now) }

        assertEquals(
            "the peak still counts both, so it lines up with an older paste; " +
                "`first` says how much of it was a row arriving",
            "recomp: railItems=17 first=14 peak=14 per 16ms busyWindows=2",
            line()
        )
    }

    @Test
    fun `a caller that cannot tell adds to the total as before`() {
        // The other surfaces (Detail's episode rail, the phone's Home) call the
        // same counter without the flag; their line must not read as if every
        // tick were an invalidation.
        RecompositionTrace.noteItem(RecompositionTrace.SURFACE_DETAIL, 0L)

        assertEquals(
            "an unflagged tick is not a first composition and not a lie about one",
            "recomp: detail items=1 first=0 peak=1 per 16ms busyWindows=1",
            RecompositionTrace.lines().single()
        )
    }

    @Test
    fun `a window is 16 ms and the report says so`() {
        assertEquals(
            "the line names a 16 ms window rather than a frame: at other refresh " +
                "rates the two differ and this code cannot see the display",
            16L,
            RecompositionTrace.WINDOW_MS
        )
    }
}
