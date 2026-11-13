package com.kennyb1201.kbstream.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.widget.CwWidgetRow
import com.kennyb1201.kbstream.data.widget.CwWidgetState
import com.kennyb1201.kbstream.data.widget.CwWidgetStore
import kotlin.math.roundToInt

/**
 * Fills the Continue Watching widget's list (spec 2026-11-13, F1/F3).
 *
 * The factory reads the snapshot the app wrote - it never opens the history
 * database - and draws it. The rows ARE the Home rail's rows: same order, same
 * dismissals, same profile, because they are the app's own published list, run
 * through the same read-site filter ([com.kennyb1201.kbstream.data.widget.CwWidgetPublisher]).
 *
 * Posters come through the app's own Coil pipeline, at a widget-sized request, so
 * a rail the viewer has already browsed costs a memory/disk cache hit and nothing
 * is downloaded into a second cache. A poster that cannot be loaded leaves the
 * placeholder behind - the row still draws, because a missing image is not a
 * reason to lose a resume point.
 */
class CwWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        CwWidgetFactory(applicationContext)
}

internal class CwWidgetFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var state: CwWidgetState = CwWidgetState(snapshot = null, stale = false, profileId = null)

    private val posters = HashMap<String, Bitmap?>()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        state = runCatching { CwWidgetStore.read(context) }
            .getOrDefault(CwWidgetState(snapshot = null, stale = true, profileId = null))
        loadPosters()
    }

    override fun onDestroy() {
        posters.clear()
    }

    /** One row when there is nothing to draw, so the empty state is still a filled list. */
    override fun getCount(): Int = if (state.rows.isEmpty()) 1 else state.rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val rows = state.rows
        if (rows.isEmpty()) return emptyView()
        val row = rows.getOrNull(position) ?: return emptyView()
        return rowView(row, position)
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long =
        state.rows.getOrNull(position)?.let { row ->
            (row.parentId + "#" + (row.season ?: -1) + ":" + (row.episode ?: -1)).hashCode().toLong()
        } ?: -1L

    override fun hasStableIds(): Boolean = true

    private fun rowView(row: CwWidgetRow, position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.cw_widget_row)
        views.setTextViewText(R.id.cw_widget_row_title, row.showTitle)
        views.setTextViewText(R.id.cw_widget_row_subtitle, row.subtitle)
        views.setViewVisibility(
            R.id.cw_widget_row_subtitle,
            if (row.subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        )
        views.setProgressBar(
            R.id.cw_widget_row_progress,
            PROGRESS_MAX,
            (row.progress * PROGRESS_MAX).roundToInt().coerceIn(0, PROGRESS_MAX),
            false
        )
        views.setTextViewText(R.id.cw_widget_row_time_left, row.timeLeft)
        views.setViewVisibility(
            R.id.cw_widget_row_time_left,
            if (row.timeLeft.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        )

        row.posterUrl?.let { url ->
            posters[url]?.let { bitmap ->
                views.setImageViewBitmap(R.id.cw_widget_row_poster, bitmap)
            }
        }

        // The tap: the template is set on the ListView by the provider, this is
        // the per-row half (see CwWidgetTap.rowFillIn).
        views.setOnClickFillInIntent(
            R.id.cw_widget_row_root,
            CwWidgetTap.rowFillIn(row, position)
        )
        return views
    }

    /**
     * The empty / fail-soft state.
     *
     * Its hint says what is true: nothing in progress, or no snapshot to draw yet.
     * A stale read is named as such instead of being passed off as current - and
     * the tap opens the app either way.
     */
    private fun emptyView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.cw_widget_empty)
        val staleOrMissing = state.stale || state.snapshot == null
        views.setTextViewText(
            R.id.cw_widget_empty_title,
            if (staleOrMissing) {
                context.getString(R.string.cw_widget_empty_hint)
            } else {
                context.getString(R.string.cw_widget_empty)
            }
        )
        views.setTextViewText(
            R.id.cw_widget_empty_hint,
            if (staleOrMissing) {
                context.getString(R.string.cw_widget_empty)
            } else {
                context.getString(R.string.cw_widget_empty_hint)
            }
        )
        views.setOnClickFillInIntent(
            R.id.cw_widget_empty_root,
            CwWidgetTap.openAppFillIn()
        )
        return views
    }

    /**
     * Loads the row posters into memory for this dataset.
     *
     * Bounded on purpose: `onDataSetChanged` blocks the launcher's bind, so a slow
     * network is allowed to give up on the remaining posters (they draw as the
     * placeholder) rather than stall the widget past the system's patience.
     */
    private fun loadPosters() {
        val urls = state.rows.mapNotNull { it.posterUrl }.distinct()
            .filter { it !in posters }
        if (urls.isEmpty()) return

        val loader = runCatching { SingletonImageLoader.get(context) }.getOrNull() ?: return
        val deadline = System.currentTimeMillis() + POSTER_BUDGET_MS

        urls.forEach { url ->
            if (System.currentTimeMillis() > deadline) return
            val bitmap = runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(POSTER_WIDTH_PX, POSTER_HEIGHT_PX)
                    .allowHardware(false)
                    .build()
                when (val result = kotlinx.coroutines.runBlocking { loader.execute(request) }) {
                    is SuccessResult -> result.image.toBitmap()
                    else -> null
                }
            }.onFailure { Log.w(TAG, "poster failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            posters[url] = bitmap
        }
    }

    private companion object {
        const val TAG = "CwWidgetFactory"
        const val PROGRESS_MAX = 100
        const val POSTER_WIDTH_PX = 96
        const val POSTER_HEIGHT_PX = 144
        const val POSTER_BUDGET_MS = 4_000L
    }
}
