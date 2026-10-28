package com.kennyb1201.kbstream.data.history

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kennyb1201.kbstream.data.reporting.CrashReporter
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
import com.kennyb1201.kbstream.data.reporting.TrackerHealth
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
     * True while the session that owns [sessionProfileId] is still the profile
     * that is ACTIVE — the guard every TRACKER push uses.
     *
     * A Simkl scrobble or MDBList call resolves the ACTIVE profile's token/key
     * at FIRE time, and playback can outlive a profile switch (PiP is the
     * everyday case). Without this guard the departing session's completion is
     * scrobbled to the profile the viewer moved TO — the same cross-profile
     * leak the local history write already refuses to create (see [write])
     * and, on the tracker side, the more damaging half: a valid token accepts
     * the row, so the incoming profile's account is poisoned rather than the
     * call simply being rejected. Null on both sides is the legacy no-profiles
     * device, which stays true.
     */
    fun sessionStillActive(context: Context, sessionProfileId: String?): Boolean =
        mayWrite(ProfileStorage.activeProfileId(context), sessionProfileId)

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
                    // The completion has landed, so the episode's resume point
                    // goes with it - the rows the upsert above does not reach
                    // (see [supersededResumeIds]).
                    val cleared = supersededResumeIds(row, dao)
                    SupabaseSync.enqueueHistory(row, sessionProfileId)
                    // ...and the cloud copies of those rows are TOMBSTONED, not
                    // simply deleted: a row deleted only locally is re-inserted
                    // by the next pull, which is how a finished episode's
                    // resume card came back on the next sync.
                    //
                    // Published BEFORE the local rows go, deliberately: the
                    // tombstone is the durable half (the outbox survives process
                    // death), so a crash in between leaves rows whose cloud copy
                    // is already gone and the pull's tombstone removes them here
                    // too. The other order had nothing to repair it with - the
                    // rows went, nothing was queued, and the next pull restored
                    // the finished episode's resume card.
                    if (cleared.isNotEmpty()) {
                        SupabaseSync.deleteHistoryRows(cleared, sessionProfileId)
                        runCatchingCancellable { dao.deleteByIds(cleared) }.onFailure { e ->
                            Log.w(TAG, "could not delete the superseded resume rows for ${row.id}", e)
                        }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "could not write watch history for ${entry.id}", e)
                // A lost history write is the quietest failure in the app: the
                // episode plays on, and the missing resume point or completion
                // turns up days later. It reaches the diagnostics report and the
                // retained error list from here now (spec C11).
                TrackerHealth.note(
                    kind = "history",
                    tracker = "local",
                    ok = false,
                    detail = e.javaClass.simpleName
                )
                CrashReporter.recordNonFatal(e, mapOf("where" to "history-write"))
            }.also { result ->
                if (result.isSuccess) {
                    TrackerHealth.note(kind = "history", tracker = "local", ok = true)
                }
            }.isSuccess

            // The launcher republish is NOT part of that verdict. It mirrors a
            // table this save has already written, so a failure here is a
            // missed Watch Next row, not a lost resume point - and folded into
            // the same runCatching it reported the SAVE as failed, which sent
            // callers down their failure path for a row that had landed.
            // Best-effort and cancellable, exactly like the redirect branch
            // below. Only the rows the launcher can actually publish, not the
            // whole table: a save happens on every position tick, and getAll()
            // dragged every completed row back with it.
            runCatchingCancellable {
                WatchHistoryDatabase.withScopedDao(context) { dao ->
                    TvLauncherPublisher.sync(context, dao.getResumeRowsForLauncher())
                }
            }

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
                val cleared = supersededResumeIds(row, dao)
                // Already session-scoped: the cloud row carries the same
                // profile the local row landed under.
                SupabaseSync.enqueueHistory(row, target)
                if (cleared.isNotEmpty()) {
                    // Tombstone first, then delete locally - see the note on the
                    // active-profile path above.
                    SupabaseSync.deleteHistoryRows(cleared, target)
                    runCatchingCancellable { dao.deleteByIds(cleared) }.onFailure { e ->
                        Log.w(TAG, "could not delete the superseded resume rows for ${row.id}", e)
                    }
                }
            } finally {
                runCatching { db.close() }
            }
        }.onFailure { e ->
            Log.w(TAG, "could not write watch history for ${entry.id} into profile $target", e)
        }.isSuccess

        // The TV launcher mirrors the ACTIVE profile, never the departed
        // session: republish the active profile's own rows (best-effort, and
        // deliberately outside the success verdict above).
        //
        // Cancellable, though, and this is the one place in the app that had
        // the block on plain `runCatching` alone: it SUSPENDS (the scoped dao
        // and the publisher both do), so a cancellation arriving mid-save - a
        // profile switch, a player teardown - was captured as a launcher-sync
        // FAILURE, logged, and the coroutine then carried on to the `return`
        // below reporting a save it never got to finish. Best-effort means a
        // failed publish does not fail the save (see the sibling
        // WatchedStatusRepository.publishLauncherWatchNext, which is this same
        // block); it does not mean a cancellation is a fault.
        runCatchingCancellable {
            WatchHistoryDatabase.withScopedDao(context) { dao ->
                TvLauncherPublisher.sync(context, dao.getResumeRowsForLauncher())
            }
        }

        return WriteResult(ok = ok, profileId = target)
    }

    /**
     * DELETES the episode's resume rows once its completion has been filed, and
     * returns the ids it removed (so the caller can tombstone them).
     *
     * A completion and a resume point are the SAME row in this table - the
     * completion is the resume row overwritten with `positionMs = 0` and
     * `isCompleted = 1` - so this is deliberately about the OTHER rows: a
     * resume row for the same episode filed under a different row id, which
     * the overwrite cannot reach (see [WatchHistoryDao.getSupersededResumeIds]
     * for what those are). Left behind, one of them is a Continue Watching card
     * and a progress bar on an episode that is already ticked - the field
     * report's "it keeps returning to the ten minutes it has already
     * finished".
     *
     * Runs only for a completed row, so the once-a-session completion pays for
     * it and the every-tick position save does not.
     *
     * A lookup that fails is not fatal: the completion itself has already been
     * written, and the next session's completion retries the cleanup.
     */
    /**
     * The rows a completion for [entry] supersedes - the READ half of the pair
     * whose delete half is [WatchHistoryDao.deleteByIds].
     *
     * Split in two on purpose: the caller publishes the tombstone for these ids
     * BEFORE it deletes them, so the durable half of the pair is in place first
     * (see the call sites). Nothing here is transactional either way - the read
     * and the delete were always two statements - so the split loses no
     * guarantee and buys the crash-safe order.
     */
    private suspend fun supersededResumeIds(
        entry: WatchHistoryEntity,
        dao: WatchHistoryDao
    ): List<String> {
        if (!entry.isCompleted) return emptyList()
        // The series' OTHER id flavor too, resolved offline (see
        // [WatchHistoryDao.parentIdFlavors]): this completion was filed under
        // whichever flavor launched the session, and a resume row written from a
        // route that launched the twin ("tt..." vs "tmdb:<n>") is the same
        // episode's leftover. Scoped to one flavor, that row survived and came
        // back as a Continue Watching card at 99% - the field report's "it keeps
        // returning to the ten minutes it has already finished".
        val parents = runCatchingCancellable { dao.parentIdFlavors(entry.parentId) }
            .onFailure { e ->
                Log.w(TAG, "could not resolve the id flavors for ${entry.id}", e)
            }
            .getOrDefault(listOf(entry.parentId))
            .filter { it.isNotBlank() }
        if (parents.isEmpty()) return emptyList()
        val ids = runCatchingCancellable {
            dao.getSupersededResumeIds(
                parentIds = parents,
                streamId = entry.episodeStreamId?.takeIf { it.isNotBlank() },
                season = entry.season,
                episode = entry.episode
            ).filter { it != entry.id }
        }.onFailure { e ->
            Log.w(TAG, "could not find the superseded resume rows for ${entry.id}", e)
        }.getOrDefault(emptyList())
        return ids
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
