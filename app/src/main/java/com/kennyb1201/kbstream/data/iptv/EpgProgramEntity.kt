package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


/**
 * One programme in a guide.
 *
 * [sourceId] points at [EpgSourceEntity] rather than carrying the guide URL.
 * The URL is a long string that used to sit on every row AND lead the composite
 * index below; see [EpgSourceEntity] for what that cost.
 *
 * [channelId] stays a string because it is the provider's own key and the one
 * the guide is queried by (`epgProgramChannelKey` normalizes it, and the
 * playlist match table is keyed the same way), so it appears in the table and
 * in the index as before.
 */
@Entity(
    tableName = "epg_programs",
    indices = [
        Index(
            value = [
                "sourceId",
                "channelId",
                "startUtcMillis",
                "endUtcMillis"
            ]
        )
    ]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceId: Long,
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long
)
