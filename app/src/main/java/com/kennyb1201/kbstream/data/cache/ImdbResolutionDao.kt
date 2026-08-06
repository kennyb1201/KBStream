package com.kennyb1201.kbstream.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImdbResolutionDao {
    @Query("SELECT * FROM imdb_resolution_cache WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): ImdbResolutionEntity?

    @Query("SELECT * FROM imdb_resolution_cache WHERE key IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<ImdbResolutionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ImdbResolutionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ImdbResolutionEntity>)

    @Query("DELETE FROM imdb_resolution_cache WHERE updatedAt < :minUpdatedAt")
    suspend fun deleteOlderThan(minUpdatedAt: Long)
}
