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
 * The file name this stream is named by, as the episode-scheme detector reads
 * it (see [com.kennyb1201.kbstream.data.player.EpisodeScheme.detect]).
 *
 * The addon's `title` is the release name ("Paw Patrol S01E01E02 1080p"), the
 * `name` is usually the addon's own, and `description` sometimes carries the
 * name again - all three are candidates, joined so one string holds every
 * signal the row has. The URL's last path segment is the fallback for a source
 * launched with only a URL and no addon row: a direct link usually ends in the
 * file name. A magnet's segment holds no `S<season>E<episode>` shape, so it
 * cannot match anything.
 *
 * Null when nothing was named at all, which the detector reads as "no filename
 * signal" rather than as an empty name to search.
 */
internal fun Stream.detectionFileName(): String? {
    val named = listOfNotNull(name, title, description)
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val fromUrl = url
        ?.substringAfterLast('/')
        ?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }
    return (named + listOfNotNull(fromUrl)).joinToString(" ").ifBlank { null }
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
 * The `source_addons` extra: each source's addon name, in the same order as
 * `sources_json`.
 *
 * The parallel array the in-session demotion needs (see
 * [SourceAddonSession]): the players advance over `sources: List<Stream>`, but
 * which addon each candidate came from is known only where the list was built.
 * `JSONObject.NULL` (rather than an empty string) marks an entry the writer did
 * not know, so a missing name and a blank name are both read back as null.
 */
internal fun sourceAddonsJson(addons: List<String?>): String {
    val arr = JSONArray()
    addons.forEach { name ->
        if (name.isNullOrBlank()) arr.put(JSONObject.NULL) else arr.put(name)
    }
    return arr.toString()
}

/**
 * Parses the `source_addons` extra into a list parallel to `sources_json`.
 *
 * Absent, blank or malformed payloads yield an empty list rather than an
 * exception: missing names degrade to "unknown addon", which is never skipped
 * and never demoted. Length is preserved so index `i` here lines up with index
 * `i` of [parseSourcesJson].
 */
internal fun parseSourceAddonsJson(raw: String?): List<String?> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            if (arr.isNull(i)) null else arr.optString(i).ifBlank { null }
        }
    } catch (e: Exception) {
        Log.w("PLAYER_SOURCES", "Failed to parse source_addons", e)
        emptyList()
    }
}

/**
 * The addon name for each stream in [streams], given the payload the list
 * arrived with.
 *
 * [parsed] is the list as read from `sources_json` and [addons] the parallel
 * `source_addons` array. A player may re-rank the parsed list (stable, so the
 * order usually survives) and may prepend the playing stream when the payload
 * did not carry it; resolving by stream identity rather than by index keeps the
 * names attached to the right rows either way. A stream the payload did not
 * describe, or an absent/short array, answers null - unknown, never skipped.
 */
internal fun addonsFor(
    streams: List<Stream>,
    parsed: List<Stream>,
    addons: List<String?>
): List<String?> {
    val lookup = addonLookup(parsed, addons)
    return streams.map(lookup)
}

/**
 * [addonsFor]'s lookup as a function, for a caller that needs the add-on
 * *while* ordering the list rather than beside it.
 *
 * The player re-ranks the payload it was handed, and the ranker reads the
 * add-on behind each row for its debrid-backed tier (see
 * [com.kennyb1201.kbstream.domain.streamengine.StreamRanker.rank]). Reading it
 * from the same map the parallel array is resolved with is what keeps "whose
 * row is this" one answer.
 */
internal fun addonLookup(
    parsed: List<Stream>,
    addons: List<String?>
): (Stream) -> String? {
    if (addons.isEmpty()) return { null }
    val byStream = HashMap<Stream, String?>()
    parsed.forEachIndexed { index, stream ->
        if (!byStream.containsKey(stream)) byStream[stream] = addons.getOrNull(index)
    }
    return { stream -> byStream[stream] }
}

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
    // This source's own HTTP headers. Absent for every payload written before
    // the field existed, and absent is the empty map the player already treats
    // as "no headers" - so an older tree's extras still parse to what they did.
    headers = optJSONObject("headers")
        ?.let { obj -> obj.keys().asSequence().associateWith { key -> obj.optString(key, "") } }
        ?.takeIf { it.isNotEmpty() },
    fileIdx = optInt("fileIdx", -1).takeIf { it >= 0 },
    behaviorHints = StreamBehaviorHints(
        bingeGroup = optString("bingeGroup", "").ifBlank { null }
    ),
    badges = parseSourceBadges(optJSONArray("badges"))
)

/**
 * Parses the `cast_json` extra into the cast band's members.
 *
 * The same payload the main player renders ([PlayerCastMember], built by
 * MainActivity), read through one helper so both engines show the same people
 * in the same order. A blank or malformed payload yields an empty list - the
 * band then hides itself - rather than an exception.
 */
internal fun parseCastJson(raw: String?): List<PlayerCastMember> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            PlayerCastMember(
                id = obj.optInt("id", 0),
                name = obj.optString("name", ""),
                character = obj.optString("character", "").ifBlank { null },
                profilePath = obj.optString("profilePath", "").ifBlank { null }
            )
        }.filter { it.name.isNotBlank() }
    } catch (e: Exception) {
        Log.w("PLAYER_CAST", "Failed to parse cast_json", e)
        emptyList()
    }
}

/**
 * The TMDB profile image URL for a cast member, tolerating a stored path
 * (`/abc.jpg`), a bare filename, or an already-absolute URL - the same three
 * shapes the main player's band accepts.
 */
internal fun PlayerCastMember.profileImageUrl(): String? = profilePath
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.let { path ->
        when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> "https://image.tmdb.org/t/p/w185$path"
            else -> "https://image.tmdb.org/t/p/w185/$path"
        }
    }
