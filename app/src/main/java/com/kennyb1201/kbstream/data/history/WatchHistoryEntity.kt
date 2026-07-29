package com.kennyb1201.kbstream.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val streamUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)
