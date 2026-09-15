package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One row in the in-player online subtitle search results.
 */
internal data class SubtitleSearchResult(
    val fileId: Long,
    val fileName: String,
    val language: String,
    val downloads: Int
)

/**
 * OpenSubtitles REST API helper for the player's "SEARCH SUBTITLES ONLINE…"
 * picker entry. The API key comes from Settings → Integrations (free key from
 * opensubtitles.com) and syncs across devices like the OMDb key. Search is
 * scoped by the playing item's title, season/episode, and the user's preferred
 * subtitle language; download resolves the file link and returns the subtitle
 * text, which the player then loads through the existing sidecar path.
 */
internal object SubtitleSearchHelper {
    private const val TAG = "SubtitleSearch"
    private const val BASE = "https://api.opensubtitles.com/api/v1"

    internal suspend fun search(
        context: Context,
        title: String,
        season: Int?,
        episode: Int?,
        languageHint: String
    ): List<SubtitleSearchResult> = withContext(Dispatchers.IO) {
        val key = AppPreferences.getOpensubtitlesApiKey(context)
        if (key.isBlank() || title.isBlank()) return@withContext emptyList()
        try {
            val q = StringBuilder(BASE)
                .append("/subtitles?query=")
                .append(URLEncoder.encode(title.trim(), "UTF-8"))
            if (season != null) q.append("&season=").append(season)
            if (episode != null) q.append("&episode=").append(episode)
            val lang = languageHint.trim().substringBefore('-')
            if (lang.isNotBlank()) q.append("&languages=").append(URLEncoder.encode(lang, "UTF-8"))
            val body = httpGet(q.toString(), key) ?: return@withContext emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            val out = mutableListOf<SubtitleSearchResult>()
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val file = entry.optJSONArray("files")?.optJSONObject(0) ?: continue
                val fileId = file.optLong("file_id")
                if (fileId <= 0) continue
                out.add(
                    SubtitleSearchResult(
                        fileId = fileId,
                        fileName = file.optString("file_name").ifBlank {
                            entry.optString("release").ifBlank { "Subtitle ${i + 1}" }
                        },
                        language = entry.optString("language").ifBlank { "?" },
                        downloads = entry.optInt("download_count")
                    )
                )
                if (out.size >= 12) break
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "OpenSubtitles search failed", t)
            emptyList()
        }
    }

    /** Resolves the one-time download link and returns the subtitle text. */
    internal suspend fun download(context: Context, result: SubtitleSearchResult): String? =
        withContext(Dispatchers.IO) {
            val key = AppPreferences.getOpensubtitlesApiKey(context)
            if (key.isBlank()) return@withContext null
            try {
                val meta = JSONObject(httpGet("$BASE/download?file_id=${result.fileId}", key)
                    ?: return@withContext null)
                val link = meta.optString("link").takeIf { it.isNotBlank() }
                    ?: return@withContext null
                httpGet(link, key)
            } catch (t: Throwable) {
                Log.w(TAG, "OpenSubtitles download failed", t)
                null
            }
        }

    /** Writes subtitle text into the app cache and returns a file:// Uri. */
    internal fun toCacheUri(context: Context, result: SubtitleSearchResult, body: String): Uri {
        val dir = File(context.cacheDir, "kbstream_subs").apply { mkdirs() }
        val ext = when {
            result.fileName.endsWith(".vtt", true) -> "vtt"
            result.fileName.endsWith(".ass", true) ||
                result.fileName.endsWith(".ssa", true) -> "ass"
            else -> "srt"
        }
        val f = File(dir, "${result.fileId}.$ext")
        f.writeText(body)
        return Uri.fromFile(f)
    }

    private fun httpGet(url: String, apiKey: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Api-Key", apiKey)
                setRequestProperty("User-Agent", "KBStream v1.0")
                setRequestProperty("Accept", "application/json,text/plain")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "OpenSubtitles HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OpenSubtitles request failed: $url", t)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
