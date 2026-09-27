package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.cache.DiskSweep
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheMaintenance
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.update.AppUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Every periodic disk cleanup in one place: bounds the TMDB JSON cache, hands
 * the database file its space back, and sweeps the caches that grow by file
 * COUNT rather than by bytes.
 *
 * The JSON trim is also enforced by
 * [com.kennyb1201.kbstream.data.tmdb.TmdbRepository] on the write path, so this
 * worker is not what stops that growth. It exists for the parts that cannot run
 * from a screen: `VACUUM` needs an exclusive lock on the database and
 * temporarily as much free storage as the file itself, so a launch-time pass
 * would either collide with the Home reads already in flight or be wrongly
 * skipped as busy.
 *
 * Deliberately NOT network-gated. Everything here is local, and an install that
 * has been offline for weeks is exactly one that has been accumulating rows.
 */
class CacheMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = WatchHistoryDatabase.getInstance(applicationContext).tmdbJsonCacheDao()
            val trim = TmdbJsonCacheMaintenance.trim(dao)
            val reclaimed = TmdbJsonCacheMaintenance.reclaimDatabaseSpace(applicationContext, dao)
            // The small caches are bounded by file count, so the byte budget
            // above never sees them: clock-named subtitle copies and avatar
            // imports that were picked and then abandoned.
            val subtitles = DiskSweep.sweepSubtitleCache(applicationContext)
            val avatars = DiskSweep.sweepPendingAvatars(applicationContext)
            // A staged update APK for a build that is already installed.
            val stagedApk = AppUpdater.clearStaleStagedApk(applicationContext)
            Log.i(
                TAG,
                "maintenance done: agedOut=${trim.agedOut} evicted=${trim.evicted} " +
                    "left=${trim.rows} row(s) / ${trim.bytes / 1_048_576} MB, " +
                    "reclaimed=$reclaimed subtitles=$subtitles avatars=$avatars " +
                    "stagedApk=$stagedApk"
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing here is user-visible and nothing is lost by stopping: the
            // budget is still enforced on the write path, and the next run
            // retries the reclaim.
            Log.w(TAG, "maintenance failed: ${e.message}", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "CACHE_MAINT_WORKER"

        /** Unique name of the recurring pass. */
        const val WORK_NAME = "cache_maintenance"

        /** Unique name of the one-off pass enqueued on launch. */
        private const val WORK_NAME_ONCE = "cache_maintenance_once"

        /**
         * How often the reclaim is retried.
         *
         * Daily is well under the rate at which the file can grow (the table's
         * own budget is enforced on every write), so this is about picking up
         * slack, not about keeping up. WorkManager's periodic minimum is 15
         * minutes; anything near that would just wake the device for a
         * freelist that has not moved.
         */
        private const val INTERVAL_HOURS = 24L

        /**
         * Schedules the recurring pass and enqueues one run right away.
         *
         * The immediate run is what reclaims space an EARLIER build already
         * used: those installs are carrying a database that was allowed to grow
         * without a ceiling, and the first periodic run could otherwise be a
         * day away on a TV that gets left on. `KEEP` on both, so a relaunch
         * cannot pile up duplicate work.
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                WORK_NAME_ONCE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CacheMaintenanceWorker>()
                    .setConstraints(Constraints.NONE)
                    .build()
            )
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CacheMaintenanceWorker>(
                    INTERVAL_HOURS,
                    TimeUnit.HOURS
                )
                    .setConstraints(Constraints.NONE)
                    .build()
            )
        }
    }
}
