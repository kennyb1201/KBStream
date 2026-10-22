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

    /**
     * Where the next check round starts in the followed-shows list.
     *
     * The round is capped at [NewEpisodeChecker.MAX_SHOWS_PER_RUN] shows, and
     * the cursor used to be the literal 0: with a followed list built in a
     * deterministic order, every show ranked past the cap was NEVER checked -
     * no new-episode alert, ever, on a profile with more than thirty followed
     * titles - despite the cap's comment promising the rest were "on the
     * following run". The cursor is what makes that true: each round takes the
     * next window and parks the start after it, wrapping at the end.
     */
    fun startOffset(): Int = prefs.getInt(KEY_ROUND_START, 0).coerceAtLeast(0)

    fun setStartOffset(offset: Int) {
        prefs.edit().putInt(KEY_ROUND_START, offset.coerceAtLeast(0)).apply()
    }

    private fun entries(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toSet()

    private companion object {
        const val PREFS_NAME = "kbstream_notifications"
        const val KEY_SEEN = "seen_episodes"
        const val KEY_ROUND_START = "check_round_start"
        const val SEPARATOR = "|"
    }
}
