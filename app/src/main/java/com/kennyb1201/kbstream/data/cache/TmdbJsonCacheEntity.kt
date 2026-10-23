package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One cached JSON payload, as shipped by every service that keeps a
 * take-it-or-leave-it blob (TMDB, OMDb, MDBList, TVmaze).
 *
 * [updatedAt] is indexed because BOTH reads on this table filter by it - the
 * expiry scan and the trim - and it was the one cache table the v15 index pass
 * left out, so those scans stayed full-table as the cache grew.
 */
@Entity(
    tableName = "tmdb_json_cache",
    indices = [Index("updatedAt")]
)
data class TmdbJsonCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long
)
