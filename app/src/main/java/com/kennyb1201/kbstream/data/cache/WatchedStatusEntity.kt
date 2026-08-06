package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_status_cache")
data class WatchedStatusEntity(
    @PrimaryKey val key: String,
    val imdbId: String,
    val mediaType: String,
    val isWatched: Boolean,
    val updatedAt: Long
)
