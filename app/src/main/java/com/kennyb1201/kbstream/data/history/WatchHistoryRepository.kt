package com.kennyb1201.kbstream.data.history

import android.content.Context
import kotlinx.coroutines.flow.Flow

class WatchHistoryRepository(private val appContext: Context) {

    // Resolved per access: the scoped Room instance is bound to the ACTIVE
    // profile's database file. Capturing one DAO at construction kept this
    // repository attached to the profile that was active when it was built -
    // after a profile switch (or first-profile creation, which closes the
    // scoped DB) reads/writes hit the closed or previous profile's DB. With
    // per-access resolution every call rebinds to the active profile.
    private val dao: WatchHistoryDao
        get() = WatchHistoryDatabase
            .getInstanceScoped(appContext)
            .watchHistoryDao()

    // 1. Recent history rows as a flow factory (same per-access rebinding
    //    rationale as [continueWatchingParentsFlow] - no caller in the app
    //    currently subscribes, kept for API parity).
    fun recentHistoryFlow(): Flow<List<WatchHistoryEntity>> =
        dao.observeRecent()

    // 2. Continue Watching parents as a FLOW FACTORY, not a captured
    //    StateFlow: Room flows are bound to the DAO (and thus to the DB
    //    file) that created them. A captured StateFlow kept the Home
    //    pipeline subscribed to the previous profile's DB after a profile
    //    switch or first-profile creation (which closes the scoped
    //    instance) - continue watching then went stale/local-only until a
    //    full rebuild. Each call re-resolves the active profile's DAO, and
    //    flatMapLatest on the caller side re-subscribes whenever the
    //    upstream profile-change signal fires.
    fun continueWatchingParentsFlow(): Flow<List<WatchHistoryEntity>> =
        dao.observeContinueWatchingParents()

    suspend fun upsert(entry: WatchHistoryEntity) {
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueueHistory(entry)
        dao.upsert(entry)
    }

    suspend fun getById(id: String): WatchHistoryEntity? = dao.getById(id)

    /**
     * One-shot suspend read of the Continue Watching parent rows. Backs
     * HomeViewModel's instant Continue Watching seed (cold-start fast path)
     * so the rail can render before the enriched pipeline finishes.
     */
    suspend fun getContinueWatchingParentsSnapshot(): List<WatchHistoryEntity> =
        dao.getContinueWatchingParentsSnapshot()

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
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
        dao.deleteCompletedForParentSeason(
            parentId = parentId,
            season = season
        )
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
        dao.deleteCompletedForParentSeasonEpisode(
            parentId = parentId,
            season = season,
            episode = episode
        )
    }

    /**
     * Removes every in-progress (resume) row for a parent show/movie so it
     * disappears from Continue Watching, while preserving completed-episode
     * history used for watched badges and episode counts.
     */
    suspend fun deleteResumeRowsForParent(parentId: String) {
        dao.deleteResumeRowsForParent(parentId)
    }

    /**
     * Every row with a saved resume position — used to rebuild the TV
     * launcher Continue Watching rail and for backup/restore.
     */
    suspend fun getAll(): List<WatchHistoryEntity> = dao.getAll()

    suspend fun clearAll() {
        dao.clearAll()
    }
}
