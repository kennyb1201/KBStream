package com.kennyb1201.kbstream.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of everything that makes a reinstall painful: player
 * settings, installed add-ons, watched overrides, watch history, and the
 * watched-status cache. Simkl credentials are intentionally NOT included —
 * the user reconnects the account after restoring.
 *
 * Export/import operate on SAF URIs (storage-access-framework pickers) so
 * the user chooses where the file lives — USB drive, cloud, downloads.
 */
object BackupManager {

    private const val APP_TAG = "kbstream"
    private const val BACKUP_VERSION = 1

    private const val PLAYER_PREFS = "kbstream_player_prefs"
    private const val ADDON_PREFS = "kbstream_addons"
    private const val WATCHED_OVERRIDES_PREFS = "kbstream_watched_overrides"

    /**
     * Backups are per-profile artifacts: every prefs path resolves through
     * ProfileStorage, so with an active profile the export contains that
     * profile's data and the import writes into that profile's stores. With
     * no profiles these resolve to the legacy/global names unchanged.
     */
    private fun scopedPrefs(context: Context, baseName: String) =
        context.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(context, baseName),
            Context.MODE_PRIVATE
        )

    /** Serializes current state and writes it to [uri]. Returns a summary. */
    suspend fun export(context: Context, uri: Uri): String {
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val history = db.watchHistoryDao().getAll()
        val watched = db.watchedStatusDao().getAll()

        val json = JSONObject().apply {
            put("app", APP_TAG)
            put("version", BACKUP_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("prefs", JSONObject().apply {
                put("player", JSONObject().apply {
                    scopedPrefs(context, PLAYER_PREFS)
                        .all.forEach { (key, value) ->
                            put(key, value)
                        }
                })
                put("addons", scopedPrefs(context, ADDON_PREFS)
                    .getString("installed_addons_json", null))
                put("watchedOverrides", JSONArray().apply {
                    scopedPrefs(context, WATCHED_OVERRIDES_PREFS)
                        .getStringSet("watched_overrides", emptySet())
                        .orEmpty()
                        .forEach { put(it) }
                })
            })
            put("watchHistory", JSONArray().apply {
                history.forEach { put(it.toJson()) }
            })
            put("watchedStatus", JSONArray().apply {
                watched.forEach { put(it.toJson()) }
            })
        }

        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val stream = context.contentResolver.openOutputStream(uri)
            ?: error("Could not open the chosen file for writing")
        stream.use { it.write(bytes) }

        return "Backup saved — " +
            "${history.size} title${if (history.size == 1) "" else "s"}, " +
            "${watched.size} watched marker${if (watched.size == 1) "" else "s"}"
    }

    /** Reads [uri], validates it, and replaces local state with its contents. */
    suspend fun import(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open the chosen file")
        val text = stream.bufferedReader().use { it.readText() }

        val json = runCatching { JSONObject(text) }
            .getOrElse { error("Not a valid backup file") }

        if (json.optString("app") != APP_TAG) {
            error("This file is not a KBStream backup")
        }

        val history = json.optJSONArray("watchHistory")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.toWatchHistoryEntity()
                }
            }
            .orEmpty()

        val watched = json.optJSONArray("watchedStatus")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.toWatchedStatusEntity()
                }
            }
            .orEmpty()

        val prefs = json.optJSONObject("prefs")
        restorePrefsMap(context, PLAYER_PREFS, prefs?.optJSONObject("player"))
        restoreAddons(context, prefs?.optString("addons"))
        restoreWatchedOverrides(context, prefs?.optJSONArray("watchedOverrides"))

        val db = WatchHistoryDatabase.getInstanceScoped(context)
        db.withTransaction {
            db.watchHistoryDao().clearAll()
            db.watchedStatusDao().clearAll()
            history.forEach { db.watchHistoryDao().upsert(it) }
            watched.chunked(150).forEach { chunk ->
                db.watchedStatusDao().upsertAll(chunk)
            }
        }

        // Drop in-memory watched snapshots so restored markers appear
        // immediately, and rebuild the TV launcher rail from restored data.
        WatchedStatusRepository.invalidateAllCaches()
        TvLauncherPublisher.sync(context, history)

        return "Backup restored — " +
            "${history.size} title${if (history.size == 1) "" else "s"}, " +
            "${watched.size} watched marker${if (watched.size == 1) "" else "s"}"
    }

    private fun restorePrefsMap(
        context: Context,
        prefsName: String,
        json: JSONObject?
    ) {
        if (json == null) return
        val editor = scopedPrefs(context, prefsName).edit()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> Unit
            }
        }
        editor.apply()
    }

    private fun restoreAddons(context: Context, addonsJson: String?) {
        scopedPrefs(context, ADDON_PREFS)
            .edit()
            .putString("installed_addons_json", addonsJson)
            .apply()
    }

    private fun restoreWatchedOverrides(
        context: Context,
        arr: JSONArray?
    ) {
        val set = mutableSetOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
            }
        }
        scopedPrefs(context, WATCHED_OVERRIDES_PREFS)
            .edit()
            .putStringSet("watched_overrides", set)
            .apply()
    }

    // ── Serialization ──────────────────────────────────────────────

    private fun WatchHistoryEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("parentId", parentId)
        put("type", type)
        put("name", name)
        put("episodeTitle", episodeTitle)
        put("overview", overview)
        put("clearLogo", clearLogo)
        put("backdropUrl", backdropUrl)
        put("totalEpisodesInSeason", totalEpisodesInSeason)
        put("poster", poster)
        put("streamUrl", streamUrl)
        put("season", season)
        put("episode", episode)
        put("episodeStreamId", episodeStreamId)
        put("positionMs", positionMs)
        put("durationMs", durationMs)
        put("updatedAt", updatedAt)
        put("isCompleted", isCompleted)
        put("completedAt", completedAt)
    }

    private fun JSONObject.toWatchHistoryEntity(): WatchHistoryEntity =
        WatchHistoryEntity(
            id = optString("id"),
            parentId = optString("parentId"),
            type = optString("type"),
            name = optString("name"),
            episodeTitle = optNullableString("episodeTitle"),
            overview = optNullableString("overview"),
            clearLogo = optNullableString("clearLogo"),
            backdropUrl = optNullableString("backdropUrl"),
            totalEpisodesInSeason = optNullableInt("totalEpisodesInSeason"),
            poster = optNullableString("poster"),
            streamUrl = optNullableString("streamUrl"),
            season = optNullableInt("season"),
            episode = optNullableInt("episode"),
            episodeStreamId = optNullableString("episodeStreamId"),
            positionMs = optLong("positionMs"),
            durationMs = optLong("durationMs"),
            updatedAt = optLong("updatedAt"),
            isCompleted = optBoolean("isCompleted", false),
            completedAt = optNullableLong("completedAt")
        )

    private fun WatchedStatusEntity.toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("imdbId", imdbId)
        put("mediaType", mediaType)
        put("isWatched", isWatched)
        // The eye flag rides along, or a restore would repaint every
        // started-but-unfinished show as un-watched until it re-resolves.
        put("isPartiallyWatched", isPartiallyWatched)
        put("updatedAt", updatedAt)
    }

    private fun JSONObject.toWatchedStatusEntity(): WatchedStatusEntity =
        WatchedStatusEntity(
            key = optString("key"),
            imdbId = optString("imdbId"),
            mediaType = optString("mediaType"),
            isWatched = optBoolean("isWatched", false),
            // Absent in backups taken before the eye badge existed: those
            // restore as not-partial, which is what they meant.
            isPartiallyWatched = optBoolean("isPartiallyWatched", false),
            updatedAt = optLong("updatedAt")
        )

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}