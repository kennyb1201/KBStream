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
    private val hour = 60L * 60L * 1000L

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
    fun `an empty guide is urgent, retried at the refresh cadence`() {
        // Field 2026-11-12, Fire TV Stick, 0.9.253: 15,425 channels, zero
        // programs in the window, no newest program end - and nothing imported
        // until Kenny pressed reimport. This rule used to answer NONE for that
        // state whenever the freshness marker was under twelve hours old, so an
        // empty guide could stand behind a young marker while the background
        // worker was throttled on the same box. The empty state IS the blank
        // guide, and it has to heal on entry.
        assertEquals(
            "nothing to draw, and nobody has tried: import now",
            GuideRefreshPolicy.Urgency.URGENT,
            GuideRefreshPolicy.urgency(null, now, ageStale = false)
        )
        assertEquals(
            "and a stale marker still reads as urgent, as it did",
            GuideRefreshPolicy.Urgency.URGENT,
            GuideRefreshPolicy.urgency(null, now, ageStale = true)
        )
        assertEquals(
            "a zero end is the same as none",
            GuideRefreshPolicy.Urgency.URGENT,
            GuideRefreshPolicy.urgency(0L, now, ageStale = true)
        )
    }

    @Test
    fun `an empty guide is not re-imported on every entry`() {
        // The loop guard: the retry is spaced off the last pass that STARTED,
        // whoever ran it, at the same "don't hammer a provider" interval the
        // watchdog's entry cooldown already uses. A feed that is empty on
        // purpose (an EmptyFeedKeptLive pass) is therefore not re-downloaded on
        // every open - but a failed pass does not silence the guide for a whole
        // cadence either, which is what "only a manual reimport fixes it" was.
        assertEquals(
            "a pass started ten minutes ago is still recent for an empty guide",
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(
                endsAtMs = null,
                nowMs = now,
                ageStale = false,
                lastAttemptMs = now - 10 * 60_000L
            )
        )
        assertEquals(
            "twenty-nine minutes is still inside the cooldown",
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(
                endsAtMs = null,
                nowMs = now,
                ageStale = false,
                lastAttemptMs = now - GuideRefreshPolicy.EMPTY_GUIDE_RETRY_MS + 1
            )
        )
        assertEquals(
            "a whole cooldown without an attempt is the retry",
            GuideRefreshPolicy.Urgency.URGENT,
            GuideRefreshPolicy.urgency(
                endsAtMs = null,
                nowMs = now,
                ageStale = false,
                lastAttemptMs = now - GuideRefreshPolicy.EMPTY_GUIDE_RETRY_MS
            )
        )
        assertEquals(
            "a clock put back must not silence the retry",
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(
                endsAtMs = null,
                nowMs = now,
                ageStale = false,
                lastAttemptMs = now + hour
            )
        )
    }

    @Test
    fun `the empty-guide rule does not touch a guide that has a window`() {
        // The attempt stamp only spaces the EMPTY case: a guide with a schedule
        // is still judged by where that schedule ends, or a pass every few
        // minutes would re-import a perfectly healthy guide.
        assertEquals(
            GuideRefreshPolicy.Urgency.NONE,
            GuideRefreshPolicy.urgency(
                endsAtMs = hours(15),
                nowMs = now,
                ageStale = false,
                lastAttemptMs = now - hour
            )
        )
        assertEquals(
            GuideRefreshPolicy.Urgency.DUE,
            GuideRefreshPolicy.urgency(
                endsAtMs = hours(13),
                nowMs = now,
                ageStale = false,
                lastAttemptMs = now - hour
            )
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
