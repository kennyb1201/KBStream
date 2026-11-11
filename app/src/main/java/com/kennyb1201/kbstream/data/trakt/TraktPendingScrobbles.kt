package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, profile-scoped storage for [PendingScrobble].
 *
 * The same shape as the tree's other small stores (a `org.json` blob in a prefs
 * file named through [ProfileStorage.prefsName]), rather than a Room table: a
 * handful of entries that are written on a failed stop and read on the next
 * Trakt window do not justify an entity, a DAO and a schema migration on the
 * profile database - and profile scoping here is what a Room table would need a
 * column and a filter for anyway.
 *
 * Profile scoping matters: a stop belongs to the profile whose Trakt session it
 * was scrobbled through, and the same title can be watched by two people on one
 * device. Prefs named `${profileId}.kbstream_trakt_pending_scrobbles` keeps the
 * two apart for free, and a disconnect that clears Trakt's token leaves the
 * queue in its own file to be replayed when the profile reconnects.
 */
internal class TraktPendingScrobbles(private val context: Context?) {

    private fun prefs() = context?.applicationContext?.let { app ->
        app.getSharedPreferences(
            ProfileStorage.prefsName(app, PREFS_NAME),
            Context.MODE_PRIVATE
        )
    }

    /** Every queued play, oldest first. A missing or unreadable blob is empty. */
    @Synchronized
    fun read(): List<PendingScrobble> {
        val raw = prefs()?.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    // opt*-reads throughout: one corrupt element must not
                    // discard the rest of the queue (the same lesson the
                    // deferred-delete store records).
                    val obj = array.optJSONObject(index) ?: continue
                    val action = obj.optString("action").takeIf { it.isNotEmpty() } ?: continue
                    val parentId = obj.optString("parentId").takeIf { it.isNotEmpty() } ?: continue
                    val parentType =
                        obj.optString("parentType").takeIf { it.isNotEmpty() } ?: continue
                    add(
                        PendingScrobble(
                            action = action,
                            parentId = parentId,
                            parentType = parentType,
                            season = obj.optInt("season", Int.MIN_VALUE)
                                .takeIf { it != Int.MIN_VALUE },
                            episode = obj.optInt("episode", Int.MIN_VALUE)
                                .takeIf { it != Int.MIN_VALUE },
                            progress = obj.optInt("progress", 0),
                            tmdbId = obj.optInt("tmdbId", 0).takeIf { it > 0 },
                            attemptedAtMs = obj.optLong("attemptedAtMs", 0L)
                        )
                    )
                }
            }
        }.getOrElse {
            Log.w(TAG, "pending scrobble queue unreadable: ${it.message}")
            emptyList()
        }
    }

    /**
     * Adds one failed stop, replacing any older entry for the same PLAY (see
     * [PendingScrobble.playKey]) and dropping the oldest beyond
     * [TraktScrobbleQueueRules.MAX_ENTRIES].
     */
    @Synchronized
    fun record(entry: PendingScrobble) {
        val current = read().filterNot { it.playKey == entry.playKey }.toMutableList()
        current += entry
        while (current.size > TraktScrobbleQueueRules.MAX_ENTRIES) {
            current.removeAt(0)
        }
        write(current)
    }

    /** Drops the entry for one play (delivered, or retired by the cap). */
    @Synchronized
    fun remove(playKey: String) {
        val current = read()
        val kept = current.filterNot { it.playKey == playKey }
        if (kept.size != current.size) write(kept)
    }

    private fun write(entries: List<PendingScrobble>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
                .put("action", entry.action)
                .put("parentId", entry.parentId)
                .put("parentType", entry.parentType)
                .put("progress", entry.progress)
                .put("attemptedAtMs", entry.attemptedAtMs)
            entry.season?.let { obj.put("season", it) }
            entry.episode?.let { obj.put("episode", it) }
            entry.tmdbId?.let { obj.put("tmdbId", it) }
            array.put(obj)
        }
        prefs()?.edit()?.putString(KEY_ENTRIES, array.toString())?.apply()
    }

    private companion object {
        const val TAG = "TraktPendingScrobbles"
        const val PREFS_NAME = "kbstream_trakt_pending_scrobbles"
        const val KEY_ENTRIES = "pending_scrobbles"
    }
}
