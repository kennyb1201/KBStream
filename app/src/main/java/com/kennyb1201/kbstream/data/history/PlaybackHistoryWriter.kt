package com.kennyb1201.kbstream.data.history

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileManager
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
 * kids profile ever watched — and because [currentProfileId] was read at the
 * same moment, the cloud row was stamped with profile 1's scope too, so every
 * later pull re-downloaded it and the card came back after any cleanup. That
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
 *  2. a save whose profile is no longer the active one is REDIRECTED to the
 *     session's own profile file rather than REFUSED. It used to be refused
 *     with a `Log.w`, and because the player's trace note printed BEFORE the
 *     asynchronous Room write, the diagnostic said "filed" for a row that was
 *     never stored — a lie that cost a full investigation when a completed
 *     handoff left no local checkmark. The row belongs to the profile that
 *     started the session, so it is filed there, never under the profile the
 *     user moved TO.
 *
 * The refusal is now reserved for the one case that cannot be redirected: a
 * session profile that no longer exists (deleted mid-session). That is loud
 * (`Log.e` plus a `written=false reason=unknown-profile` trace note) so a row
 * is never silently dropped again.
 */
internal object PlaybackHistoryWriter {

    private const val TAG = "PLAYBACK_HISTORY"

    /**
     * Launch-intent extra naming the profile a session belongs to. Written by
     * the launcher, read by every engine.
     */
    const val EXTRA_SESSION_PROFILE_ID = "session_profile_id"

    /**
     * What a [write] actually did: whether the row landed, and the profile it
     * landed under (null when it was refused). Returned so the player can put
     * the truth in its session trace instead of predicting it (see
     * `saveProgress(onWritten = ...)` in both engines).
     */
    internal data class WriteResult(val ok: Boolean, val profileId: String?)

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
     * True when a row for [sessionProfileId] belongs in the ACTIVE profile's
     * database. Pure, so the rule is testable without a Context: the two must
     * be the SAME profile, including the both-null case (a device with no
     * profiles yet, whose history is the legacy unscoped database).
     */
    internal fun mayWrite(
        activeProfileId: String?,
        sessionProfileId: String?
    ): Boolean = activeProfileId == sessionProfileId

    /**
     * Stores one playback row, preserving the completion stamp of the row it
     * replaces, and mirrors the active profile's Continue Watching to the TV
     * launcher.
     *
     * The row goes to the ACTIVE profile's scoped database when the session's
     * profile is still the active one — the everyday path, unchanged. When the
     * user switched profiles mid-session the row is written to the SESSION
     * profile's own database (a one-shot [WatchHistoryDatabase.openForProfile]
     * handle pinned for the operation) so the departing profile keeps its row
     * and the incoming profile's Continue Watching stays clean. Only when the
     * session profile is gone does the write refuse, loudly.
     */
    suspend fun write(
        context: Context,
        sessionProfileId: String?,
        entry: WatchHistoryEntity
    ): WriteResult {
        val active = ProfileStorage.activeProfileId(context)
        if (mayWrite(active, sessionProfileId)) {
            val ok = runCatchingCancellable {
                WatchHistoryDatabase.withScopedDao(context) { dao ->
                    val row = preserveCompletion(entry, dao)
                    dao.upsert(row)
                    SupabaseSync.enqueueHistory(row, sessionProfileId)
                    // Only the rows the launcher can actually publish, not the
                    // whole table: a save happens on every position tick, and
                    // getAll() dragged every completed row back with it.
                    TvLauncherPublisher.sync(context, dao.getResumeRowsForLauncher())
                }
            }.onFailure { e ->
                Log.w(TAG, "could not write watch history for ${entry.id}", e)
            }.isSuccess
            return WriteResult(ok = ok, profileId = sessionProfileId)
        }

        // The session outlived a profile switch (or never had a profile while
        // the device now has one). Resolve the session profile against the
        // live list: a real one gets the row, a deleted one gets the loud
        // refusal instead of a silent drop.
        val target = sessionProfileId?.takeIf { pid ->
            ProfileManager.profileIds(context).contains(pid)
        }
        if (target == null) {
            Log.e(
                TAG,
                "dropping ${entry.id} (${entry.name}): this session belongs to " +
                    "profile ${sessionProfileId ?: "legacy"}, which no longer " +
                    "exists; the active profile is ${active ?: "legacy"}"
            )
            PlaybackSessionTrace.note(
                "written=false reason=unknown-profile " +
                    "s=${entry.season ?: "-"} e=${entry.episode ?: "-"} id=${entry.id}"
            )
            return WriteResult(ok = false, profileId = null)
        }

        val ok = runCatchingCancellable {
            // One-shot handle on the session profile's file, closed in
            // `finally`: it is not the active instance, so its open/close never
            // touches the active instance's tombstone bookkeeping.
            val db = WatchHistoryDatabase.openForProfile(context, target)
            try {
                val dao = db.watchHistoryDao()
                val row = preserveCompletion(entry, dao)
                dao.upsert(row)
                // Already session-scoped: the cloud row carries the same
                // profile the local row landed under.
                SupabaseSync.enqueueHistory(row, target)
            } finally {
                runCatching { db.close() }
            }
        }.onFailure { e ->
            Log.w(TAG, "could not write watch history for ${entry.id} into profile $target", e)
        }.isSuccess

        // The TV launcher mirrors the ACTIVE profile, never the departed
        // session: republish the active profile's own rows (best-effort, and
        // deliberately outside the success verdict above).
        runCatching {
            WatchHistoryDatabase.withScopedDao(context) { dao ->
                TvLauncherPublisher.sync(context, dao.getResumeRowsForLauncher())
            }
        }

        return WriteResult(ok = ok, profileId = target)
    }

    /**
     * The completion stamp belongs to the FIRST completion of a row, which is
     * why it is read back from the row being replaced rather than re-stamped: a
     * later save of the same finished row must not move the date the show was
     * marked watched.
     */
    private suspend fun preserveCompletion(
        entry: WatchHistoryEntity,
        dao: WatchHistoryDao
    ): WatchHistoryEntity {
        if (!(entry.isCompleted && entry.completedAt == null)) return entry
        val existing = dao.getById(entry.id)
        return entry.copy(completedAt = existing?.completedAt ?: entry.updatedAt)
    }
}
