package com.kennyb1201.kbstream.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.widget.CwWidgetPublisher
import com.kennyb1201.kbstream.data.widget.CwWidgetUpdater
import kotlinx.coroutines.CancellationException

/**
 * The Continue Watching widget's refresh (spec 2026-11-13, F2; rebuilds since
 * spec 2026-11-16, F1/F3).
 *
 * One worker, two schedules: the half-hourly round
 * ([CwWidgetUpdater.syncPeriodicWork]) and the header button's one-time tap
 * ([CwWidgetUpdater.rebuildNow]). Both do the same thing, which is the point - a
 * tap and a round must never be two different refreshes.
 *
 * It now REBUILDS: [CwWidgetPublisher.rebuild] re-derives the list from this
 * phone's own watch history (local only - no network, no tracker), writes it and
 * repaints. It used to only repaint the snapshot the app had written, which was
 * the bug Kenny reported on 2026-11-16: a phone that had not opened the app for
 * twelve hours had a widget drawing twelve-hour-old rows, and neither the button
 * nor the round could change them.
 *
 * Armed only while a widget is placed ([CwWidgetUpdater.syncPeriodicWork]);
 * removing the last instance cancels both the round and any queued tap.
 */
class CwWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val context = applicationContext
        if (!CwWidgetUpdater.hasInstances(context)) {
            // The last widget went away while this round was in flight.
            CwWidgetUpdater.syncPeriodicWork(context, hasWidget = false)
            Result.success()
        } else {
            CwWidgetPublisher.rebuild(context)
            Result.success()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A rebuild that failed already kept the last good snapshot; that is not
        // worth a retry storm. The next round and the next tap cover it.
        Result.success()
    }
}
