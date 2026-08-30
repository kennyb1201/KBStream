package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class IntroDbMarkerType(val buttonLabel: String) {
    Intro("SKIP INTRO"), Recap("SKIP RECAP"), Outro("SKIP OUTRO"),
    Credits("SKIP CREDITS"), Preview("SKIP PREVIEW")
}

internal data class IntroDbStamp(val startMs: Long, val endMs: Long, val type: IntroDbMarkerType)

internal val introDbHttpClient = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

internal fun JSONObject.readIntroDbStamp(type: IntroDbMarkerType): IntroDbStamp? {
    fun readMillis(millisKey: String, secondsKey: String): Long {
        val millis = optLong(millisKey, -1L)
        if (millis >= 0L) return millis
        val seconds = optDouble(secondsKey, -1.0)
        return if (seconds >= 0.0) (seconds * 1_000.0).toLong() else -1L
    }
    val startMs = readMillis("start_ms", "start_sec")
    val endMs = readMillis("end_ms", "end_sec")
    return if (startMs >= 0L && endMs > startMs) IntroDbStamp(startMs, endMs, type) else null
}

internal fun fetchIntroDbJson(url: String): JSONObject? = runCatching {
    introDbHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
    }
}.getOrElse { null }

internal suspend fun fetchIntroDbStamps(parentId: String, season: Int?, episode: Int?): List<IntroDbStamp> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val normalizedId = parentId.trim()
    val idQuery = when {
        normalizedId.startsWith("tt") && normalizedId.drop(2).all { it.isDigit() } -> "imdb_id=${Uri.encode(normalizedId)}"
        normalizedId.toLongOrNull() != null -> "tmdb_id=$normalizedId"
        else -> return@withContext emptyList()
    }
    val episodeQuery = if (season != null && episode != null) "&season=$season&episode=$episode" else ""

    if (normalizedId.startsWith("tt") && season != null && episode != null) {
        val introDbMarkers = fetchIntroDbJson(
            "https://api.introdb.app/segments?imdb_id=${Uri.encode(normalizedId)}&season=$season&episode=$episode"
        )?.let { root ->
            listOfNotNull(
                root.optJSONObject("intro")?.readIntroDbStamp(IntroDbMarkerType.Intro),
                root.optJSONObject("recap")?.readIntroDbStamp(IntroDbMarkerType.Recap),
                root.optJSONObject("outro")?.readIntroDbStamp(IntroDbMarkerType.Outro)
            )
        }.orEmpty()
        if (introDbMarkers.isNotEmpty()) return@withContext introDbMarkers
    }

    val theIntroDbMarkers = fetchIntroDbJson("https://api.theintrodb.org/v2/media?$idQuery$episodeQuery")?.let { root ->
        fun readArray(key: String, type: IntroDbMarkerType): List<IntroDbStamp> {
            val arr = root.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.readIntroDbStamp(type) }
        }
        readArray("intro", IntroDbMarkerType.Intro) + readArray("recap", IntroDbMarkerType.Recap) +
            readArray("credits", IntroDbMarkerType.Credits) + readArray("preview", IntroDbMarkerType.Preview)
    }.orEmpty()

    return@withContext theIntroDbMarkers
}
