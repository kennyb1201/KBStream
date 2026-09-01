package com.kennyb1201.kbstream.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TmdbJsonCacheDao {
    @Query("SELECT * FROM tmdb_json_cache WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): TmdbJsonCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TmdbJsonCacheEntity)

    @Query("DELETE FROM tmdb_json_cache WHERE updatedAt < :minUpdatedAt")
    suspend fun deleteOlderThan(minUpdatedAt: Long)

    @Query("DELETE FROM tmdb_json_cache WHERE key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
}
