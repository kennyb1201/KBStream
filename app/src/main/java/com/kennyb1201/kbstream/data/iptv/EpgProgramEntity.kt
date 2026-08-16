package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["sourceUrl"]),
        Index(value = ["channelId", "startUtcMillis"]),
        Index(value = ["channelId", "endUtcMillis"]),
        Index(value = ["sourceUrl", "channelId", "startUtcMillis"]),
        Index(value = ["sourceUrl", "channelId", "endUtcMillis"]),
        Index(value = ["startUtcMillis"]),
        Index(value = ["endUtcMillis"])
    ]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceUrl: String,
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long
)
