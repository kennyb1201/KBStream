package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository

class SimklSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val watchedStatusRepository = WatchedStatusRepository(applicationContext)
        val watchedStatusDao = WatchHistoryDatabase
            .getInstance(applicationContext)
            .watchedStatusDao()

        return try {
            val refreshTargets = watchedStatusDao.getRefreshTargets()
            val items = refreshTargets.map { it.imdbId to it.mediaType }

            if (items.isEmpty()) {
                Log.e(TAG, "No watched refresh targets found; skipping sync")
                return Result.success()
            }

            val refreshResult = watchedStatusRepository.refreshRemoteWatchStateIfNeeded(items)

            Log.e(
                TAG,
                "Periodic sync completed; attempted=${refreshResult.attempted} changed=${refreshResult.changed} refreshedCount=${refreshResult.refreshedCount} success=${refreshResult.success} error=${refreshResult.errorMessage}"
            )

            when {
                !refreshResult.attempted -> Result.success()
                refreshResult.success -> Result.success()
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Periodic Simkl sync failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SIMKL_SYNC_WORKER"
    }
}
