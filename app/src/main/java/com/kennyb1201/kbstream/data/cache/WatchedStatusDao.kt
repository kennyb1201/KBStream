package com.kennyb1201.kbstream.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WatchedStatusDao {
    @Query("SELECT * FROM watched_status_cache WHERE key IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<WatchedStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WatchedStatusEntity>)

    @Query("DELETE FROM watched_status_cache WHERE updatedAt < :minUpdatedAt")
    suspend fun deleteOlderThan(minUpdatedAt: Long)
}
