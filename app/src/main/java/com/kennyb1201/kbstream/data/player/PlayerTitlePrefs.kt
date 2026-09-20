package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-title playback preferences the user set *while watching*: audio
 * language, subtitle language, and the subtitle/audio delay offsets.
 *
 * Keyed per SHOW (not per episode) — unlike [PlayerTrackMemory], which keys a
 * sidecar subtitle file to one video. A language choice or an A/V delay is a
 * property of how the user wants *that series* to play, so episode 2 inherits
 * episode 1's choices while a one-off subtitle file deliberately does not.
 *
 * Device-local and profile-scoped, never synced: the whole point is N+1
 * devices not fighting over one user's per-title tweaks.
 */
internal object PlayerTitlePrefs {

    private const val TAG = "PLAYER_TITLE_PREFS"

    // Same prefs file as PlayerTrackMemory (it is already profile-scoped and
    // cleared with that memory); a distinct key keeps the blobs independent.
    private const val PREFS_BASE = "kbstream_player_memory"
    private const val KEY_ENTRIES = "title_prefs_v1"

    /** Bounded like the subtitle memory, so the file cannot grow forever. */
    private const val MAX_ENTRIES = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Prefs(
        val audioLang: String = "",
        val subtitleLang: String = "",
        val subtitleOffsetMs: Int = 0,
        val audioDelayMs: Int = 0,
        /**
         * One specific audio track in this show's files, as
         * `language|codecs|channels` (see PlayerTrackBridge.signatureOf), or
         * blank for "whichever track my language preference picks".
         *
         * A signature, not an index: the same episode on another source (or a
         * different episode) numbers its tracks differently, but "English
         * E-AC3 5.1" keeps meaning the same thing — which is exactly the track
         * a language-only preference picks wrong.
         */
        val audioTrackSignature: String = ""
    ) {
        /** True when nothing differs from the global defaults. */
        val isEmpty: Boolean
            get() = audioLang.isBlank() &&
                subtitleLang.isBlank() &&
                subtitleOffsetMs == 0 &&
                audioDelayMs == 0 &&
                audioTrackSignature.isBlank()
    }

    @Serializable
    private data class Entry(
        val prefs: Prefs,
        val at: Long = 0L
    )

    @Serializable
    private data class Store(
        val entries: Map<String, Entry> = emptyMap()
    )

    /**
     * Identity of one show: the show's catalog id when there is one, else the
     * history row's own id (movies). Null when the caller has nothing stable
     * to key on — the player then simply remembers nothing.
     */
    fun titleKeyFor(parentId: String?, mediaId: String?): String? =
        parentId?.takeIf { it.isNotBlank() }
            ?: mediaId?.takeIf { it.isNotBlank() }

    fun remember(context: Context, key: String?, prefs: Prefs) {
        if (key.isNullOrBlank()) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            if (prefs.isEmpty) {
                // Nothing to remember: drop the row so we never resurrect a
                // stale choice after the user put everything back to Auto.
                if (entries.remove(key) != null) write(context, Store(entries))
                return
            }
            entries[key] = Entry(prefs = prefs, at = System.currentTimeMillis())

            if (entries.size > MAX_ENTRIES) {
                entries.entries
                    .sortedBy { it.value.at }
                    .take(entries.size - MAX_ENTRIES)
                    .forEach { entries.remove(it.key) }
            }

            write(context, Store(entries))
        }.onFailure {
            Log.w(TAG, "remember title prefs failed: ${it.message}")
        }
    }

    fun get(context: Context, key: String?): Prefs? {
        if (key.isNullOrBlank()) return null
        return runCatching { read(context).entries[key]?.prefs }.getOrNull()
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
                // A corrupt blob must never affect playback.
                Log.w(TAG, "title prefs blob unreadable; resetting (${it.message})")
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
