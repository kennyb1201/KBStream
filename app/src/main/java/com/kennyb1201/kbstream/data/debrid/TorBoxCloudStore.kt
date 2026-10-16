package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * Which of the account's TorBox torrents the library sync has already handled,
 * per profile.
 *
 * The sync runs on a schedule and on every app launch, so without this it would
 * re-search TMDB for every torrent in the account each time. An id is recorded
 * once it has been dealt with — successfully added, or found to name nothing
 * TMDB can match — so a round only costs work for torrents that are new since
 * the last one.
 *
 * Profile-scoped like every other on-device store and deliberately NOT in the
 * synced prefs allow-list: which torrents this device has processed is local
 * bookkeeping, not account state (the Library entries it produces DO sync, via
 * LocalLibraryStore's own blob).
 */
internal class TorBoxCloudStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        ProfileStorage.prefsName(context.applicationContext, PREFS_NAME),
        Context.MODE_PRIVATE
    )

    /** Torrent ids already dealt with by a previous round. */
    @Synchronized
    fun processed(): Set<Long> =
        prefs.getStringSet(KEY_PROCESSED, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    /**
     * Records [torrentId] as handled. Synchronized because the library sync can
     * run from more than one place (launch, the schedule, a manual "sync now"),
     * and the read-modify-write here would otherwise drop one round's ids when
     * two overlap.
     */
    @Synchronized
    fun markProcessed(torrentId: Long) {
        val updated = processed().toMutableSet()
        if (!updated.add(torrentId)) return
        // Cap the set so a very large library cannot grow the persisted set
        // without bound. Ids are unordered in a StringSet, so the overflow is
        // dropped arbitrarily: those torrents are simply re-checked once.
        val capped =
            if (updated.size > MAX_PROCESSED) {
                updated.toList().takeLast(MAX_PROCESSED).toMutableSet()
            } else {
                updated
            }
        prefs.edit()
            .putStringSet(KEY_PROCESSED, capped.map { it.toString() }.toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "kbstream_debrid"
        private const val KEY_PROCESSED = "torbox_library_processed"
        private const val MAX_PROCESSED = 5000
    }
}
