package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_channels",
    indices = [
        Index(value = ["sourceUrl"])
    ]
)
data class EpgChannelEntity(
    @PrimaryKey
    val id: String,
    val sourceUrl: String,
    val primaryDisplayName: String,
    val allDisplayNames: String,
    val iconUrl: String?
)
