package com.kennyb1201.kbstream.data.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import com.kennyb1201.kbstream.MainActivity
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Publishes KBStream's in-progress titles to the Android TV "Watch Next"
 * channel, which launchers surface as "Continue watching". The system-side
 * table is writable by any app — no manifest provider, permission, or
 * Google approval needed.
 *
 * One program per parent show/movie (the most recently active one) is kept,
 * mirroring the in-app Continue Watching rail. Each program is keyed by the
 * watch-history row id through COLUMN_INTERNAL_PROVIDER_ID, and the mapping
 * of historyId -> launcher row id is cached in SharedPreferences so updates
 * and removals can target the exact row without re-querying the provider.
 *
 * The Watch Next table lives on the system side, so no manifest provider or
 * runtime permission is required. Every write is a no-op below API 26,
 * where the Watch Next table does not exist.
 */
object TvLauncherPublisher {

    private const val TAG = "TV_LAUNCHER"

    // Extras on the deep-link intent that launcher cards launch into
    // MainActivity.
    const val EXTRA_TYPE = "tv_watch_next_type"
    const val EXTRA_ID = "tv_watch_next_id"

    // Don't clutter the launcher rail with barely-started playback.
    private const val MIN_PUBLISH_POSITION_MS = 30_000L

    private const val PREFS = "kbstream_tv_launcher"
    private const val KEY_ROWS = "watch_next_rows_json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    /**
     * Full reconciliation: reads every resume row and rebuilds the launcher
     * Watch Next rows to match (one program per parent, most recently active
     * one). Cheap (a handful of rows) and self-healing — finished or removed
     * titles disappear, and stale rows from previous sessions are dropped.
     */
    fun sync(
        context: Context,
        entries: List<WatchHistoryEntity>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        scope.launch {
            mutex.withLock {
                runCatching {
                    syncLocked(context, entries)
                }.onFailure { e ->
                    Log.e(TAG, "Watch Next sync failed: ${e.message}")
                }
            }
        }
    }

    /** Removes every Watch Next row this app published. */
    fun clear(context: Context) {
        sync(context, emptyList())
    }

    private fun syncLocked(
        context: Context,
        entries: List<WatchHistoryEntity>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val appContext = context.applicationContext

        // One program per parent — the most recently touched in-progress row,
        // matching the in-app Continue Watching rail query.
        val candidates = entries
            .filter { !it.isCompleted && it.positionMs >= MIN_PUBLISH_POSITION_MS }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.parentId }

        // 1. Drop every row we previously created.
        val old = parseRows(prefs.getString(KEY_ROWS, null))
        old.values.forEach { rowId ->
            runCatching {
                resolver.delete(
                    Uri.withAppendedPath(
                        TvContract.WatchNextPrograms.CONTENT_URI,
                        rowId.toString()
                    ),
                    null,
                    null
                )
            }
        }

        // 2. Re-insert the current set and remember the new row ids.
        val next = JSONObject()
        candidates.forEach { entry ->
            runCatching {
                val uri = resolver.insert(
                    TvContract.WatchNextPrograms.CONTENT_URI,
                    entry.toWatchNextValues(appContext)
                )
                if (uri != null) {
                    next.put(entry.id, uri.lastPathSegment)
                }
            }.onFailure { e ->
                Log.e(TAG, "Watch Next insert failed for ${entry.id}: ${e.message}")
            }
        }

        prefs.edit()
            .putString(KEY_ROWS, next.toString())
            .apply()
    }

    private fun WatchHistoryEntity.toWatchNextValues(
        context: Context
    ): ContentValues {
        val isSeries = type == "series" || type == "show" || type == "tv"

        return ContentValues().apply {
            put(
                TvContract.WatchNextPrograms.COLUMN_TYPE,
                if (season != null && episode != null) {
                    TvContract.WatchNextPrograms.TYPE_TV_EPISODE
                } else if (isSeries) {
                    TvContract.WatchNextPrograms.TYPE_TV_SERIES
                } else {
                    TvContract.WatchNextPrograms.TYPE_MOVIE
                }
            )
            put(
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            )
            put(
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                updatedAt
            )
            put(
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                episodeTitle?.takeIf { it.isNotBlank() } ?: name
            )
            put(
                TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
                buildString {
                    append(name)
                    if (season != null && episode != null) {
                        append(" · S")
                        append(season)
                        append("E")
                        append(episode)
                    }
                }
            )
            poster?.takeIf { it.isNotBlank() }?.let {
                put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it)
            }
            put(
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                deepLinkIntent(context).toUri(Intent.URI_INTENT_SCHEME)
            )
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, id)
            season?.let {
                put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, it.toString())
            }
            episode?.let {
                put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString())
            }
        }
    }

    private fun WatchHistoryEntity.deepLinkIntent(
        context: Context
    ): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_TYPE, type)
        putExtra(EXTRA_ID, parentId)
    }

    private fun parseRows(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.getString(key)
            }
            result
        }.getOrDefault(emptyMap())
    }
}