package com.kennyb1201.kbstream.data.sync

import android.util.Log

/**
 * One pulled tracker session, and what this device did with it.
 *
 * @param key the sync row's own key (`kitsu_auth`, `mal_auth`, `anilist_auth`)
 * @param profileId the row's profile, or null for the legacy unscoped store
 * @param outcome one of [AuthSyncTrace.ADOPTED], [AuthSyncTrace.TOMBSTONED],
 *   [AuthSyncTrace.REFUSED_STALE]
 */
internal data class AuthSyncEvent(
    val key: String,
    val profileId: String?,
    val outcome: String
)

/**
 * The last few session pulls the applier acted on (spec 2026-11-13, F6.3).
 *
 * A tracker session that syncs has one failure mode that leaves no trace
 * anywhere: the blob arrived and the device did nothing with it - refused it as
 * stale, or adopted it and kept a stale cached account state. That is
 * indistinguishable from "the blob never arrived" in every other line of a
 * report, so the outcome is recorded here and printed beside the `sync:` line.
 *
 * Connected yes/no only, like the `bingecat:` line: no token values, no viewer
 * names, no account identifiers ever reach this record.
 */
internal object AuthSyncTrace {

    const val ADOPTED = "adopted"
    const val TOMBSTONED = "tombstoned"
    const val REFUSED_STALE = "refused-stale"

    private const val TAG = "SUPABASE_SYNC"

    /** Enough to show a pull of all three legs on a couple of profiles. */
    private const val MAX_EVENTS = 8

    private val events = ArrayDeque<AuthSyncEvent>()

    fun record(key: String, profileId: String?, outcome: String) {
        synchronized(events) {
            events.addLast(AuthSyncEvent(key, profileId, outcome))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        // The stored session is never in this line: the key, the outcome, and
        // the profile's short id (a UUID: it identifies nothing and matches the
        // `profiles:` line's prefix).
        Log.i(TAG, "auth sync: $key profile=${profileId?.take(8) ?: "global"} $outcome")
    }

    fun events(): List<AuthSyncEvent> = synchronized(events) { events.toList() }

    /** Test hook: the record is process-wide and must not leak between cases. */
    internal fun reset() {
        synchronized(events) { events.clear() }
    }
}
