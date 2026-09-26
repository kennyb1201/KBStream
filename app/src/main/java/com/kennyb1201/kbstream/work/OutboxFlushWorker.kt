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
import com.kennyb1201.kbstream.data.sync.SupabaseSync

/**
 * Flushes the durable cloud-sync outbox when the app is closed.
 *
 * The outbox used to be in-memory only: a write enqueued while offline was
 * lost the moment the process died, so the cloud never saw it and the only
 * recovery was a manual "Force full resync". `sync_outbox` now persists those
 * writes, and this worker is what finishes them — WorkManager's request
 * outlives the process, so the flush happens once connectivity returns even
 * if the app is not running.
 *
 * Network-gated and KEEP-coalesced (see [enqueue]): enqueuing on every
 * stranded flush cannot pile up duplicate workers.
 */
class OutboxFlushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // MainApplication.onCreate has already called SupabaseSync.init in
        // this process, so the client is building and the session restoring;
        // flushOutboxWhenReady waits for both before flushing.
        return try {
            if (SupabaseSync.flushOutboxWhenReady(applicationContext)) {
                Result.success()
            } else if (runAttemptCount >= MAX_ATTEMPTS) {
                // Still signed-out or still offline after several tries: stop
                // waking up. The rows stay in `sync_outbox`, so the next app
                // launch (or the next stranded flush) picks them up.
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "outbox flush failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OUTBOX_FLUSH_WORKER"

        /** Unique name so repeated enqueues coalesce into one pending flush. */
        const val WORK_NAME = "sync_outbox_flush"

        /** Bounded wakeups before giving up; the rows persist regardless. */
        private const val MAX_ATTEMPTS = 3

        /**
         * Enqueue one network-gated flush. Safe to call from any thread and
         * as often as writes strand — [ExistingWorkPolicy.KEEP] keeps a single
         * pending request.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxFlushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
