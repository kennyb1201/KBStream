package com.kennyb1201.kbstream.data.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * The snapshot's on-disk form (spec 2026-11-13, F3).
 *
 * Hand-rolled and tiny on purpose: one rendered row per rail card, written whole
 * and read whole. A serializer dependency would be a bigger surface than the document,
 * and a decode that fails MUST return null rather than a half-filled list - the
 * widget then draws its fail-soft state instead of a row that cannot be resumed.
 *
 * Unknown keys are ignored, so adding a field later is a one-line change on both
 * sides. A missing key means "this build did not have it", never "zero".
 */
internal object CwWidgetSnapshotJson {

    fun encode(snapshot: CwWidgetSnapshot): String {
        val array = JSONArray()
        snapshot.rows.forEach { row ->
            array.put(
                JSONObject()
                    .put("pt", row.parentType)
                    .put("pid", row.parentId)
                    .put("sid", row.streamId ?: "")
                    .put("t", row.showTitle)
                    .put("chip", row.chip ?: "")
                    .put("et", row.episodeTitle ?: "")
                    .put("s", row.season ?: -1)
                    .put("e", row.episode ?: -1)
                    .put("p", row.posterUrl ?: "")
                    .put("pos", row.positionMs)
                    .put("frac", row.progressPercent?.toDouble() ?: -1.0)
                    .put("left", row.remainingMinutes ?: -1)
                    .put("dur", row.durationMs)
                    .put("at", row.updatedAtMs)
                    // Spec 2026-11-16: what the row IS (a resume or an offer),
                    // and the identity spellings a rebuild re-filters it by.
                    .put("res", row.isResume)
                    .put("cid", row.cardId)
                    .put("tmdb", row.tmdbId ?: -1)
                    .put("hr", row.historyRowId ?: "")
                    .put("air", row.ownEpisodeAirEpochDay ?: -1L)
            )
        }
        return JSONObject()
            .put("profile", snapshot.profileId ?: "")
            .put("written", snapshot.writtenAtMs)
            .put("rows", array)
            .toString()
    }

    /** Anything unreadable decodes to null - which the widget draws as "open the app", never as empty. */
    fun decode(raw: String?): CwWidgetSnapshot? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            val root = JSONObject(text)
            val array = root.optJSONArray("rows") ?: return null
            val rows = ArrayList<CwWidgetRow>(array.length())
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                val parentId = node.optString("pid").trim()
                if (parentId.isEmpty()) continue
                rows += CwWidgetRow(
                    parentType = node.optString("pt").trim().ifEmpty { "series" },
                    parentId = parentId,
                    streamId = node.optString("sid").trim().takeIf { it.isNotEmpty() },
                    showTitle = node.optString("t").trim(),
                    // Absent in a snapshot an older build wrote: no chip, not an
                    // empty one (the row hides the view).
                    chip = node.optString("chip").trim().takeIf { it.isNotEmpty() },
                    episodeTitle = node.optString("et").trim().takeIf { it.isNotEmpty() },
                    season = node.optInt("s", -1).takeIf { it >= 0 },
                    episode = node.optInt("e", -1).takeIf { it > 0 },
                    posterUrl = node.optString("p").trim().takeIf { it.isNotEmpty() },
                    positionMs = node.optLong("pos", 0L).coerceAtLeast(0L),
                    progressPercent = node.optDouble("frac", -1.0)
                        .takeIf { !it.isNaN() && it >= 0.0 }
                        ?.toFloat(),
                    remainingMinutes = node.optInt("left", -1).takeIf { it >= 0 },
                    durationMs = node.optLong("dur", 0L).coerceAtLeast(0L),
                    updatedAtMs = node.optLong("at", 0L),
                    // Absent in a snapshot an older build wrote, which drew a
                    // resume row until the next publish rewrote it.
                    isResume = node.optBoolean("res", true),
                    cardId = node.optString("cid").trim(),
                    tmdbId = node.optInt("tmdb", -1).takeIf { it > 0 },
                    historyRowId = node.optString("hr").trim().takeIf { it.isNotEmpty() },
                    ownEpisodeAirEpochDay = node.optLong("air", -1L).takeIf { it >= 0L }
                )
            }
            CwWidgetSnapshot(
                profileId = root.optString("profile").trim().takeIf { it.isNotEmpty() },
                writtenAtMs = root.optLong("written", 0L),
                rows = rows
            )
        }.getOrNull()
    }
}
