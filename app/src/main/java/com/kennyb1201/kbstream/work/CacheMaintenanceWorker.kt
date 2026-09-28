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
import com.kennyb1201.kbstream.data.iptv.GuideStorage
import com.kennyb1201.kbstream.data.player.StreamDiskCache
import com.kennyb1201.kbstream.data.update.AppUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Every periodic disk cleanup in one place: bounds the TMDB JSON cache, hands
 * the database files their space back, sweeps the caches that grow by file
 * COUNT rather than by bytes, reclaims the player's read-ahead cache when its
 * budget has moved out from under it, and bounds the IPTV guides.
 *
 * The guides are the largest store the app owns — four of them (one per
 * profile, plus the legacy global file) accounted for ~980 MB of the 1.34 GB
 * measured on the field TV — and until now nothing maintained them at all. See
 * [com.kennyb1201.kbstream.data.iptv.GuideStorage].
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
            // The player's read-ahead cache, when an earlier budget left it
            // larger than the one its free space allows now. Its LRU evictor
            // only evicts while a stream is being written into it, so without
            // this an oversized cache would survive until a long playback.
            val streamCache = StreamDiskCache.sweepIfOversized(applicationContext)
            // The guides: whole files nothing can read again (the legacy global
            // one, and a profile's guide left idle past the sweep window),
            // then a prune plus the VACUUM that hands a shrunken guide's free
            // pages back. Same reason as the JSON cache above — a delete never
            // shrinks a SQLite file — but an order of magnitude larger.
            val guides = GuideStorage.sweep(applicationContext)
            // A staged update APK for a build that is already installed.
            val stagedApk = AppUpdater.clearStaleStagedApk(applicationContext)
            Log.i(
                TAG,
                "maintenance done: agedOut=${trim.agedOut} evicted=${trim.evicted} " +
                    "left=${trim.rows} row(s) / ${trim.bytes / 1_048_576} MB, " +
                    "reclaimed=$reclaimed subtitles=$subtitles avatars=$avatars " +
                    "streamCache=${streamCache / 1_048_576}MB stagedApk=$stagedApk"
            )
            Log.i(
                TAG,
                "guides: deleted=${guides.deleted} " +
                    "(${guides.deletedBytes / 1_048_576} MB) " +
                    "pruned=${guides.prunedRows} row(s) " +
                    "clipped=${guides.trimmedDescriptions} description(s) " +
                    "vacuumed=${guides.vacuumed} " +
                    "skipped=${guides.busy} left=${guides.beforeBytes / 1_048_576} -> " +
                    "${guides.afterBytes / 1_048_576} MB"
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

        /**
         * Unique name of the one-off pass enqueued on launch.
         *
         * The generation suffix is deliberate. `KEEP` on a unique name means a
         * name is enqueued exactly once per install, so a pass that GAINS work
         * (this one now reclaims the guides, which the earlier generation never
         * touched) would otherwise not run until the next daily tick — up to a
         * day after the update that added it, on a device whose whole point was
         * that it had already grown too large.
         */
        // ...and once more for the description clip, which is another pass
        // these installs have never run: see the generation note above.
        private const val WORK_NAME_ONCE = "cache_maintenance_once_3"

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
