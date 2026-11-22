package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


/**
 * One program in a guide.
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
        ),
        // The diagnostics' newest-program-end read and the "recent" guide read
        // filter on end time, but the composite above leads with start time and
        // cannot seek on it. On a 129MB guide, MAX(endUtcMillis) over a source
        // was a full source scan; this is a covering seek. Added in schema v9
        // with a real migration, not the destructive fallback.
        Index(value = ["sourceId", "endUtcMillis"])
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
