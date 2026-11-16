package com.kennyb1201.kbstream.data.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kennyb1201.kbstream.widget.CwWidgetProvider
import com.kennyb1201.kbstream.work.CwWidgetRefreshWorker
import java.util.concurrent.TimeUnit

/**
 * Tells the Continue Watching widget that its list changed (spec 2026-11-13,
 * F2), and owns its half-hourly round and the header button's one-time rebuild.
 *
 * The app is the only thing that knows Continue Watching changed, so it says so -
 * after a playback session, a mark, a dismissal, a profile switch or a snapshot
 * write - and the launcher asks its factory for the rows again. No polling: a
 * refresh from the app is a notification, not a re-read of the database.
 *
 * The two SCHEDULED paths are different since spec 2026-11-16: the half-hourly
 * round and the button both ask [CwWidgetPublisher.rebuild] to re-derive the list
 * from local history before they repaint, because the app's own publish only
 * happens while Home is alive and a widget on a phone that was not opened for
 * hours went stale - and stayed stale, since re-reading an unchanged snapshot
 * changes nothing on screen.
 *
 * A device with no widget placed is never woken: [refresh] returns immediately
 * and the periodic round is not scheduled at all, so adding this feature costs a
 * phone that never used it exactly nothing.
 */
internal object CwWidgetUpdater {

    const val PERIODIC_WORK_NAME = "cw_widget_refresh"

    /** The header's button: one rebuild, at most one in flight (spec 2026-11-16, F2). */
    const val REBUILD_WORK_NAME = "cw_widget_rebuild"

    /** Thirty minutes: the round rebuilds from local history and repaints. */
    private const val INTERVAL_MINUTES = 30L

    /** True while at least one instance of the widget is on a launcher. */
    fun hasInstances(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val ids = manager.getAppWidgetIds(
            ComponentName(context.applicationContext, CwWidgetProvider::class.java)
        )
        return ids != null && ids.isNotEmpty()
    }

    /**
     * Whether at least one instance exists, and - when it does - the ids to tell.
     *
     * Two widget shapes are supported by that one call: a launcher that honours
     * `notifyAppWidgetViewDataChanged` re-runs the factory, and a stale header
     * repaints through the same ids.
     */
    private fun ids(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return manager.getAppWidgetIds(
            ComponentName(context.applicationContext, CwWidgetProvider::class.java)
        ) ?: IntArray(0)
    }

    /**
     * Re-binds every placed widget: the shell's header (its freshness line) AND
     * the list.
     *
     * An explicit `APPWIDGET_UPDATE` broadcast rather than only
     * `notifyAppWidgetViewDataChanged`: the latter re-runs the collection's
     * factory and nothing else, so a header written when the snapshot was hours
     * old would keep saying "Updated 5h ago" after a fresh write. The provider's
     * `onUpdate` calls `updateAppWidget` (the header) and then notifies the data
     * set (the rows), so one broadcast reaches both. Cheap, and safe with none
     * placed.
     */
    fun refresh(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        runCatching {
            val app = context.applicationContext
            app.sendBroadcast(
                Intent(app, CwWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
            )
        }
    }

    /**
     * The header's refresh button: one on-demand rebuild, run off the UI thread
     * (spec 2026-11-16, F2).
     *
     * WorkManager rather than a thread inside `onReceive`, which runs on the main
     * thread and is a broadcast receiver the launcher is waiting on. `KEEP` is the
     * "a tap while a rebuild is in flight is a no-op" rule: the second press finds
     * the work already there and queues nothing, instead of two rebuilds racing to
     * write the same file. No network constraint - the rebuild reads local history.
     *
     * The caller still repaints first, so the tap is acknowledged even if the work
     * system is slow to start it.
     */
    fun rebuildNow(context: Context) {
        if (!hasInstances(context)) return
        runCatching {
            val request = OneTimeWorkRequestBuilder<CwWidgetRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                REBUILD_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    /**
     * Arms the 30-minute round while a widget exists, and disarms it when the
     * last one is removed.
     *
     * `UPDATE` rather than `KEEP`: re-arming from `onUpdate` must not stack
     * rounds, and the interval is a constant, so there is nothing to preserve.
     * No network constraint - the snapshot is local, and requiring connectivity
     * would skip the round on a phone in airplane mode for no reason.
     */
    fun syncPeriodicWork(context: Context, hasWidget: Boolean = hasInstances(context)) {
        val workManager = WorkManager.getInstance(context)
        if (!hasWidget) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            // A tap that landed just before the last instance went away has
            // nothing left to rebuild for.
            workManager.cancelUniqueWork(REBUILD_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<CwWidgetRefreshWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
