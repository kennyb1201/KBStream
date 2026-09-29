package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index

/**
 * A channel as the guide's own XMLTV file describes it, keyed by
 * ([sourceId], [id]).
 *
 * [sourceId] replaces the source URL that used to be the first half of this
 * primary key — see [EpgSourceEntity] for why the URL no longer appears on
 * guide rows. The `id` index stays: the playlist matcher looks guide channels
 * up by the provider's id alone, across sources.
 */
@Entity(
    tableName = "epg_channels",
    primaryKeys = ["sourceId", "id"],
    indices = [
        Index(value = ["id"])
    ]
)
data class EpgChannelEntity(
    val id: String,
    val sourceId: Long,
    val primaryDisplayName: String,
    val allDisplayNames: String,
    val iconUrl: String?
)
