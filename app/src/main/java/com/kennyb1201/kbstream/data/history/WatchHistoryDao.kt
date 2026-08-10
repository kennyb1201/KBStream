package com.kennyb1201.kbstream.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Upsert
    suspend fun upsert(entry: WatchHistoryEntity)

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

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
