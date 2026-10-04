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
    fun processed(): Set<Long> =
        prefs.getStringSet(KEY_PROCESSED, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun markProcessed(torrentId: Long) {
        val updated = processed().toMutableSet()
        if (!updated.add(torrentId)) return
        prefs.edit()
            .putStringSet(KEY_PROCESSED, updated.map { it.toString() }.toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "kbstream_debrid"
        private const val KEY_PROCESSED = "torbox_library_processed"
    }
}
