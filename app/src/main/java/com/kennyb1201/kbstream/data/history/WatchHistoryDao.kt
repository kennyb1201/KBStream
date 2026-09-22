package com.kennyb1201.kbstream.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Upsert
    suspend fun upsertRaw(entry: WatchHistoryEntity)

    /**
     * Merge a playback row without allowing a partial caller to erase
     * metadata already stored for the same title/episode.
     */
    @androidx.room.Transaction
    suspend fun upsert(entry: WatchHistoryEntity) {
        val existing = getById(entry.id)
        upsertRaw(
            entry.copy(
                name = entry.name.ifBlank { existing?.name.orEmpty() },
                episodeTitle = entry.episodeTitle ?: existing?.episodeTitle,
                overview = entry.overview ?: existing?.overview,
                clearLogo = entry.clearLogo ?: existing?.clearLogo,
                backdropUrl = entry.backdropUrl ?: existing?.backdropUrl,
                totalEpisodesInSeason =
                    entry.totalEpisodesInSeason ?: existing?.totalEpisodesInSeason,
                poster = entry.poster ?: existing?.poster,
                streamUrl = entry.streamUrl ?: existing?.streamUrl,
                season = entry.season ?: existing?.season,
                episode = entry.episode ?: existing?.episode,
                episodeStreamId = entry.episodeStreamId ?: existing?.episodeStreamId
            )
        )
    }

    /**
     * Bulk form of [upsert] in ONE transaction: a whole-series "mark watched"
     * writes a row per episode, and hundreds of separate transactions is both
     * slow and a lot of churn on the SQLite writer.
     */
    @androidx.room.Transaction
    suspend fun upsertAll(entries: List<WatchHistoryEntity>) {
        entries.forEach { entry ->
            upsert(entry)
        }
    }

    @Query("SELECT * FROM watch_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WatchHistoryEntity?

    /**
     * Batch variant of [getById] for sync merges: one query for the whole
     * remote batch instead of one per row (N+1 made a large history pull
     * issue thousands of individual lookups on every sync).
     */
    @Query("SELECT * FROM watch_history WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<WatchHistoryEntity>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getResumeForParent(parentId: String): WatchHistoryEntity?

    /**
     * Every in-progress row for a parent, newest first. The detail screen
     * maps these by episodeStreamId so every episode card can show its own
     * progress bar / time left — [getResumeForParent] only carries the most
     * recent one, which left the other in-progress episodes without progress.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getInProgressForParent(parentId: String): List<WatchHistoryEntity>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND isCompleted = 1
        ORDER BY season ASC, episode ASC, updatedAt DESC
        """
    )
    suspend fun getCompletedForParent(parentId: String): List<WatchHistoryEntity>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE positionMs > 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getAll(): List<WatchHistoryEntity>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    fun observeRecent(): Flow<List<WatchHistoryEntity>>

    @Query(
    """
    SELECT * FROM watch_history
    WHERE positionMs > 0
      AND isCompleted = 0
      AND updatedAt IN (
          SELECT MAX(updatedAt)
          FROM watch_history
          WHERE positionMs > 0
            AND isCompleted = 0
          GROUP BY parentId
      )
    ORDER BY updatedAt DESC
    """
)
fun observeContinueWatchingParents(): Flow<List<WatchHistoryEntity>>

    /**
     * One-shot suspend snapshot of [observeContinueWatchingParents]. Used by
     * HomeViewModel's instant Continue Watching seed so the rail can render
     * from local rows immediately instead of waiting for the enrichment
     * pipeline or a Simkl round-trip.
     */
    @Query(
    """
    SELECT * FROM watch_history
    WHERE positionMs > 0
      AND isCompleted = 0
      AND updatedAt IN (
          SELECT MAX(updatedAt)
          FROM watch_history
          WHERE positionMs > 0
            AND isCompleted = 0
          GROUP BY parentId
      )
    ORDER BY updatedAt DESC
    """
)
suspend fun getContinueWatchingParentsSnapshot(): List<WatchHistoryEntity>

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND positionMs > 0
          AND isCompleted = 0
        """
    )
    suspend fun deleteResumeRowsForParent(parentId: String)

    @Query("UPDATE watch_history SET backdropUrl = :backdropUrl WHERE id = :id AND (backdropUrl IS NULL OR backdropUrl = '')")
    suspend fun updateBackdropIfMissing(id: String, backdropUrl: String)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND season = :season
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentSeason(parentId: String, season: Int)

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND season = :season
          AND episode = :episode
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentSeasonEpisode(
        parentId: String,
        season: Int,
        episode: Int
    )

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParent(parentId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
