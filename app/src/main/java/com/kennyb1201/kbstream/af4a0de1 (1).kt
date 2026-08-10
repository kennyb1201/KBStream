package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.simkl.SimklRepository

class SimklSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val simklRepository = SimklRepository(applicationContext)

        if (!simklRepository.isConfigured()) {
            Log.e(TAG, "Simkl is not configured; skipping sync")
            return Result.success()
        }

        if (!simklRepository.hasToken()) {
            Log.e(TAG, "No Simkl token; skipping sync")
            return Result.success()
        }

        return try {
            val refreshResult = simklRepository.refreshWatchedActivity()

            Log.e(
                TAG,
                "Periodic sync checked watched activity; attempted=${refreshResult.attempted} changed=${refreshResult.changed} latest=${refreshResult.latestActivity} error=${refreshResult.errorMessage}"
            )

            if (!refreshResult.attempted) {
                return Result.success()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Periodic Simkl sync failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SIMKL_SYNC_WORKER"
    }
}
