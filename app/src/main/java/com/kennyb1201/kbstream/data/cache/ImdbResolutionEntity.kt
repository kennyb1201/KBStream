package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

// (audit P9) The table is one row per title ever resolved, and it had no index
// at all: the detail-resolution path looks titles up by (imdbId, mediaType)
// while the cache prune scans by updatedAt, so both walked every row.
@Entity(
    tableName = "imdb_resolution_cache",
    indices = [
        androidx.room.Index(value = ["imdbId", "mediaType"]),
        androidx.room.Index(value = ["updatedAt"])
    ]
)
data class ImdbResolutionEntity(
    @PrimaryKey val key: String,
    val tmdbId: Int,
    val mediaType: String,
    val imdbId: String,
    val updatedAt: Long
)
