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

    @Query("SELECT * FROM watch_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WatchHistoryEntity?

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

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
