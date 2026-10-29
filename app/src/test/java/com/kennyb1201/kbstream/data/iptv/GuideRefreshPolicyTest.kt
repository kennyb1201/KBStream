package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an entry starts a guide refresh — the rule that replaces "12 hours since
 * the last import" as the primary signal.
 *
 * The field case is in the class under test: a guide can be 11 hours old and
 * still hold 17 hours of schedule (fine), or 5 hours old and end in two hours
 * (about to thin). Age cannot tell those apart, and the window can. These cases
 * are the three shapes Kenny's devices actually reach: a healthy guide, a guide
 * whose tail is closing, and one that has already run out.
 */
class GuideRefreshPolicyTest {

    private val now = 1_700_000_000_000L
    private fun hours(h: Long): Long = now + h * 60L * 60L * 1000L

    @Test
    fun `a window that reaches past the lead is left alone`() {
        assertEquals(
            "15h of schedule left is more than the 14h lead, so an entry is not an import",
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(hours(15), now, ageStale = false)
        )
        assertEquals(
            "and exactly the lead is still not due",
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(now + GuideRefreshPolicy.LEAD_MS, now, ageStale = false)
        )
        assertFalse(GuideRefreshPolicy.needsImport(GuideRefreshPolicy.Urgency.NONE))
    }

    @Test
    fun `a window inside the lead is due`() {
        assertEquals(
            "13h left: a refresh has to start now so even a deferred one lands",
            GuideRefreshPolicy.Urgency.DUE,
            GuideRefreshPolicy.urgency(hours(13), now, ageStale = false)
        )
        assertEquals(
            "exactly the read window is still only due - the grid is covered",
            GuideRefreshPolicy.Urgency.DUE,
            GuideRefreshPolicy.urgency(hours(8), now, ageStale = false)
        )
        assertTrue(GuideRefreshPolicy.needsImport(GuideRefreshPolicy.Urgency.DUE))
    }

    @Test
    fun `a window thinner than the grid is urgent`() {
        assertEquals(
            "7h of schedule cannot fill the 8h grid any more",
            GuideRefreshPolicy.Urgency.URGENT,
            GuideRefreshPolicy.urgency(hours(7), now, ageStale = false)
        )
        assertEquals(
            "and a window that already ended is the blank-guide case",
            GuideRefreshPolicy.Urgency.URGENT,
            GuideRefreshPolicy.urgency(now - 60_000L, now, ageStale = false)
        )
        assertTrue(GuideRefreshPolicy.needsImport(GuideRefreshPolicy.Urgency.URGENT))
    }

    @Test
    fun `an empty guide falls back to the age rule`() {
        // An empty guide holds no programs, so its window says nothing: treating
        // "no window" as urgent would start an import on every single entry, and
        // that is the loop the stall cooldown exists to prevent.
        //
        // The fallback is also the RECOVERY half of the blank-guide fix, now
        // that markers are honest: a refused empty feed no longer stamps, so a
        // blank guide ages to `ageStale = true` and this rule turns the next
        // entry into a DUE import (the first case below is the fresh-marker
        // case, which stays NONE on purpose - nobody "fixes" this into an entry
        // loop).
        assertEquals(
            "nothing to thin, and the marker is fresh: leave it alone",
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(null, now, ageStale = false)
        )
        assertEquals(
            "a guide that has never been imported is due by age, as before",
            GuideRefreshPolicy.Urgency.DUE,
            GuideRefreshPolicy.urgency(null, now, ageStale = true)
        )
        assertEquals(
            "and a zero end is the same as none",
            GuideRefreshPolicy.Urgency.DUE,
            GuideRefreshPolicy.urgency(0L, now, ageStale = true)
        )
    }

    @Test
    fun `age still retries a source the window cannot see`() {
        // A partial pass leaves the freshness marker alone, so the source that
        // failed is retried even though its healthy siblings keep the window far
        // ahead - the half of the rule the window alone would lose.
        assertEquals(
            GuideRefreshPolicy.Urgency.DUE,
            GuideRefreshPolicy.urgency(hours(18), now, ageStale = true)
        )
    }

    @Test
    fun `the lead is a read window plus one refresh interval`() {
        assertEquals(
            "derived from EpgWindow, so changing either half moves this with it",
            EpgWindow.READ_FUTURE_MS + EpgWindow.REFRESH_INTERVAL_MS,
            GuideRefreshPolicy.LEAD_MS
        )
        assertTrue(
            "starting a refresh a whole interval before the window closes is the point",
            GuideRefreshPolicy.LEAD_MS > EpgWindow.READ_FUTURE_MS
        )
    }
}
