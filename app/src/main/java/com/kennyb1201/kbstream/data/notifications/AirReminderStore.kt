package com.kennyb1201.kbstream.data.notifications

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * Shows the viewer explicitly asked to be reminded about, per profile.
 *
 * The new-episode check already follows the shows a profile *watches* — that
 * set is inferred from continue-watching and the watched-status cache, so it
 * only covers something already in progress. This is the other half: a show the
 * viewer flags by hand ("Remind me when it airs") before they have started it,
 * so an upcoming premiere can be announced on the day it airs even though no
 * episode is in progress.
 *
 * The flag itself is the only thing stored here. Once a flagged show is being
 * checked, "announce the newest aired episode once" is exactly what
 * [NewEpisodeStore] already does for the inferred set, so the once-only
 * bookkeeping is shared rather than duplicated.
 *
 * Profile-scoped and deliberately NOT in the synced prefs allow-list: a
 * reminder is device state, not account state.
 */
internal class AirReminderStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        ProfileStorage.prefsName(context.applicationContext, PREFS_NAME),
        Context.MODE_PRIVATE
    )

    /** Every show this profile has flagged, as stored ids. */
    fun flagged(): Set<String> =
        prefs.getStringSet(KEY_FLAGGED, emptySet()).orEmpty().toSet()

    fun isFlagged(showId: String): Boolean = showId in flagged()

    /** Sets the flag to [flagged]; returns nothing — read [isFlagged] back. */
    fun setFlagged(showId: String, flagged: Boolean) {
        if (showId.isBlank()) return
        val updated = flagged().toMutableSet()
        if (flagged) updated.add(showId) else updated.remove(showId)
        prefs.edit().putStringSet(KEY_FLAGGED, updated).apply()
    }

    /** Flips the flag and returns the new state, so a menu can label itself. */
    fun toggle(showId: String): Boolean {
        val nowFlagged = !isFlagged(showId)
        setFlagged(showId, nowFlagged)
        return nowFlagged
    }

    companion object {
        /** Shared with [NewEpisodeStore]; one notifications store per profile. */
        private const val PREFS_NAME = "kbstream_notifications"
        private const val KEY_FLAGGED = "air_reminders"

        /**
         * True when this profile has at least one show flagged. The scheduled
         * round keys off this as well as the notifications toggle, so a viewer
         * who wants reminders but left "new episode" alerts off still gets the
         * work armed.
         */
        fun hasAny(context: Context): Boolean =
            AirReminderStore(context).flagged().isNotEmpty()
    }
}
