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
import com.kennyb1201.kbstream.data.notifications.NewEpisodeChecker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodic "has a new episode aired?" round (see [NewEpisodeChecker]).
 *
 * Separate from [SimklSyncWorker] on purpose: the check needs no Simkl
 * account, only the shows the profile watches, so it keeps working for users
 * who never connected a tracker.
 */
class NewEpisodeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        NewEpisodeChecker(applicationContext).run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed round is not worth a retry storm (the next periodic run
        // covers it), but it is worth a log line.
        Log.e(TAG, "new episode check failed: ${e.message}", e)
        Result.success()
    }

    companion object {
        private const val TAG = "NEW_EPISODE_WORKER"

        /** Unique periodic work name (owned here so the toggle and startup agree). */
        const val PERIODIC_WORK_NAME = "new_episode_periodic_check"

        private const val ONESHOT_WORK_NAME = "new_episode_immediate_check"
        private const val INTERVAL_HOURS = 12L

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun periodicRequest() =
            PeriodicWorkRequestBuilder<NewEpisodeWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints())
                // Every request carries the tag, so disabling the toggle can
                // cancel the periodic round and any immediate one together.
                .addTag(TAG)
                .build()

        /**
         * Keeps scheduled work in step with the user's "New Episode
         * Notifications" toggle — the pref alone isn't enough, or a disabled
         * device would still wake twice a day just to bail out.
         *
         * Pass `runImmediate = true` only from the settings toggle: startup
         * arms the periodic round alone, so launching the app never spends a
         * TMDB request per followed show.
         */
        fun syncSchedule(
            context: Context,
            enabled: Boolean,
            runImmediate: Boolean = false
        ) {
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
                    OneTimeWorkRequestBuilder<NewEpisodeWorker>()
                        .setConstraints(constraints())
                        .addTag(TAG)
                        .build()
                )
            }
        }
    }
}
