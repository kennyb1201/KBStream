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
        // The torrent is dealt with, so its empty-search streak is over: left
        // behind it would be re-counted against a torrent that is no longer
        // looked up at all.
        clearEmptySearch(torrentId)
    }

    /**
     * How many rounds running this torrent's TMDB search has come back empty.
     *
     * Kept apart from [processed] because the two answer opposite questions:
     * an empty result is retried a few rounds (it is as likely to be a blip as
     * a missing title), and only then is the torrent recorded as handled. See
     * [TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS].
     */
    @Synchronized
    fun emptySearchAttempts(torrentId: Long): Int =
        readAttempts()[torrentId] ?: 0

    /**
     * Records one more empty search for [torrentId] and returns the running
     * count, so the caller can tell "retry next round" from "record it and
     * stop looking" without a second read.
     */
    @Synchronized
    fun noteEmptySearch(torrentId: Long): Int {
        val attempts = readAttempts().toMutableMap()
        val next = (attempts[torrentId] ?: 0) + 1
        attempts[torrentId] = next
        writeAttempts(attempts)
        return next
    }

    /**
     * Forgets [torrentId]'s empty-search record: a search that produced
     * candidates is evidence the name resolves, so any earlier streak is over.
     */
    @Synchronized
    fun clearEmptySearch(torrentId: Long) {
        val attempts = readAttempts().toMutableMap()
        if (attempts.remove(torrentId) != null) writeAttempts(attempts)
    }

    private fun readAttempts(): Map<Long, Int> =
        prefs.getStringSet(KEY_EMPTY_ATTEMPTS, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val id = entry.substringBefore(':').toLongOrNull()
                val count = entry.substringAfter(':', "").toIntOrNull()
                if (id == null || count == null) null else id to count
            }
            .toMap()

    private fun writeAttempts(attempts: Map<Long, Int>) {
        // Bounded with the same policy as the processed set: the oldest entries
        // are dropped first (the map is rebuilt in insertion order), and a
        // torrent dropped this way is simply re-checked once.
        val bounded =
            if (attempts.size > MAX_EMPTY_ATTEMPTS) {
                attempts.entries.toList().takeLast(MAX_EMPTY_ATTEMPTS)
                    .associate { (id, count) -> id to count }
            } else {
                attempts
            }
        prefs.edit()
            .putStringSet(
                KEY_EMPTY_ATTEMPTS,
                bounded.map { (id, count) -> "$id:$count" }.toSet()
            )
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "kbstream_debrid"
        private const val KEY_PROCESSED = "torbox_library_processed"
        private const val MAX_PROCESSED = 5000
        private const val KEY_EMPTY_ATTEMPTS = "torbox_library_empty_searches"
        private const val MAX_EMPTY_ATTEMPTS = 5000
    }
}
