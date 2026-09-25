package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues the background guide refresh.
 *
 * This used to be called from exactly one place - the cloud-sync prefs
 * applier, and only when a synced playlist URL had changed. A guide
 * configured in the app itself therefore never got an [EpgRefreshWorker] at
 * all: the only refreshes were the manual one and the staleness check that
 * runs while the Guide screen is open. With the app closed (or on a TV box
 * that is left on the launcher) the guide simply aged, and because an import
 * only stores a window of programmes, it eventually aged into "No program
 * data" rather than merely being old. [MainApplication] now schedules it on
 * every launch.
 *
 * `UPDATE` rather than `KEEP` on purpose: KEEP would leave an existing
 * request's old interval in place forever, so the cadence below could never
 * change on an install that already had one (and it is the cadence that keeps
 * the stored window ahead of the clock).
 */
object EpgRefreshScheduler {

    private const val WORK_NAME = "iptv_epg_refresh"

    /**
     * How often the guide is re-imported in the background. WorkManager's
     * minimum for periodic work is 15 minutes and a large XMLTV import is a
     * multi-minute download plus parse, so this is a freshness/cost trade
     * rather than a fine-grained poll. Six hours keeps the stored window
     * (see [XmltvImporter]'s future window) comfortably ahead of the clock
     * even when a run is delayed by Doze or by the write gate deferring it
     * past playback.
     */
    private const val REFRESH_INTERVAL_HOURS = 6L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
            REFRESH_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
