package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.debrid.TorBoxLibrarySync
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodic "new TorBox cloud files → Library" round (see [TorBoxLibrarySync]).
 *
 * Only armed while the "Add TorBox Cloud to Library" toggle is on AND a TorBox
 * API key is set, so a device with neither never wakes for it. A round is
 * cheap once caught up: [com.kennyb1201.kbstream.data.debrid.TorBoxCloudStore]
 * means only torrents new since the last round cost a TMDB lookup.
 */
class TorBoxLibraryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        TorBoxLibrarySync.sync(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed round is not worth a retry storm (the next periodic run
        // covers it), but it is worth a log line.
        Log.e(TAG, "TorBox library sync failed: ${e.message}", e)
        Result.success()
    }

    companion object {
        private const val TAG = "TORBOX_LIBRARY_WORKER"

        /** Unique periodic work name (owned here so toggle and startup agree). */
        const val PERIODIC_WORK_NAME = "torbox_library_sync"

        private const val ONESHOT_WORK_NAME = "torbox_library_sync_now"
        private const val INTERVAL_HOURS = 12L

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun periodicRequest() =
            PeriodicWorkRequestBuilder<TorBoxLibraryWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints())
                // Every request carries the tag, so turning the toggle off can
                // cancel the periodic round and any immediate one together.
                .addTag(TAG)
                .build()

        /**
         * Keeps the scheduled round in step with the toggle: on only when the
         * viewer enabled it AND a key is present. `runImmediate = true` is for
         * the settings toggle, so flipping it on produces rows without waiting
         * for the first periodic window.
         */
        fun syncScheduleForPrefs(context: Context, runImmediate: Boolean = false) {
            syncSchedule(
                context,
                enabled = AppPreferences.getTorboxLibrarySync(context) &&
                    AppPreferences.getTorboxApiKey(context).isNotBlank(),
                runImmediate = runImmediate
            )
        }

        fun syncSchedule(context: Context, enabled: Boolean, runImmediate: Boolean = false) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelAllWorkByTag(TAG)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest()
            )
            if (runImmediate) {
                workManager.enqueueUniqueWork(
                    ONESHOT_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<TorBoxLibraryWorker>()
                        .setConstraints(constraints())
                        .addTag(TAG)
                        .build()
                )
            }
        }
    }
}
