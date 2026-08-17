package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity

@Entity(
    tableName = "cached_playlist_channels",
    primaryKeys = ["playlistUrl", "position"]
)
data class CachedPlaylistChannelEntity(
    val playlistUrl: String,
    val position: Int,
    val id: String,
    val name: String,
    val displayName: String,
    val streamUrl: String,
    val groupTitle: String?,
    val logoUrl: String?,
    val tvgId: String?,
    val tvgName: String?,
    val tvgChno: String?,
    val catchup: String?,
    val catchupDays: String?,
    val catchupSource: String?,
    val providerChannelId: String?,
    val headersText: String
)
