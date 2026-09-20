package com.kennyb1201.kbstream.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live-TV reminder delivery rules.
 *
 * Two failures matter equally: a reminder that never alerts (the state this
 * feature was in before — the guide's own banner only exists while the guide
 * is on screen), and one that alerts repeatedly or after the programme is
 * over.
 */
class ReminderRulesTest {

    private val now = 1_700_000_000_000L

    // ── keys ────────────────────────────────────────────────────────

    @Test
    fun `reminder keys are channel plus start`() {
        assertEquals("bbc1|1700000000000", ReminderRules.reminderKey("bbc1", now))
    }

    @Test
    fun `the same channel at different times is a different reminder`() {
        assertNotEquals(
            ReminderRules.reminderKey("bbc1", now),
            ReminderRules.reminderKey("bbc1", now + 3_600_000)
        )
    }

    // ── due ─────────────────────────────────────────────────────────

    @Test
    fun `a programme that has started is due`() {
        assertTrue(ReminderRules.isDue(now - 1_000, now, alreadyNotified = false))
    }

    @Test
    fun `a programme starting right now is due`() {
        assertTrue(ReminderRules.isDue(now, now, alreadyNotified = false))
    }

    @Test
    fun `a programme that has not started is not due`() {
        assertFalse(ReminderRules.isDue(now + 1, now, alreadyNotified = false))
    }

    @Test
    fun `an already announced reminder never fires twice`() {
        assertFalse(ReminderRules.isDue(now - 1_000, now, alreadyNotified = true))
    }

    @Test
    fun `a reminder with no schedule is never due`() {
        // The guide falls back to 0 when a programme has no start time.
        assertFalse(ReminderRules.isDue(0L, now, alreadyNotified = false))
        assertFalse(ReminderRules.isDue(-5L, now, alreadyNotified = false))
    }

    // ── staleness ───────────────────────────────────────────────────

    @Test
    fun `a programme that just ended is still worth keeping`() {
        assertFalse(ReminderRules.isStale(now - 1_000, now))
    }

    @Test
    fun `a programme beyond the grace window is stale`() {
        assertTrue(
            ReminderRules.isStale(now - ReminderRules.STALE_GRACE_MS - 1, now)
        )
    }

    @Test
    fun `the grace window boundary is inclusive`() {
        // Exactly at the boundary the reminder is dropped, matching the
        // guide's own prune rule so the two never disagree about what is live.
        assertTrue(ReminderRules.isStale(now - ReminderRules.STALE_GRACE_MS, now))
    }

    @Test
    fun `a reminder with no end time is never stale`() {
        assertFalse(ReminderRules.isStale(0L, now))
    }

    // ── arming ──────────────────────────────────────────────────────

    @Test
    fun `delay is the wait until the programme starts`() {
        assertEquals(60_000L, ReminderRules.delayUntilStart(now + 60_000, now))
    }

    @Test
    fun `an already started programme needs no delay`() {
        // Arming is skipped for these, but a zero delay must never go negative
        // and schedule work in the past.
        assertEquals(0L, ReminderRules.delayUntilStart(now - 60_000, now))
        assertEquals(0L, ReminderRules.delayUntilStart(now, now))
    }

    // ── notification ids ────────────────────────────────────────────

    @Test
    fun `notification ids are stable and non-negative`() {
        val key = ReminderRules.reminderKey("bbc1", now)
        val id = ReminderRules.notificationId(key)
        assertEquals(id, ReminderRules.notificationId(key))
        assertTrue(id >= 0)
    }

    @Test
    fun `different reminders get different ids`() {
        assertNotEquals(
            ReminderRules.notificationId(ReminderRules.reminderKey("bbc1", now)),
            ReminderRules.notificationId(ReminderRules.reminderKey("itv1", now))
        )
    }
}
