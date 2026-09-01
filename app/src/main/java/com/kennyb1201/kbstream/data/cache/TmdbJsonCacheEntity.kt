package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tmdb_json_cache")
data class TmdbJsonCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long
)
