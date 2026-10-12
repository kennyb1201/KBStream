package com.kennyb1201.kbstream.work

import com.kennyb1201.kbstream.data.iptv.EpgWindow
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository

/**
 * The decisions the background workers make, pulled out of the workers.
 *
 * Every worker in this package is a `CoroutineWorker`: running one at all needs
 * WorkManager and therefore a device, which is why this package had no tests.
 * What each worker decides *about a finished attempt* — retry, give up, or
 * leave the reminder alone — is a pure function of that attempt's result,
 * though, so those live here and are unit tested. Same split as
 * [ReminderRules] carrying the reminder arithmetic out of [ReminderWorker].
 */
internal object WorkPolicies {

    /**
     * Bounded wakeups before the cloud outbox flush stops retrying.
     *
     * Giving up loses nothing: the rows stay in `sync_outbox`, so the next app
     * launch — or the next write that strands — picks them up. Retrying past
     * this only keeps waking a device that is still signed out, or still has
     * no connectivity to offer.
     */
    const val OUTBOX_MAX_ATTEMPTS = 3

    /** What a worker should hand back to WorkManager after one attempt. */
    enum class Outcome {
        /** Finished — including the case where there was nothing to do. */
        Done,

        /** Unfinished and worth another attempt, on WorkManager's backoff. */
        Retry,

        /**
         * Unfinished, and another attempt would not change that. Maps to the
         * same `Result.success()` as [Done], but for the opposite reason: the
         * work is being abandoned rather than completed. The two are kept apart
         * because a policy that collapsed this into [Retry] would spend exactly
         * the battery it exists to protect.
         */
        GiveUp
    }

    /**
     * The cloud outbox flush. `flushed` is false while the session is still
     * restoring or the device is still offline — neither is a failure, and
     * neither is improved by an immediate retry.
     */
    fun outboxFlushOutcome(flushed: Boolean, runAttemptCount: Int): Outcome = when {
        flushed -> Outcome.Done
        runAttemptCount >= OUTBOX_MAX_ATTEMPTS -> Outcome.GiveUp
        else -> Outcome.Retry
    }

    /**
     * The periodic watched-state refresh.
     *
     * `attempted` is false when there was nothing to refresh — no followed
     * titles, or no Simkl account. That is a finished round, not a failure:
     * retrying it would ask Simkl the same nothing again on the next backoff.
     */
    fun simklSyncOutcome(
        result: WatchedStatusRepository.BackgroundRefreshResult
    ): Outcome = when {
        !result.attempted -> Outcome.Done
        result.success -> Outcome.Done
        else -> Outcome.Retry
    }

    /**
     * Whether a stored live-TV reminder should be armed for its start time.
     *
     * A program that has already started is the guide banner's job — arming it
     * would announce something the user is already late for — and one whose
     * program has ended past [ReminderRules.STALE_GRACE_MS] is only wasted
     * work. Encoding both here keeps that ordering out of the worker's loop
     * over the store.
     */
    fun shouldArmReminder(startUtcMillis: Long, endUtcMillis: Long, nowMs: Long): Boolean =
        startUtcMillis > nowMs && !ReminderRules.isStale(endUtcMillis, nowMs)

    /**
     * What the periodic guide refresh should do with an attempt, decided
     * before it opens a socket.
     *
     * Three answers, because the two gates it runs are different in kind: a
     * fresh guide is finished, a playing device is merely not-now, and only
     * the third case is the multi-minute import the worker exists for.
     */
    enum class EpgRefreshDecision {
        /** The guide was imported recently enough; re-storing it buys nothing. */
        FRESH,

        /** Something is playing; try again on WorkManager's backoff. */
        DEFER,

        /** Nothing in the way — go and import. */
        IMPORT
    }

    /**
     * How much fresher than the refresh cadence a guide may be and still be
     * worth skipping.
     *
     * One hour under [EpgWindow.REFRESH_INTERVAL_MS], so a run on the plain
     * periodic tick — which lands a whole interval after the last import — is
     * never counted as fresh, while a run that lands early (a retry, a manual
     * trigger, an enqueue from the cloud-sync applier) is. Without the slack
     * the guard would be a coin flip on WorkManager's own scheduling jitter.
     */
    const val EPG_FRESH_SLACK_MS = 60L * 60L * 1000L

    /** The age below which an import is redundant. See [EPG_FRESH_SLACK_MS]. */
    const val EPG_MIN_AGE_MS = EpgWindow.REFRESH_INTERVAL_MS - EPG_FRESH_SLACK_MS

    /**
     * The guide refresh's two pre-flight gates, in the order they matter.
     *
     * Freshness first: it is the answer that ends the attempt, and a guide
     * that does not need importing does not need the device to stop playing
     * first. Playback second, because a deferred run stays deferred only until
     * the viewer stops — WorkManager retries on its backoff, so the import
     * happens when the box is idle again.
     *
     * `lastImportMs` of 0 (never imported) is deliberately not fresh: every
     * unset marker reads as the epoch, which is older than any window.
     *
     * [EpgRefreshDecision.DEFER] maps to `Result.retry()` at the call site and
     * never to `Result.failure()` — failure cancels the periodic work outright,
     * so a film would silently cost the install its guide refreshes.
     */
    fun epgRefreshDecision(
        lastImportMs: Long,
        nowMs: Long,
        playbackActive: Boolean
    ): EpgRefreshDecision = when {
        nowMs - lastImportMs < EPG_MIN_AGE_MS -> EpgRefreshDecision.FRESH
        playbackActive -> EpgRefreshDecision.DEFER
        else -> EpgRefreshDecision.IMPORT
    }
}
