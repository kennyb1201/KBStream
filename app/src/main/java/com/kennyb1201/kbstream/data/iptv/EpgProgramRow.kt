package com.kennyb1201.kbstream.data.iptv.db

data class EpgProgramRow(
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long
)
