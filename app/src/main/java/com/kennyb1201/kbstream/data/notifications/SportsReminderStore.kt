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

    /**
     * Games the viewer asked to be reminded about BY NAME, whatever teams they
     * follow (audit: sports F3 - the reminder was global, keyed on favourites,
     * so a one-off game between two teams nobody follows could not be asked
     * for at all).
     *
     * Kept beside the announced record and in the same per-profile file: "remind
     * me about this game" is a choice about whose hub it is, exactly like
     * following a team is. Stored as the same `gameId=timestamp` lines, where
     * the timestamp is the game's own kick-off, so a request for a game that
     * has since been played can be dropped rather than kept forever.
     */
    private const val KEY_REQUESTED = "sports_reminder_requested"

    /** The store for the profile in use. */
    fun prefsFor(context: Context): SharedPreferences =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, FILE),
            Context.MODE_PRIVATE
        )

    /** Every remembered game id and when it was announced, epoch millis. */
    fun load(prefs: SharedPreferences): Map<String, Long> = readMap(prefs, KEY_NOTIFIED)

    /** The `id=value` line format both records share. */
    private fun readMap(prefs: SharedPreferences, key: String): Map<String, Long> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
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

    /**
     * The games asked for individually, as `gameId -> kick-off millis`.
     *
     * A kick-off of 0 means the hub did not know it when the request was made;
     * such a row is kept until the ordinary clean-up drops it, which is the
     * honest outcome for a request with no expiry attached.
     */
    fun loadRequested(prefs: SharedPreferences): Map<String, Long> =
        readMap(prefs, KEY_REQUESTED)

    /**
     * Adds or removes one game's individual reminder.
     *
     * [startsAtMs] is stored with it so the clean-up can tell a request for a
     * game that has started from one still ahead; 0 when unknown.
     */
    fun setRequested(
        prefs: SharedPreferences,
        gameId: String,
        startsAtMs: Long,
        requested: Boolean
    ) {
        if (gameId.isBlank()) return
        val current = loadRequested(prefs)
        val next = if (requested) current + (gameId to startsAtMs) else current - gameId
        write(requestedPrefsKey = KEY_REQUESTED, prefs = prefs, values = next)
    }

    /**
     * Drops requests whose game started more than the dedupe TTL ago, and any
     * whose kick-off the hub never knew once its announced record has aged out.
     */
    fun pruneRequested(prefs: SharedPreferences, nowMs: Long) {
        val current = loadRequested(prefs)
        val kept = current.filterValues { startMs ->
            startMs <= 0L || nowMs - startMs < SportsGameReminderRules.DEDUPE_TTL_MS
        }
        if (kept.size != current.size) {
            write(requestedPrefsKey = KEY_REQUESTED, prefs = prefs, values = kept)
        }
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
        write(requestedPrefsKey = KEY_NOTIFIED, prefs = prefs, values = notified)
    }

    private fun write(
        requestedPrefsKey: String,
        prefs: SharedPreferences,
        values: Map<String, Long>
    ) {
        val raw = values.entries.joinToString("\n") { (id, at) -> "$id=$at" }
        prefs.edit().putString(requestedPrefsKey, raw).apply()
    }
}
