package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.library.TasteRatingSubmission
import java.util.concurrent.TimeUnit

/**
 * The deferred re-drain the ratings queue could not do inside the drain itself.
 *
 * A drain used to schedule a follow-up only for a pause under three seconds
 * (see `scheduleFollowUp`), which left two shapes sitting until the viewer
 * happened to press something (spec 2026-11-18, taste drain F3):
 *
 *  - a **429 park** - a `Retry-After` of a minute or a day, recorded but never
 *    waited out;
 *  - an entry **not yet due** - the 1/5/30/120/720-minute backoff, which was
 *    only ever re-read on the next trigger.
 *
 * A coroutine delay is the wrong tool for either: it dies with the process, and
 * the field case is a TV left on Home for hours. WorkManager's one-shot request
 * outlives the process, which is exactly what "the app is not running" needs.
 *
 * One request, not one per entry: the unique name and [ExistingWorkPolicy.REPLACE]
 * mean the SOONEST wake wins (a later schedule replaces an earlier one with its
 * own, earlier, delay), and at most one wake is ever pending.
 *
 * [doWork] does nothing but run the ordinary drain. Every rule that decides
 * whether anything is sent - enabled, closed leg, due, spent, active profile -
 * is re-checked inside it, so a wake that arrives after another trigger already
 * drained is a harmless no-op. It never asks for a retry: a failed drain leaves
 * its entries queued for the next trigger or the next scheduled wake, while
 * `Result.retry()` would loop straight back into a 429 that is still parked.
 */
class TasteRatingDrainWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            TasteRatingSubmission.drain(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logged, not retried: see the class note. The queue is durable and
            // the next trigger (or wake) reads it again.
            Log.w(TAG, "deferred ratings drain failed: ${e.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "TASTE_RATING_DRAIN_WORKER"

        /** Unique name, so the soonest pending wake is the only one. */
        const val WORK_NAME = "taste_rating_drain"

        /**
         * Schedules one wake [delayMs] from now, replacing any pending wake.
         *
         * Network-gated: a drain with no network can only record failures, and
         * this queue's whole problem is waiting for a moment when the tracker is
         * reachable. Safe from any thread.
         */
        fun enqueue(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<TasteRatingDrainWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
