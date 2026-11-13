package com.kennyb1201.kbstream.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.widget.CwWidgetUpdater
import kotlinx.coroutines.CancellationException

/**
 * The Continue Watching widget's half-hourly repaint (spec 2026-11-13, F2).
 *
 * It does NOT read the history database or any tracker: the widget draws the
 * snapshot the app wrote, and this round only asks the launcher to re-run the
 * factory so the "updated Nm ago" line stays honest and a long-idle widget
 * notices a snapshot the app wrote while the launcher's cache was cold.
 *
 * Armed only while a widget is placed ([CwWidgetUpdater.syncPeriodicWork]);
 * removing the last instance cancels it.
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
            CwWidgetUpdater.refresh(context)
            Result.success()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A repaint that failed is not worth a retry storm; the next round and
        // the next in-app change both cover it.
        Result.success()
    }
}
