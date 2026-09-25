package com.kennyb1201.kbstream.data.history

import android.content.Context
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import kotlinx.coroutines.flow.Flow

/**
 * The app-facing watch-history access layer, over the profile-scoped
 * [WatchHistoryDatabase].
 *
 * Every call goes through [WatchHistoryDatabase.withScopedDao] /
 * [WatchHistoryDatabase.observeScopedDao]: the history database is a
 * per-profile FILE whose Room instance is retired when the active profile
 * changes, and an operation caught mid-flight by that retirement fails with
 * Room's "connection pool has been closed" rather than a real error - the
 * history twin of the guide-database race (see data/db/DatabaseSwapRetry.kt).
 * Retrying re-resolves the DAO against the instance the database layer settled
 * on instead of surfacing the swap as a failure.
 *
 * That matters most for [continueWatchingParentsFlow], which Home subscribes to
 * for as long as it is on screen: one unretried failure used to take the whole
 * subscription down and leave Continue Watching dead until Home was rebuilt.
 *
 * The DAO itself is resolved per access (never captured): the scoped Room
 * instance is bound to the ACTIVE profile's file, so a repository built before
 * a switch would otherwise keep reading the closed previous profile's DB.
 */
class WatchHistoryRepository(private val appContext: Context) {

    // 1. Recent history rows as a flow factory (same per-access rebinding
    //    rationale as [continueWatchingParentsFlow] - no caller in the app
    //    currently subscribes, kept for API parity).
    fun recentHistoryFlow(): Flow<List<WatchHistoryEntity>> =
        WatchHistoryDatabase.observeScopedDao(appContext) { it.observeRecent() }

    // 2. Continue Watching parents as a FLOW FACTORY, not a captured StateFlow:
    //    Room flows are bound to the DAO (and thus to the DB file) that created
    //    them. A captured StateFlow kept the Home pipeline subscribed to the
    //    previous profile's DB after a profile switch or first-profile creation
    //    (which closes the scoped instance) - continue watching then went
    //    stale/local-only until a full rebuild. Each call re-resolves the active
    //    profile's DAO, and the caller flatMapLatest's on the profile-change
    //    signal so a switch re-subscribes.
    fun continueWatchingParentsFlow(): Flow<List<WatchHistoryEntity>> =
        WatchHistoryDatabase.observeScopedDao(appContext) { it.observeContinueWatchingParents() }

    suspend fun upsert(entry: WatchHistoryEntity) {
        // Enqueued outside the retry: the outbox is keyed by row id, so a
        // re-enqueue would be harmless, but the sync side is not what a swap
        // interrupted.
        SupabaseSync.enqueueHistory(entry)
        WatchHistoryDatabase.withScopedDao(appContext) { it.upsert(entry) }
    }

    suspend fun getById(id: String): WatchHistoryEntity? =
        WatchHistoryDatabase.withScopedDao(appContext) { it.getById(id) }

    /**
     * One-shot suspend read of the Continue Watching parent rows. Backs
     * HomeViewModel's instant Continue Watching seed (cold-start fast path)
     * so the rail can render before the enriched pipeline finishes.
     */
    suspend fun getContinueWatchingParentsSnapshot(): List<WatchHistoryEntity> =
        WatchHistoryDatabase.withScopedDao(appContext) {
            it.getContinueWatchingParentsSnapshot()
        }

    suspend fun deleteById(id: String) {
        WatchHistoryDatabase.withScopedDao(appContext) { it.deleteById(id) }
    }

    /**
     * Removes every completed-episode row for one season of a show. Used by
     * the season-chip long-press "Mark as Unwatched" action so the local
     * watched state (and the derived episode badges) clears for that season
     * while other seasons stay untouched.
     */
    suspend fun deleteCompletedForParentSeason(
        parentId: String,
        season: Int
    ) {
        WatchHistoryDatabase.withScopedDao(appContext) {
            it.deleteCompletedForParentSeason(
                parentId = parentId,
                season = season
            )
        }
    }

    /**
     * Removes the completed-episode row(s) for a single episode of a show.
     * Used by the episode-card long-press "Mark as Unwatched" / "Mark
     * Previous as Unwatched" actions so only the targeted episode(s) clear
     * while the rest of the season stays untouched.
     */
    suspend fun deleteCompletedForParentSeasonEpisode(
        parentId: String,
        season: Int,
        episode: Int
    ) {
        WatchHistoryDatabase.withScopedDao(appContext) {
            it.deleteCompletedForParentSeasonEpisode(
                parentId = parentId,
                season = season,
                episode = episode
            )
        }
    }

    /**
     * Removes every in-progress (resume) row for a parent show/movie so it
     * disappears from Continue Watching, while preserving completed-episode
     * history used for watched badges and episode counts.
     */
    suspend fun deleteResumeRowsForParent(parentId: String) {
        WatchHistoryDatabase.withScopedDao(appContext) {
            it.deleteResumeRowsForParent(parentId)
        }
    }

    /**
     * Every row with a saved resume position - used to rebuild the TV
     * launcher Continue Watching rail and for backup/restore.
     */
    suspend fun getAll(): List<WatchHistoryEntity> =
        WatchHistoryDatabase.withScopedDao(appContext) { it.getAll() }

    suspend fun clearAll() {
        WatchHistoryDatabase.withScopedDao(appContext) { it.clearAll() }
    }
}
