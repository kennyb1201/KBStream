package com.kennyb1201.kbstream.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.addon.AddonManager

/**
 * Periodic add-on manifest refresh. WorkManager fires outside any activity,
 * so the manager resolves the ACTIVE profile's addon store at run time
 * (AddonManager.prefs goes through ProfileStorage on every access).
 *
 * The pass is AWAITED. It used to be fire-and-forget - the worker called the
 * manager, returned [Result.success] immediately, and the fetch it had just
 * started ran on a scope the system was free to reclaim the moment the worker
 * returned. A `CoroutineWorker`'s contract is the opposite: the work is done
 * when `doWork` returns, and WorkManager is entitled to stop the process then,
 * so that shape usually fetched nothing at all while still reporting success -
 * the addons quietly stayed at their install-time manifests.
 *
 * Offline / transient-failure safe: the pass skips addons whose fetch fails,
 * unchanged manifests are not re-saved, and the throttle it shares with the
 * other triggers means a run the launch or foreground hook already did is a
 * cheap no-op. Only a pass that reached NO add-on host asks for a retry.
 */
class AddonManifestRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Same entry point as the launch / foreground triggers, so both
            // automatic paths share one interval and one timestamp instead of
            // fetching every manifest twice.
            val report =
                AddonManager.getInstance(applicationContext).refreshInstalledAddonsIfDue()

            when {
                // Not due: another trigger already refreshed inside the window.
                report == null -> {
                    Log.i(TAG, "manifest refresh skipped: not due")
                    Result.success()
                }

                // Reached no add-on at all - offline, or every host down. The
                // pass leaves the throttle stamp alone in that case, so a retry
                // is not competing with a window it just consumed.
                report.fetched == 0 && report.failed > 0 -> {
                    Log.w(TAG, "manifest refresh reached no addon (${report.failed} failed)")
                    Result.retry()
                }

                else -> {
                    Log.i(
                        TAG,
                        "manifest refresh done: fetched=${report.fetched} " +
                            "applied=${report.applied} failed=${report.failed}"
                    )
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "manifest refresh failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ADDON_REFRESH_WORKER"
    }
}
