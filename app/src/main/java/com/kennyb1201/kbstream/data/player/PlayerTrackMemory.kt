package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remembers the subtitle the user attached to a specific video, so opening it
 * again (days later, after a reboot, on the same profile) brings it back
 * without re-picking the file or re-searching OpenSubtitles.
 *
 * Keyed per VIDEO, not per show: a sidecar file is authored against one
 * episode, so attaching it to the next episode would be worse than attaching
 * nothing. Profile-scoped, and never synced — the stored value is a
 * `content://` or cache-file URI, which only means anything on the device that
 * chose it.
 *
 * Only external/online subtitles are recorded here. Embedded track choices
 * (audio + subtitle language) stay with the synced display prefs, which the
 * player already reads at startup.
 */
internal object PlayerTrackMemory {

    private const val TAG = "PLAYER_TRACK_MEMORY"
    private const val PREFS_BASE = "kbstream_player_memory"
    private const val KEY_ENTRIES = "subtitle_memory_v1"

    /** Bounded so a long-lived install cannot grow this file forever. */
    private const val MAX_ENTRIES = 150

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Entry(
        val uri: String,
        val label: String = "",
        val at: Long = 0L
    )

    @Serializable
    private data class Store(
        val entries: Map<String, Entry> = emptyMap()
    )

    /** What the player needs to restore a remembered subtitle. */
    data class Remembered(val uri: String, val label: String)

    /**
     * Identity of one playable video: the show id (when there is one) plus the
     * season/episode, or the media id for movies. Null when the caller has
     * nothing stable to key on — the player then simply remembers nothing.
     */
    fun keyFor(
        parentId: String?,
        mediaId: String?,
        season: Int?,
        episode: Int?
    ): String? {
        val show = parentId?.takeIf { it.isNotBlank() }
        if (show != null) {
            val s = season?.takeIf { it >= 0 }
            val e = episode?.takeIf { it >= 0 }
            return if (s != null && e != null) "$show|s$s|e$e" else show
        }
        return mediaId?.takeIf { it.isNotBlank() }
    }

    fun rememberSubtitle(context: Context, key: String?, uri: String, label: String = "") {
        if (key.isNullOrBlank() || uri.isBlank()) return
        runCatching {
            val store = read(context)
            val entries = LinkedHashMap(store.entries)
            entries[key] = Entry(uri = uri, label = label, at = System.currentTimeMillis())

            // Evict the oldest entries once past the cap.
            if (entries.size > MAX_ENTRIES) {
                entries.entries
                    .sortedBy { it.value.at }
                    .take(entries.size - MAX_ENTRIES)
                    .forEach { entries.remove(it.key) }
            }

            write(context, Store(entries))
        }.onFailure {
            Log.w(TAG, "remember subtitle failed: ${it.message}")
        }
    }

    fun rememberedSubtitle(context: Context, key: String?): Remembered? {
        if (key.isNullOrBlank()) return null
        val entry = runCatching { read(context).entries[key] }.getOrNull() ?: return null
        if (entry.uri.isBlank()) return null
        return Remembered(entry.uri, entry.label)
    }

    fun forget(context: Context, key: String?) {
        if (key.isNullOrBlank()) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            if (entries.remove(key) != null) write(context, Store(entries))
        }
    }

    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ENTRIES).apply() }
    }

    private fun read(context: Context): Store {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }
            .getOrElse {
                // A corrupt blob must never break playback: drop and restart.
                Log.w(TAG, "subtitle memory blob unreadable; resetting (${it.message})")
                Store()
            }
    }

    private fun write(context: Context, store: Store) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}
