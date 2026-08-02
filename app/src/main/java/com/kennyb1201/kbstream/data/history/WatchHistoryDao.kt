package com.kennyb1201.kbstream.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("""
        SELECT * FROM watch_history
        ORDER BY updatedAt DESC
        LIMIT 20
    """)
    fun observeRecent(): Flow<List<WatchHistoryEntity>>

    @Query("""
        SELECT * FROM watch_history
        ORDER BY updatedAt DESC
        LIMIT 20
    """)
    suspend fun getRecent(): List<WatchHistoryEntity>

    @Query("""
        SELECT * FROM watch_history
        WHERE id = :id
        LIMIT 1
    """)
    suspend fun getById(id: String): WatchHistoryEntity?

    @Query("""
        SELECT * FROM watch_history
        WHERE parentId = :parentId
        ORDER BY updatedAt DESC
    """)
    suspend fun getAllForParent(parentId: String): List<WatchHistoryEntity>

    @Query("""
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND isCompleted = 0
          AND positionMs > 0
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    suspend fun getResumeForParent(parentId: String): WatchHistoryEntity?

    @Query("""
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND isCompleted = 1
        ORDER BY season ASC, episode ASC, updatedAt DESC
    """)
    suspend fun getCompletedForParent(parentId: String): List<WatchHistoryEntity>

    @Query("""
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND season = :season
          AND episode = :episode
        LIMIT 1
    """)
    suspend fun getEpisode(
        parentId: String,
        season: Int,
        episode: Int
    ): WatchHistoryEntity?

    @Query("""
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND episodeStreamId = :episodeStreamId
        LIMIT 1
    """)
    suspend fun getEpisodeByStreamId(
        parentId: String,
        episodeStreamId: String
    ): WatchHistoryEntity?

    @Query("""
        DELETE FROM watch_history
        WHERE parentId = :parentId
    """)
    suspend fun deleteForParent(parentId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
