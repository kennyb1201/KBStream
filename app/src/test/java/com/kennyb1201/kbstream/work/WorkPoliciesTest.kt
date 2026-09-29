package com.kennyb1201.kbstream.work

import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `work/` package had no tests because every class in it is a
 * `CoroutineWorker`, and running one needs WorkManager and a device. The part
 * that does not is the decision a worker makes about an attempt that has just
 * finished, which is what lives in [WorkPolicies].
 *
 * These are worth pinning because both failure modes are quiet in the log.
 * Retrying a doomed flush forever wakes a TV box every backoff interval for
 * work that cannot succeed; abandoning one that would have succeeded strands
 * the write in `sync_outbox` with nothing to say so.
 */
class WorkPoliciesTest {

    private fun refresh(attempted: Boolean, success: Boolean) =
        WatchedStatusRepository.BackgroundRefreshResult(
            attempted = attempted,
            changed = false,
            refreshedCount = 0,
            success = success
        )

    // ── the cloud outbox flush ──────────────────────────────────────────

    @Test
    fun `a flushed outbox is done at any attempt`() {
        for (attempt in 0..6) {
            assertEquals(
                "attempt $attempt",
                WorkPolicies.Outcome.Done,
                WorkPolicies.outboxFlushOutcome(flushed = true, runAttemptCount = attempt)
            )
        }
    }

    @Test
    fun `an unflushed outbox retries below the ceiling`() {
        assertEquals(
            WorkPolicies.Outcome.Retry,
            WorkPolicies.outboxFlushOutcome(flushed = false, runAttemptCount = 0)
        )
        assertEquals(
            WorkPolicies.Outcome.Retry,
            WorkPolicies.outboxFlushOutcome(
                flushed = false,
                runAttemptCount = WorkPolicies.OUTBOX_MAX_ATTEMPTS - 1
            )
        )
    }

    @Test
    fun `an unflushed outbox gives up at the ceiling and stays given up`() {
        assertEquals(
            WorkPolicies.Outcome.GiveUp,
            WorkPolicies.outboxFlushOutcome(
                flushed = false,
                runAttemptCount = WorkPolicies.OUTBOX_MAX_ATTEMPTS
            )
        )
        // The attempt count only climbs, so a later attempt must not slide
        // back into retrying.
        assertEquals(
            WorkPolicies.Outcome.GiveUp,
            WorkPolicies.outboxFlushOutcome(
                flushed = false,
                runAttemptCount = Int.MAX_VALUE
            )
        )
    }

    @Test
    fun `the outbox ceiling is three attempts`() {
        // Asserted as a value rather than read from the constant: the change
        // worth catching is somebody raising the ceiling, and comparing the
        // constant to itself would not catch it.
        assertEquals(3, WorkPolicies.OUTBOX_MAX_ATTEMPTS)
    }

    // ── the periodic watched-state refresh ──────────────────────────────

    @Test
    fun `a sync with nothing to refresh is done, not a failure`() {
        // No followed titles, or no Simkl account: the repository answers
        // attempted=false together with success=false, and retrying that only
        // asks Simkl the same nothing on the next backoff.
        assertEquals(
            WorkPolicies.Outcome.Done,
            WorkPolicies.simklSyncOutcome(refresh(attempted = false, success = false))
        )
    }

    @Test
    fun `a successful sync is done and a failed one retries`() {
        assertEquals(
            WorkPolicies.Outcome.Done,
            WorkPolicies.simklSyncOutcome(refresh(attempted = true, success = true))
        )
        assertEquals(
            WorkPolicies.Outcome.Retry,
            WorkPolicies.simklSyncOutcome(refresh(attempted = true, success = false))
        )
    }

    @Test
    fun `the sync round never gives up`() {
        // Unlike the outbox there is no ceiling here: the next attempt is the
        // next periodic tick, hours away, so a give-up would throw away a
        // refresh that was about to work.
        for (attempted in listOf(true, false)) {
            for (success in listOf(true, false)) {
                assertNotEquals(
                    "attempted=$attempted success=$success",
                    WorkPolicies.Outcome.GiveUp,
                    WorkPolicies.simklSyncOutcome(refresh(attempted, success))
                )
            }
        }
    }

    // ── live-TV reminders ───────────────────────────────────────────────

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun `a reminder for a program that has not started is armed`() {
        assertTrue(WorkPolicies.shouldArmReminder(now + hour, now + 2 * hour, now))
    }

    @Test
    fun `a reminder for a program that already started is not armed`() {
        // By then it is the guide banner's job: arming it would announce
        // something the user is already late for.
        assertFalse(WorkPolicies.shouldArmReminder(now, now + hour, now))
        assertFalse(WorkPolicies.shouldArmReminder(now - hour, now + hour, now))
    }

    @Test
    fun `a reminder with no known end is still armed`() {
        // The guide can hand back a program with no end time. isStale reads 0
        // as "unknown" rather than "ended", so the reminder survives.
        assertTrue(WorkPolicies.shouldArmReminder(now + hour, 0L, now))
    }

    @Test
    fun `staleness wins over a future start`() {
        // Reachable when the guide rewrites a reminder's end: the stored start
        // is still ahead of the clock while the stored end is already past the
        // grace window. The staleness check is what must decide, or the job
        // would be armed for a program that no longer exists.
        val endedLongAgo = now - (ReminderRules.STALE_GRACE_MS + 1)
        assertFalse(WorkPolicies.shouldArmReminder(now + hour, endedLongAgo, now))
    }
}
