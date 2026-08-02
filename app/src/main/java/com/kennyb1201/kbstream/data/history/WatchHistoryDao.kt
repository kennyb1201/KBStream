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

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT 20")
    suspend fun getRecent(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WatchHistoryEntity?
}
