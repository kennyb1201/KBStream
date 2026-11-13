package com.kennyb1201.kbstream.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kennyb1201.kbstream.MainActivity
import com.kennyb1201.kbstream.data.widget.CwWidgetRow

/**
 * The widget's taps (spec 2026-11-13, F1).
 *
 * A row tap hands [MainActivity] the same handful of facts a Continue Watching
 * card press hands it - type, parent id, stream id, season/episode, playhead -
 * and the activity's ONE launch-intent router turns them into the same
 * `Screen.Detail(pendingTarget = ...)` a card press produces. The widget adds no
 * navigation and no resume logic: it carries the numbers the history row already
 * held, and the app's existing player entry does the rest (one tap, straight into
 * playback).
 *
 * Extras rather than a `kbstream://title` link on purpose: that host's filter is
 * on [com.kennyb1201.kbstream.ui.search.VoiceSearchActivity] (the suggestion
 * row's route), which opens Detail and does NOT resume. Reusing it would have
 * made the widget's first tap land on Detail - exactly what the spec forbids.
 *
 * Row taps go through a PendingIntent TEMPLATE on the ListView plus a per-row
 * fill-in intent, which is the only shape a collection's RemoteViews supports:
 * the launcher merges the row's extras into the template at send time.
 */
internal object CwWidgetTap {

    /** Header refresh: the provider re-reads the snapshot and repaints. */
    const val ACTION_REFRESH = "com.kennyb1201.kbstream.widget.action.CW_REFRESH"

    const val EXTRA_PARENT_TYPE = "cw_widget_parent_type"
    const val EXTRA_PARENT_ID = "cw_widget_parent_id"
    const val EXTRA_STREAM_ID = "cw_widget_stream_id"
    const val EXTRA_TITLE = "cw_widget_title"
    const val EXTRA_EPISODE_TITLE = "cw_widget_episode_title"
    const val EXTRA_SEASON = "cw_widget_season"
    const val EXTRA_EPISODE = "cw_widget_episode"
    const val EXTRA_POSITION_MS = "cw_widget_position_ms"
    const val EXTRA_POSTER = "cw_widget_poster"

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * The per-row fill-in: the facts needed to resume THIS row.
     *
     * `data` is unique per row because the merged intent is what the activity
     * reads, and two rows sharing identity would be indistinguishable in logs and
     * in the router's own dedupe.
     */
    fun rowFillIn(row: CwWidgetRow, index: Int): Intent =
        Intent().apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(
                "kbstream://widget/cw/" + index + "/" +
                    Uri.encode(row.parentType.ifBlank { "series" }) + "/" +
                    Uri.encode(row.parentId)
            )
            putExtra(EXTRA_PARENT_TYPE, row.parentType.ifBlank { "series" })
            putExtra(EXTRA_PARENT_ID, row.parentId)
            row.streamId?.let { putExtra(EXTRA_STREAM_ID, it) }
            putExtra(EXTRA_TITLE, row.showTitle)
            row.episodeTitle?.let { putExtra(EXTRA_EPISODE_TITLE, it) }
            row.season?.let { putExtra(EXTRA_SEASON, it) }
            row.episode?.let { putExtra(EXTRA_EPISODE, it) }
            putExtra(EXTRA_POSITION_MS, row.positionMs)
            row.posterUrl?.let { putExtra(EXTRA_POSTER, it) }
        }

    /**
     * The ListView's template: opens the app, and the launcher fills in which row.
     *
     * Null extras here is deliberate - the fill-in carries every value, so the
     * template cannot leak one row's playhead into another.
     */
    fun rowTemplatePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("kbstream://widget/cw/row")
        }
        return PendingIntent.getActivity(context, REQUEST_ROW, intent, FLAGS)
    }

    /** The empty state's fill-in: open the app, nothing else. */
    fun openAppFillIn(): Intent = Intent().apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("kbstream://widget/cw/open")
    }

    /** Header / "view all": open the app at Home, where Continue Watching lives. */
    fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("kbstream://widget/cw/open")
        }
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, FLAGS)
    }

    /** Header refresh button: a broadcast the provider turns into a repaint. */
    fun refreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, CwWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            data = Uri.parse("kbstream://widget/cw/refresh")
        }
        return PendingIntent.getBroadcast(context, REQUEST_REFRESH, intent, FLAGS)
    }

    private const val REQUEST_ROW = 0x1F01
    private const val REQUEST_OPEN = 0x1F02
    private const val REQUEST_REFRESH = 0x1F03
}
