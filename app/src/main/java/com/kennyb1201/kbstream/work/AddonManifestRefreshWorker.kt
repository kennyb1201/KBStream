package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.addon.AddonManager

/**
 * Daily add-on manifest refresh. WorkManager fires outside any activity,
 * so the manager resolves the ACTIVE profile's addon store at run time
 * (AddonManager.prefs goes through ProfileStorage on every access).
 *
 * Offline / transient-failure safe: refreshInstalledAddons skips addons
 * whose fetch fails, and unchanged manifests are not re-saved, so a run
 * with no network is a clean no-op rather than an error loop.
 */
class AddonManifestRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            AddonManager.getInstance(applicationContext).refreshInstalledAddons()
            Log.i(TAG, "manifest refresh dispatched")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "manifest refresh failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ADDON_REFRESH_WORKER"
    }
}
