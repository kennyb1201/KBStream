package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player guide's fill budget (field report 2026-11-12, build 262).
 *
 * The overlay used to re-read only when `GuideRevision` bumped, which is an
 * import. A viewer who opened the guide before the Guide screen had finished
 * MATCHING the lineup saw "No guide data" and nothing ever changed it. The tick
 * now runs on blank rows too, which makes it bounded non-stop work unless the
 * budget is right - so the budget is pinned here, not left to the loop.
 */
class ChannelGuideFillPlanTest {

    @Test
    fun `blank rows re-read while the budget lasts`() {
        val plan = channelGuideFillPlan(
            changed = false,
            incomplete = true,
            ticksSpent = 0,
            settled = false
        )

        assertTrue("an early open must keep trying (F1)", plan.read)
        assertFalse(plan.repaint)
        assertFalse("not settled: the row is still loading (F3)", plan.settled)
        assertEquals(1, plan.ticksSpent)
    }

    @Test
    fun `new data buys a fresh budget and a read`() {
        // The import landed, or the guide screen published the lineup with its
        // matches filled in, AFTER the budget was spent.
        val plan = channelGuideFillPlan(
            changed = true,
            incomplete = true,
            ticksSpent = CHANNEL_GUIDE_FILL_TICKS,
            settled = true
        )

        assertTrue("data that landed must be read (S1, S2)", plan.read)
        assertFalse("and the rows are loading again, not final", plan.settled)
        assertEquals(1, plan.ticksSpent)
    }

    @Test
    fun `new data on a complete guide resets the budget without spending it`() {
        val plan = channelGuideFillPlan(
            changed = true,
            incomplete = false,
            ticksSpent = CHANNEL_GUIDE_FILL_TICKS,
            settled = true
        )

        assertTrue(plan.read)
        assertEquals(0, plan.ticksSpent)
        assertFalse(plan.settled)
    }

    @Test
    fun `a spent budget settles the blanks and stops reading`() {
        // S3: after the retries, "No guide data" is the truthful label.
        val plan = channelGuideFillPlan(
            changed = false,
            incomplete = true,
            ticksSpent = CHANNEL_GUIDE_FILL_TICKS,
            settled = false
        )

        assertFalse("no more reads (S4)", plan.read)
        assertTrue("one repaint to drop the loading text", plan.repaint)
        assertTrue(plan.settled)
        assertEquals(CHANNEL_GUIDE_FILL_TICKS, plan.ticksSpent)
    }

    @Test
    fun `a settled blank row is left alone`() {
        // S4: the overlay does not hammer the database on a channel whose guide
        // is genuinely empty - no read, and no repaint churn either.
        val plan = channelGuideFillPlan(
            changed = false,
            incomplete = true,
            ticksSpent = CHANNEL_GUIDE_FILL_TICKS,
            settled = true
        )

        assertFalse(plan.read)
        assertFalse(plan.repaint)
        assertTrue(plan.settled)
    }

    @Test
    fun `a complete guide is not re-read`() {
        val plan = channelGuideFillPlan(
            changed = false,
            incomplete = false,
            ticksSpent = 3,
            settled = false
        )

        assertFalse("every row has data: nothing to do (S4)", plan.read)
        assertFalse(plan.repaint)
        assertEquals(
            "and the budget it did not spend stays available",
            3,
            plan.ticksSpent
        )
    }

    @Test
    fun `the budget is finite and generous enough for a slow matching pass`() {
        assertTrue(
            "a handful of ticks would give up before a big lineup matches",
            CHANNEL_GUIDE_FILL_TICKS >= 8
        )
        assertTrue(
            "but it must stop well inside a minute of ticks",
            CHANNEL_GUIDE_FILL_TICKS <= 120
        )

        // Walking the whole budget from a cold open: exactly `budget` reads,
        // then one settle, then silence.
        var ticks = 0
        var settled = false
        var reads = 0
        var repaints = 0
        repeat(CHANNEL_GUIDE_FILL_TICKS + 5) {
            val plan = channelGuideFillPlan(
                changed = false,
                incomplete = true,
                ticksSpent = ticks,
                settled = settled
            )
            ticks = plan.ticksSpent
            settled = plan.settled
            if (plan.read) reads++
            if (plan.repaint) repaints++
        }
        assertEquals(CHANNEL_GUIDE_FILL_TICKS, reads)
        assertEquals(1, repaints)
    }
}
