package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "epg_channels",
    primaryKeys = ["sourceUrl", "id"],
    indices = [
        Index(value = ["sourceUrl"]),
        Index(value = ["id"])
    ]
)
data class EpgChannelEntity(
    val id: String,
    val sourceUrl: String,
    val primaryDisplayName: String,
    val allDisplayNames: String,
    val iconUrl: String?
)
