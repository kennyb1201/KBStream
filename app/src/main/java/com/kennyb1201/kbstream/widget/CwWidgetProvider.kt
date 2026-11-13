package com.kennyb1201.kbstream.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.widget.CwWidgetRules
import com.kennyb1201.kbstream.data.widget.CwWidgetStore
import com.kennyb1201.kbstream.data.widget.CwWidgetUpdater

/**
 * The Continue Watching home-screen widget (spec 2026-11-13, F1/F2).
 *
 * Classic `AppWidgetProvider` + `RemoteViews` rather than Glance, and the commit
 * message records why: the project has no Glance dependency, and this widget is a
 * header plus a collection - `ListView` + `RemoteViewsService` is the supported
 * shape for exactly that, including per-row fill-in intents and a resize that
 * simply shows more rows. Adding a Compose-for-widgets stack (and its own
 * runtime) to draw eight rows would have been a larger surface than the feature.
 *
 * The provider draws the shell (title, freshness line, refresh affordance, the
 * list's adapter/template) and leaves the rows to [CwWidgetService]. Refreshes are
 * pushed by the app when Continue Watching changes ([CwWidgetUpdater]) and by the
 * header's own button; `updatePeriodMillis` is 0, so the launcher never wakes the
 * app on its own schedule.
 */
class CwWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            runCatching { render(context, appWidgetManager, id) }
                .onFailure { Log.w(TAG, "could not render widget: ${it.javaClass.simpleName}") }
        }
        // Only placed widgets need the half-hourly round; adding the first one
        // arms it, and onDisabled (the last one removed) disarms it.
        runCatching { CwWidgetUpdater.syncPeriodicWork(context, hasWidget = true) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == CwWidgetTap.ACTION_REFRESH) {
            // The header's own button. There is nothing to fetch: re-reading the
            // snapshot is the refresh, and the launcher's re-bind shows the
            // freshness line as of now.
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                if (manager != null) {
                    val ids = manager.getAppWidgetIds(
                        android.content.ComponentName(context, CwWidgetProvider::class.java)
                    )
                    ids.forEach { id -> runCatching { render(context, manager, id) } }
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        runCatching { CwWidgetUpdater.syncPeriodicWork(context) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        runCatching { CwWidgetUpdater.syncPeriodicWork(context, hasWidget = false) }
    }

    /**
     * One instance's RemoteViews.
     *
     * Re-read here as well as in the factory so the header (the freshness line and
     * the fail-soft copy) is right the moment the shell is drawn, before the
     * launcher's first bind of the list.
     */
    private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val state = runCatching { CwWidgetStore.read(context) }.getOrNull()
        val views = RemoteViews(context.packageName, R.layout.cw_widget)

        views.setTextViewText(
            R.id.cw_widget_freshness,
            CwWidgetRules.freshnessLabel(state?.snapshot?.writtenAtMs ?: 0L)
        )
        views.setOnClickPendingIntent(
            R.id.cw_widget_header,
            CwWidgetTap.openAppPendingIntent(context)
        )
        views.setOnClickPendingIntent(
            R.id.cw_widget_refresh,
            CwWidgetTap.refreshPendingIntent(context)
        )

        val adapter = Intent(context, CwWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Distinct data per instance: two placed widgets are two adapters.
            data = android.net.Uri.parse("kbstream://widget/cw/adapter/$appWidgetId")
        }
        views.setRemoteAdapter(R.id.cw_widget_list, adapter)
        views.setPendingIntentTemplate(
            R.id.cw_widget_list,
            CwWidgetTap.rowTemplatePendingIntent(context)
        )

        manager.updateAppWidget(appWidgetId, views)
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.cw_widget_list)
    }

    private companion object {
        const val TAG = "CwWidgetProvider"
    }
}
