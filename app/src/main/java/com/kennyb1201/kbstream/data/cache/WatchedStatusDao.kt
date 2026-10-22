package com.kennyb1201.kbstream.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WatchedStatusDao {
    /**
     * Batch lookup, chunked to SQLite's bind-variable ceiling.
     *
     * The sync pull hands this every key a page of the CLOUD table returned, so
     * the list is as long as the account is large; over the limit the statement
     * throws and the pull reports the table unsynced, which is a silent
     * never-syncs for that device rather than a visible error.
     */
    suspend fun getByKeys(keys: List<String>): List<WatchedStatusEntity> =
        keys.chunked(com.kennyb1201.kbstream.data.db.SQLITE_MAX_QUERY_VARIABLES)
            .flatMap { getByKeysPage(it) }

    @Query("SELECT * FROM watched_status_cache WHERE key IN (:keys)")
    suspend fun getByKeysPage(keys: List<String>): List<WatchedStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WatchedStatusEntity>)

    @Query(
        """
        SELECT imdbId, mediaType
        FROM watched_status_cache
        WHERE imdbId IS NOT NULL
          AND imdbId != ''
        GROUP BY imdbId, mediaType
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getRefreshTargets(): List<WatchedRefreshTarget>

    @Query("SELECT * FROM watched_status_cache")
    suspend fun getAll(): List<WatchedStatusEntity>

    @Query("DELETE FROM watched_status_cache WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM watched_status_cache WHERE updatedAt < :minUpdatedAt")
    suspend fun deleteOlderThan(minUpdatedAt: Long)

    @Query("DELETE FROM watched_status_cache")
    suspend fun clearAll()
}
