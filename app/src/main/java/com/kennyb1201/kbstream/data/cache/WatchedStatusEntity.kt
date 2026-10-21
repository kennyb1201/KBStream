package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

// (audit P9) One row per title ever watched-statused, and the cache prune
// deletes by updatedAt - without an index that is a full scan of the table.
@Entity(
    tableName = "watched_status_cache",
    indices = [androidx.room.Index(value = ["updatedAt"])]
)
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
