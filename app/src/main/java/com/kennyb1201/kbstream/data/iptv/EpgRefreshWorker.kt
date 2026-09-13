package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // WorkManager fires outside any profile context, so resolve the
        // ACTIVE profile's scoped store at run time - otherwise the worker
        // would always read the legacy/global config even after profiles
        // exist (and could import the wrong account's EPG into a scoped DB).
        val prefs = applicationContext.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                applicationContext, "iptv_prefs"
            ),
            Context.MODE_PRIVATE
        )

        val epgUrl = prefs
            .getString("epg_url", "")
            .orEmpty()
            .trim()

        if (epgUrl.isBlank()) {
            return Result.success()
        }

        return try {
            IptvRepository(applicationContext).importGuide(epgUrl)

            prefs.edit()
                .putLong("epg_updated_at", System.currentTimeMillis())
                .apply()

            Result.success()
        } catch (error: Exception) {
            Result.retry()
        }
    }
}
