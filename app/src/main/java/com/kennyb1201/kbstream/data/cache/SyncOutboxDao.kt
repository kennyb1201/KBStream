package com.kennyb1201.kbstream.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable store behind the sync outbox (see [SyncOutboxEntity]).
 *
 * Lives in the GLOBAL database ([WatchHistoryDatabase.getInstance]) rather
 * than a profile-scoped one: a pending write can belong to a profile the user
 * has since switched away from, and a profile switch must not strand it.
 */
@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox")
    suspend fun getAll(): List<SyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM sync_outbox")
    suspend fun deleteAll()
}
