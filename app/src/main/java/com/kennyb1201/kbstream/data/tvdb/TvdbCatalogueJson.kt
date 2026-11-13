package com.kennyb1201.kbstream.data.tvdb

import org.json.JSONArray
import org.json.JSONObject

/**
 * A catalogue in the form [TvdbOrderStore] keeps on disk.
 *
 * One row per episode as `[orderSeason, orderEpisode, tmdbSeason, tmdbEpisode,
 * aired]`, with `-1` for a TMDB side the join could not make. Flat arrays rather
 * than named objects because this is read on every season-list cache miss and
 * written once per order change; the shape never leaves this file.
 *
 * Anything unreadable decodes to NULL, never to an empty catalogue: an empty one
 * would read as a real order that happens to renumber nothing, and that is a
 * different, wrong statement.
 */
internal fun TvdbCatalogue.encode(): String {
    val rows = JSONArray()
    episodes.forEach { episode ->
        rows.put(
            JSONArray().apply {
                put(episode.orderSeason)
                put(episode.orderEpisode)
                put(episode.tmdbSeason ?: -1)
                put(episode.tmdbEpisode ?: -1)
                put(episode.airDate ?: "")
            }
        )
    }
    return JSONObject()
        .put("type", seasonType)
        .put("at", builtAtMs)
        .put("episodes", rows)
        .toString()
}

internal fun decodeCatalogue(raw: String?): TvdbCatalogue? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching {
        val root = JSONObject(text)
        val type = root.optString("type").trim()
        if (type.isEmpty()) return null
        val rows = root.optJSONArray("episodes") ?: return null
        val episodes = ArrayList<TvdbOrderEpisode>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            if (row.length() < 5) continue
            val orderSeason = row.optInt(0, 0)
            val orderEpisode = row.optInt(1, 0)
            if (orderSeason <= 0 || orderEpisode <= 0) continue
            episodes += TvdbOrderEpisode(
                orderSeason = orderSeason,
                orderEpisode = orderEpisode,
                tmdbSeason = row.optInt(2, -1).takeIf { it > 0 },
                tmdbEpisode = row.optInt(3, -1).takeIf { it > 0 },
                airDate = row.optString(4).takeIf { it.isNotBlank() }
            )
        }
        TvdbCatalogue(type, root.optLong("at", 0L), episodes)
    }.getOrNull()
}
