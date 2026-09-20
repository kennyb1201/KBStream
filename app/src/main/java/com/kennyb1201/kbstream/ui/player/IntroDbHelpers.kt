package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class IntroDbMarkerType(val buttonLabel: String) {
    Intro("SKIP INTRO"), Recap("SKIP RECAP"), Outro("SKIP OUTRO"),
    Credits("SKIP CREDITS"), Preview("SKIP PREVIEW"),

    /**
     * The scene after the end credits. It is content, not filler — the button
     * skips *past* it (to the end of the file), and auto-skip never touches it.
     */
    PostCredits("SKIP TO END")
}

internal data class IntroDbStamp(
    val startMs: Long,
    val endMs: Long,
    val type: IntroDbMarkerType,
    /**
     * Crowd-sourced confidence when the source reports one. Null means "not
     * stated", which is treated as good enough — only a reported value below
     * [AutoSkipRules.MIN_CONFIDENCE] blocks an automatic skip.
     */
    val confidence: Double? = null
)

internal val introDbHttpClient = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

/**
 * Millisecond field. Accepts a JSON number (the API's normal shape), a numeric
 * string, or a clock string.
 */
internal fun readMillisField(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val raw = obj.opt(key)) {
        is Number -> raw.toLong()
        is String -> {
            val text = raw.trim()
            text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: parseClockToMillis(text)
        }
        else -> null
    }
}

/**
 * Seconds field. The API returns numbers today, but its documented input format
 * accepts clock strings ("00:00:02", "1:59:00") — so a client that only reads
 * numbers would silently drop a segment that comes back in that shape instead
 * of skipping it.
 */
internal fun readSecondsField(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val raw = obj.opt(key)) {
        is Number -> (raw.toDouble() * 1_000.0).toLong()
        is String -> {
            val text = raw.trim()
            text.toDoubleOrNull()?.let { (it * 1_000.0).toLong() } ?: parseClockToMillis(text)
        }
        else -> null
    }
}

/** "45" / "mm:ss" / "hh:mm:ss" → millis. Null when it is not a clock string. */
internal fun parseClockToMillis(text: String): Long? {
    val parts = text.split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    var totalSeconds = 0L
    for (part in parts) {
        val value = part.trim().toLongOrNull() ?: return null
        if (value < 0L) return null
        totalSeconds = totalSeconds * 60L + value
    }
    return totalSeconds * 1_000L
}

private fun JSONObject.readConfidence(): Double? = when (val raw = opt("confidence")) {
    is Number -> raw.toDouble()
    is String -> raw.toDoubleOrNull()
    else -> null
}

internal fun JSONObject.readIntroDbStamp(type: IntroDbMarkerType): IntroDbStamp? {
    val startMs = readMillisField(this, "start_ms")
        ?: readSecondsField(this, "start_sec")
        ?: return null
    val endMs = readMillisField(this, "end_ms")
        ?: readSecondsField(this, "end_sec")
        ?: return null
    if (startMs < 0L || endMs <= startMs) return null
    return IntroDbStamp(startMs, endMs, type, readConfidence())
}

internal fun fetchIntroDbJson(url: String): JSONObject? = runCatching {
    introDbHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
    }
}.getOrElse { null }

/**
 * Reads one `/segments` response: `intro`, `recap`, `outro`, `post_credits`
 * (the API's newer movie/scene segments). A key arrives as a single object
 * today; the array shape is accepted too so a future/bulk response cannot
 * silently blind the skip button.
 */
private fun readIntroDbSegments(root: JSONObject): List<IntroDbStamp> {
    fun one(key: String, type: IntroDbMarkerType): IntroDbStamp? =
        root.optJSONObject(key)?.readIntroDbStamp(type)
            ?: root.optJSONArray(key)?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it)?.readIntroDbStamp(type) }
                    .minByOrNull { it.startMs }
            }

    return listOfNotNull(
        one("intro", IntroDbMarkerType.Intro),
        one("recap", IntroDbMarkerType.Recap),
        one("outro", IntroDbMarkerType.Outro),
        one("credits", IntroDbMarkerType.Credits),
        one("post_credits", IntroDbMarkerType.PostCredits),
        one("post-credits", IntroDbMarkerType.PostCredits),
        one("preview", IntroDbMarkerType.Preview)
    )
}

/**
 * Segments for one episode (season + episode present) or one movie (both null —
 * how the player represents a movie).
 *
 * Movie lookups need the explicit `is_movie=true` flag: without it the API
 * answers a TV query, which is why movies previously got no skip data at all.
 * Only an IMDb id can be sent there; a numeric TMDB id falls through to the
 * secondary source, which keys off `tmdb_id`.
 */
internal suspend fun fetchIntroDbStamps(
    parentId: String,
    season: Int?,
    episode: Int?
): List<IntroDbStamp> = withContext(Dispatchers.IO) {
    val normalizedId = parentId.trim()
    val isImdbId = normalizedId.startsWith("tt") && normalizedId.drop(2).all { it.isDigit() }
    val hasEpisode = season != null && episode != null
    val encodedId = Uri.encode(normalizedId)

    if (isImdbId) {
        val url = if (hasEpisode) {
            "https://api.introdb.app/segments?imdb_id=$encodedId&season=$season&episode=$episode"
        } else {
            "https://api.introdb.app/segments?imdb_id=$encodedId&is_movie=true"
        }
        val markers = fetchIntroDbJson(url)?.let { readIntroDbSegments(it) }.orEmpty()
        if (markers.isNotEmpty()) return@withContext markers
    }

    val idQuery = when {
        isImdbId -> "imdb_id=$encodedId"
        normalizedId.toLongOrNull() != null -> "tmdb_id=$normalizedId"
        else -> return@withContext emptyList()
    }
    val episodeQuery = if (hasEpisode) "&season=$season&episode=$episode" else ""

    val media = fetchIntroDbJson("https://api.theintrodb.org/v2/media?$idQuery$episodeQuery")
        ?: return@withContext emptyList()

    fun readArray(key: String, type: IntroDbMarkerType): List<IntroDbStamp> {
        val arr = media.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.readIntroDbStamp(type) }
    }

    readArray("intro", IntroDbMarkerType.Intro) +
        readArray("recap", IntroDbMarkerType.Recap) +
        readArray("credits", IntroDbMarkerType.Credits) +
        readArray("outro", IntroDbMarkerType.Outro) +
        readArray("post_credits", IntroDbMarkerType.PostCredits) +
        readArray("post-credits", IntroDbMarkerType.PostCredits) +
        readArray("preview", IntroDbMarkerType.Preview)
}
