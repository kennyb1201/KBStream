package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One row of any of the three sync tables (history / watched / prefs) as the
 * wire sees it. Previously a private nested class inside [SupabaseSync]; it
 * lives here so the sync pieces that were split out of that object can share
 * it without reaching into SupabaseSync.
 */
@Serializable
internal data class SyncRowDto(
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("item_key") val itemKey: String? = null,
    @SerialName("pref_key") val prefKey: String? = null,
    val payload: JsonObject,
    @SerialName("updated_at") val updatedAt: String = ""
)
