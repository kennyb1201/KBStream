package com.kennyb1201.kbstream.data.history

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class WatchHistoryRepository(context: Context) {
    private val dao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 1. Hot StateFlow for all recent watch history items
    val recentHistory: StateFlow<List<WatchHistoryEntity>> = dao.observeRecent()
        .stateIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 2. Hot StateFlow for Continue Watching parent items (ideal for Home and Up Next rails)
    val continueWatchingParents: StateFlow<List<WatchHistoryEntity>> = dao.observeContinueWatchingParents()
        .stateIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    suspend fun upsert(entry: WatchHistoryEntity) {
        dao.upsert(entry)
    }

    suspend fun getById(id: String): WatchHistoryEntity? = dao.getById(id)

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
     * Removes every in-progress (resume) row for a parent show/movie so it
     * disappears from Continue Watching, while preserving completed-episode
     * history used for watched badges and episode counts.
     */
    suspend fun deleteResumeRowsForParent(parentId: String) {
        dao.deleteResumeRowsForParent(parentId)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
