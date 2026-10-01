package com.kennyb1201.kbstream.data.history

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher

/**
 * The one place a PLAYBACK row is written.
 *
 * Every engine used to resolve the scoped history database at SAVE time —
 * `WatchHistoryDatabase.getInstanceScoped(activity)` inside the save
 * coroutine — which is the database of the profile that is active *then*, not
 * the one the session started under. A session that outlives a profile switch
 * (PiP is the everyday case: press Home during playback, reopen the app, switch
 * to profile 1, and the kids profile's episode is still running) therefore
 * filed that profile's next save under the profile the user moved TO. The
 * result is a LOCAL Continue Watching card on profile 1 for a show only the
 * kids profile ever watched — and because [currentProfileId] is read at the
 * same moment, the cloud row is stamped with profile 1's scope too, so every
 * later pull re-downloads it and the card comes back after any cleanup. That
 * is exactly the "poisoned row" the scoped-database layer already refuses to
 * create for its own callers ([WatchHistoryDatabase.withScopedDao] pins the
 * file and throws instead); the player paths simply bypassed it.
 *
 * So two things happen here:
 *
 *  1. the session's profile is pinned when the session STARTS. It travels in
 *     the launch intent ([EXTRA_SESSION_PROFILE_ID]) rather than being read
 *     from the activity's own state, because an activity recreation — which is
 *     what a switch can cause — would otherwise re-pin a running session to
 *     whichever profile is active by then, which is the bug again;
 *  2. a save whose profile is no longer the active one is REFUSED instead of
 *     misfiled. The departing profile keeps the last row it legitimately owns
 *     (the pause/stop saves that ran before the switch), and the incoming
 *     profile's Continue Watching stays clean.
 *
 * The refusal is deliberately not an error report: switching profiles during
 * playback is a user action, not a defect.
 */
internal object PlaybackHistoryWriter {

    private const val TAG = "PLAYBACK_HISTORY"

    /**
     * Launch-intent extra naming the profile a session belongs to. Written by
     * the launcher, read by every engine.
     */
    const val EXTRA_SESSION_PROFILE_ID = "session_profile_id"

    /**
     * The id to stamp on a session launched now: the profile active at launch.
     * Read at the launch site so a later recreation cannot re-stamp it.
     */
    fun profileIdForNewSession(context: Context): String? =
        ProfileStorage.activeProfileId(context)

    /**
     * The profile a session belongs to: the one its launch intent carries, or
     * the active one when the intent has none (an older build's intent, or a
     * deep link that never went through the launcher).
     */
    fun sessionProfileId(
        context: Context,
        intent: Intent?
    ): String? =
        intent?.getStringExtra(EXTRA_SESSION_PROFILE_ID)?.takeIf { it.isNotBlank() }
            ?: ProfileStorage.activeProfileId(context)

    /**
     * True when a row for [sessionProfileId] may be written while [activeProfileId]
     * is the active profile. Pure, so the rule is testable without a Context:
     * the two must be the SAME profile, including the both-null case (a device
     * with no profiles yet, whose history is the legacy unscoped database).
     */
    internal fun mayWrite(
        activeProfileId: String?,
        sessionProfileId: String?
    ): Boolean = activeProfileId == sessionProfileId

    /**
     * Stores one playback row, preserving the completion stamp of the row it
     * replaces, and mirrors the active profile's Continue Watching to the TV
     * launcher. Returns true when the row was stored.
     *
     * Returns false — without touching either database — when the session's
     * profile is no longer the active one, or when the scoped database is
     * swapped mid-write ([WatchHistoryDatabase.withScopedDao] pins the file, so
     * a switch during the write fails rather than landing in the new profile).
     */
    suspend fun write(
        context: Context,
        sessionProfileId: String?,
        entry: WatchHistoryEntity
    ): Boolean {
        val active = ProfileStorage.activeProfileId(context)
        if (!mayWrite(active, sessionProfileId)) {
            Log.w(
                TAG,
                "refusing to file ${entry.id} (${entry.name}) under profile " +
                    "${active ?: "legacy"}: this session belongs to profile " +
                    "${sessionProfileId ?: "legacy"}"
            )
            return false
        }

        return runCatchingCancellable {
            WatchHistoryDatabase.withScopedDao(context) { dao ->
                val existing = dao.getById(entry.id)
                // The completion stamp belongs to the FIRST completion of this
                // row, which is why it is read back rather than re-stamped: a
                // later save of the same finished row must not move the date
                // the show was marked watched.
                val row =
                    if (entry.isCompleted && entry.completedAt == null) {
                        entry.copy(completedAt = existing?.completedAt ?: entry.updatedAt)
                    } else {
                        entry
                    }
                dao.upsert(row)
                SupabaseSync.enqueueHistory(row, sessionProfileId)
                TvLauncherPublisher.sync(context, dao.getAll())
            }
        }.onFailure { e ->
            Log.w(TAG, "could not write watch history for ${entry.id}", e)
        }.isSuccess
    }
}
