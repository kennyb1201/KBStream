package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["channelId", "startUtcMillis"]),
        Index(value = ["channelId", "endUtcMillis"])
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
