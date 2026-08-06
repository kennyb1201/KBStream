package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "imdb_resolution_cache")
data class ImdbResolutionEntity(
    @PrimaryKey val key: String,
    val tmdbId: Int,
    val mediaType: String,
    val imdbId: String,
    val updatedAt: Long
)
