package com.kennyb1201.kbstream.data.notifications

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * Remembers, per profile, which episode of each followed show was the newest
 * aired one at the last check. That snapshot is the ONLY thing standing
 * between "notify once when a new episode lands" and "notify again every
 * twelve hours forever".
 *
 * Stored in the profile-scoped prefs namespace, so profile 2 never inherits
 * profile 1's shows, and it is deliberately NOT part of the synced prefs
 * allowlist: notification history is device state, not account state.
 *
 * Entries are plain "showKey|S3E4" strings in a StringSet — no JSON, nothing
 * to migrate, and every operation is a prefix match.
 */
internal class NewEpisodeStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        ProfileStorage.prefsName(context.applicationContext, PREFS_NAME),
        Context.MODE_PRIVATE
    )

    /** The episode recorded at the last check, or null if never seen. */
    fun lastSeen(showKey: String): String? =
        entries().firstOrNull { it.startsWith("$showKey$SEPARATOR") }
            ?.substringAfter(SEPARATOR)

    /**
     * Records the newest aired episode. Returns true when the stored value
     * actually changed (so callers can log real transitions only).
     */
    fun record(showKey: String, episodeKey: String): Boolean {
        val current = entries()
        val existing = current.firstOrNull { it.startsWith("$showKey$SEPARATOR") }
        if (existing == "$showKey$SEPARATOR$episodeKey") return false
        val updated = current.filterNot { it.startsWith("$showKey$SEPARATOR") }.toMutableSet()
        updated.add("$showKey$SEPARATOR$episodeKey")
        prefs.edit().putStringSet(KEY_SEEN, updated).apply()
        return true
    }

    /**
     * Drops shows that are no longer followed (removed from continue
     * watching), so the snapshot can't grow forever or notify about a show
     * the user dropped and re-added months later.
     */
    fun retainOnly(showKeys: Set<String>) {
        val kept = entries().filterTo(mutableSetOf()) { entry ->
            val key = entry.substringBefore(SEPARATOR)
            key in showKeys
        }
        if (kept.size != entries().size) {
            prefs.edit().putStringSet(KEY_SEEN, kept).apply()
        }
    }

    private fun entries(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toSet()

    private companion object {
        const val PREFS_NAME = "kbstream_notifications"
        const val KEY_SEEN = "seen_episodes"
        const val SEPARATOR = "|"
    }
}
