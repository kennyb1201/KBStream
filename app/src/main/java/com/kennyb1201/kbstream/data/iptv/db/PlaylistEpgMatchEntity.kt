package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_epg_matches",
    primaryKeys = ["playlistUrl", "playlistChannelId", "epgUrl"],
    indices = [
        Index(value = ["playlistUrl"]),
        Index(value = ["epgUrl", "epgChannelId"])
    ]
)
data class PlaylistEpgMatchEntity(
    val playlistUrl: String,
    val playlistChannelId: String,
    val epgUrl: String,
    val epgChannelId: String?,
    val matchType: String,
    val updatedAtUtcMillis: Long
)
