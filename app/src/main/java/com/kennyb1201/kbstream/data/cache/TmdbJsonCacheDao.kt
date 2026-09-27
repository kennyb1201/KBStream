package com.kennyb1201.kbstream.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One row of the cache-size index: what a row costs on disk, and when it was
 * last written.
 *
 * `LENGTH(CAST(json AS BLOB))`, not `LENGTH(json)`: SQLite's LENGTH counts
 * CHARACTERS for TEXT, and this JSON is not pure ASCII (titles, overviews and
 * cast names are not), so the character count would under-report exactly the
 * rows that are largest. The BLOB cast makes the figure bytes, which is what
 * a byte budget is measured in.
 */
data class TmdbJsonCacheSizeRow(
    val key: String,
    val bytes: Long,
    val updatedAt: Long
)

@Dao
interface TmdbJsonCacheDao {
    @Query("SELECT * FROM tmdb_json_cache WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): TmdbJsonCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TmdbJsonCacheEntity)

    /** Returns the number of rows deleted; the maintenance pass reports it. */
    @Query("DELETE FROM tmdb_json_cache WHERE updatedAt < :minUpdatedAt")
    suspend fun deleteOlderThan(minUpdatedAt: Long): Int

    @Query("DELETE FROM tmdb_json_cache WHERE key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    /**
     * Every row's key, on-disk size and write time — what
     * [jsonCacheEvictions] needs to decide who has to go.
     *
     * Deliberately unbounded: the budget is a byte total, so the decision
     * needs the whole set. It stays affordable because the columns are two
     * short ones and never the JSON itself, and because
     * [TmdbJsonCacheMaintenance.MAX_ROWS] keeps the row count from running
     * away — which matters, since this runs from a write path.
     */
    @Query("SELECT key, LENGTH(CAST(json AS BLOB)) AS bytes, updatedAt FROM tmdb_json_cache")
    suspend fun sizeIndex(): List<TmdbJsonCacheSizeRow>

    @Query("SELECT COUNT(*) FROM tmdb_json_cache")
    suspend fun count(): Int

    /** Total payload bytes, or null when the table is empty (SUM over no rows). */
    @Query("SELECT SUM(LENGTH(CAST(json AS BLOB))) FROM tmdb_json_cache")
    suspend fun totalBytes(): Long?
}
