package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefetch distance every paginated surface shares.
 *
 * The rule that was wrong everywhere was a flat six items, so a page was only
 * requested as the last card came on screen and arrived a beat later — which
 * read as the end of the list. These pin the replacement: a runway of two
 * viewports, floored so a narrow rail still has room and capped so a
 * wall-to-wall grid does not fetch its next page immediately.
 */
class InfiniteScrollTest {

    // ── the runway ──────────────────────────────────────────────────────

    @Test
    fun `a narrow viewport gets the floor`() {
        assertEquals(PREFETCH_MIN_ITEMS, paginationPrefetchRunway(0))
        assertEquals(PREFETCH_MIN_ITEMS, paginationPrefetchRunway(1))
        assertEquals(PREFETCH_MIN_ITEMS, paginationPrefetchRunway(2))
    }

    @Test
    fun `a rail prefetches two screenfuls out`() {
        assertEquals(10, paginationPrefetchRunway(5))
        assertEquals(12, paginationPrefetchRunway(6))
    }

    @Test
    fun `a grid is capped so it does not page on its first frame`() {
        assertEquals(PREFETCH_MAX_ITEMS, paginationPrefetchRunway(20))
        assertEquals(PREFETCH_MAX_ITEMS, paginationPrefetchRunway(100))
    }

    // ── the decision ────────────────────────────────────────────────────

    @Test
    fun `nothing to page and no laid-out item are both refused`() {
        assertFalse(shouldPrefetchNextPage(0, 0, 6))
        assertFalse(shouldPrefetchNextPage(-1, 100, 6))
        assertFalse(shouldPrefetchNextPage(0, 100, 0))
    }

    @Test
    fun `a viewport parked at the start of a long list does not fetch`() {
        // 100 items, a 6-wide rail: the trigger sits at index 88, nowhere
        // near the first screenful.
        assertFalse(shouldPrefetchNextPage(5, 100, 6))
    }

    @Test
    fun `the request starts two screenfuls from the end`() {
        // Runway is 12 (six wide); the page is asked for at index 8, not at
        // index 94 as the old flat-six rule would have it.
        assertFalse(shouldPrefetchNextPage(7, 20, 6))
        assertTrue(shouldPrefetchNextPage(8, 20, 6))
        assertTrue(shouldPrefetchNextPage(19, 20, 6))
    }

    @Test
    fun `a page shorter than the runway is prefetched immediately`() {
        // 10 items with a 12-item runway: the whole list is within reach, so
        // the first laid-out frame already crosses the line.
        assertTrue(shouldPrefetchNextPage(0, 10, 6))
    }

    @Test
    fun `a wide grid uses its capped runway`() {
        // 24 laid-out cells cap the runway at 16, so a 40-item grid pages at
        // index 24 rather than at its second frame.
        assertFalse(shouldPrefetchNextPage(23, 40, 24))
        assertTrue(shouldPrefetchNextPage(24, 40, 24))
    }
}
