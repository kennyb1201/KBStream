package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_status_cache")
data class WatchedStatusEntity(
    @PrimaryKey val key: String,
    val imdbId: String,
    val mediaType: String,
    val isWatched: Boolean,
    // Shows the user has started but not finished (Simkl: watchedEpisodesCount > 0
    // while the show isn't fully watched; local: an in-progress episode exists and
    // the show isn't completed). Renders the eye badge instead of the checkmark.
    // Defaults false so every existing named-arg constructor and JSON round-trip
    // (backup restore, Supabase pull) keeps compiling unchanged.
    val isPartiallyWatched: Boolean = false,
    val updatedAt: Long
)
