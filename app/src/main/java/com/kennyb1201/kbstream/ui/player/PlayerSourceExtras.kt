package com.kennyb1201.kbstream.ui.player

import android.util.Log
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import com.kennyb1201.kbstream.data.badges.StreamBadge
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ranked source list a session was launched with, shared by both engines.
 *
 * The launch extras carry the whole list as `sources_json` (see the player
 * intent in MainActivity), so the SOURCES picker can offer the same rows - the
 * same labels, the same badge chips, the same "which one is playing" mark -
 * and a source switch means the same thing whichever engine is on screen.
 * Reading that payload in one place is what keeps the two pickers identical.
 */

/**
 * The label the SOURCES picker and the settings panel show for one stream.
 *
 * Named rather than sharing the main player's own `Stream.displayLabel`, which
 * is file-private to that activity; the two must produce the same string, and
 * the shape below is that one verbatim.
 */
internal fun Stream.sourceLabel(): String = listOfNotNull(
    name?.takeIf { it.isNotBlank() },
    title?.takeIf { it.isNotBlank() },
    description?.takeIf { it.isNotBlank() }
).distinct().joinToString(" • ").ifBlank {
    url?.substringAfterLast('/').orEmpty().substringBefore('?').takeIf { it.isNotBlank() }
        ?: "Current source"
}

/**
 * The stream to fall back to when a caller launched the player with only a
 * `stream_url` (or with a list that does not contain it): the session must
 * always have at least the source it is playing, so the picker can mark it.
 */
internal fun currentSourceStream(
    url: String,
    audioUrl: String?,
    label: String? = null
): Stream = Stream(
    name = label?.takeIf { it.isNotBlank() } ?: "Current source",
    title = null,
    url = url,
    audioUrl = audioUrl
)

/** Badge chips for one stream, from the sources_json payload. */
internal fun parseSourceBadges(array: JSONArray?): List<StreamBadge> {
    if (array == null || array.length() == 0) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name", "")
        val imageURL = obj.optString("imageURL", "")
        if (name.isBlank() && imageURL.isBlank()) return@mapNotNull null
        StreamBadge(
            name = name,
            imageURL = imageURL,
            tagColor = obj.optString("tagColor", ""),
            tagStyle = obj.optString("tagStyle", ""),
            textColor = obj.optString("textColor", ""),
            borderColor = obj.optString("borderColor", "")
        )
    }
}

/**
 * Parses the `sources_json` extra into the ranked stream list.
 *
 * A blank or malformed payload yields an empty list rather than an exception:
 * the session still has the stream it was launched with, and [withCurrentSource]
 * puts that one back at the front.
 */
internal fun parseSourcesJson(raw: String?): List<Stream> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            obj.toStream()
        }.filter { !it.url.isNullOrBlank() }
    } catch (e: Exception) {
        Log.w("PLAYER_SOURCES", "Failed to parse sources_json", e)
        emptyList()
    }
}

/**
 * The parsed list with the playing stream guaranteed to be on it.
 *
 * Sits at the front, where it landed when the list was ranked, when the payload
 * did not carry it - e.g. a session launched with a bare `stream_url`, or one
 * whose stream was resolved after the list was built.
 */
internal fun List<Stream>.withCurrentSource(current: Stream): List<Stream> =
    if (any { it.url == current.url }) this else listOf(current) + this

/**
 * One row of the payload.
 *
 * `optString(key, "").ifBlank { null }` rather than a null fallback:
 * JSONObject's fallback parameter is @NonNull, so a null fallback makes Kotlin
 * infer a non-null result while a missing key / JSON null actually yields null.
 */
private fun JSONObject.toStream(): Stream = Stream(
    name = optString("name", "").ifBlank { null },
    title = optString("title", "").ifBlank { null },
    description = optString("description", "").ifBlank { null },
    url = optString("url", "").ifBlank { null },
    audioUrl = optString("audioUrl", "").ifBlank { null },
    infoHash = optString("infoHash", "").ifBlank { null },
    fileIdx = optInt("fileIdx", -1).takeIf { it >= 0 },
    behaviorHints = StreamBehaviorHints(
        bingeGroup = optString("bingeGroup", "").ifBlank { null }
    ),
    badges = parseSourceBadges(optJSONArray("badges"))
)
