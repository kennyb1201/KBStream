package com.kennyb1201.kbstream.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val type: String,
    val name: String,
    val episodeTitle: String? = null,
    val overview: String? = null,
    val clearLogo: String? = null,
    val backdropUrl: String? = null,
    val totalEpisodesInSeason: Int? = null,
    val poster: String?,
    val streamUrl: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeStreamId: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null
)
