package com.kennyb1201.kbstream.data.notifications

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * Which followed-team games have already been announced, so a game is never
 * buzzed about twice.
 *
 * Per profile, like the favourites list it describes: who you follow is part of
 * whose hub it is, so the record of what was announced has to follow the same
 * profile the notifications were chosen for. Stored as `gameId=timestamp` lines
 * - a small, bounded map rather than a JSON document, because the only two
 * operations are "have I seen this id" and "drop the expired ones".
 *
 * The ids live for [SportsGameReminderRules.DEDUPE_TTL_MS] and are pruned on
 * every run, so this cannot grow for the life of the install.
 */
object SportsReminderStore {

    private const val FILE = "sports_reminders"
    private const val KEY_NOTIFIED = "sports_reminder_notified"

    /** The store for the profile in use. */
    fun prefsFor(context: Context): SharedPreferences =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, FILE),
            Context.MODE_PRIVATE
        )

    /** Every remembered game id and when it was announced, epoch millis. */
    fun load(prefs: SharedPreferences): Map<String, Long> {
        val raw = prefs.getString(KEY_NOTIFIED, null) ?: return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val id = line.substring(0, separator)
                val at = line.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
                id.takeIf { it.isNotBlank() }?.let { it to at }
            }
            .toMap()
    }

    /** Remembers one game as announced. */
    fun markNotified(prefs: SharedPreferences, gameId: String, atMs: Long) {
        if (gameId.isBlank()) return
        write(prefs, load(prefs) + (gameId to atMs))
    }

    /** Drops records past their TTL. */
    fun prune(prefs: SharedPreferences, nowMs: Long) {
        val current = load(prefs)
        val kept = SportsGameReminderRules.prune(current, nowMs)
        if (kept.size != current.size) write(prefs, kept)
    }

    private fun write(prefs: SharedPreferences, notified: Map<String, Long>) {
        val raw = notified.entries.joinToString("\n") { (id, at) -> "$id=$at" }
        prefs.edit().putString(KEY_NOTIFIED, raw).apply()
    }
}
