package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * When this device last WROTE watched state - a mark, an unmark, a finish.
 *
 * The input to the show library's read-time guard (see
 * `isShowLibraryBlobTrustworthy` and the caught-up leg in SimklReads.kt): a
 * library blob minted before this instant still describes the account as it was
 * BEFORE the viewer's last mark, so it cannot decide whether a show is caught
 * up. The reported case is exactly one episode of staleness - MobLand finished
 * at 17 of 18 aired episodes, the cached blob still said 17, and the show never
 * reached Upcoming - so the stamp has to be written wherever watched state
 * changes, not only where Simkl accepts a push.
 *
 * Deliberately its own small profile-scoped store rather than a field on the
 * Simkl session: a finish is recorded here even when Simkl is not connected, a
 * push fails, or the finish is only ever local, and all three writers (the
 * players' history writer, the watched-state repository, the Simkl write
 * successes) already hold a Context. Profile-scoped because each profile has its
 * own library blob to compare this against.
 *
 * Wall clock ([System.currentTimeMillis]), the same basis as the blob's own
 * `updatedAt`, which is what makes the comparison meaningful.
 */
internal object WatchedWriteStamp {

    private const val PREFS_NAME =
        "kbstream_watched_write_stamp"

    private const val KEY_LAST_WRITE_AT =
        "last_write_at"

    /** Records that watched state was just written, at [at] (test seam). */
    fun record(
        context: Context,
        at: Long = System.currentTimeMillis()
    ) {
        prefs(context)
            ?.edit()
            ?.putLong(KEY_LAST_WRITE_AT, at)
            ?.apply()
    }

    /** The instant of the last write on this profile, or 0 when there is none. */
    fun last(context: Context): Long =
        prefs(context)
            ?.getLong(KEY_LAST_WRITE_AT, 0L)
            ?: 0L

    private fun prefs(context: Context): SharedPreferences? =
        runCatching {
            context.applicationContext.getSharedPreferences(
                ProfileStorage.prefsName(context, PREFS_NAME),
                Context.MODE_PRIVATE
            )
        }.getOrNull()
}
