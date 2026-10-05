package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.data.settings.AppPreferences
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
 * What a download attempt produced: the subtitle text, or a sentence saying why
 * there is none.
 *
 * The reason is carried out of here rather than logged because the failures are
 * not equivalent to the viewer: an exhausted daily quota and a rejected API key
 * both used to arrive as the same "Subtitle download failed" toast, and neither
 * is fixed by trying another row in the list.
 */
internal sealed interface SubtitleDownload {
    data class Ready(val body: String) : SubtitleDownload
    data class Failed(val reason: String) : SubtitleDownload
}

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
    internal const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "KBStream v1.0"
    private const val MAX_RESULTS = 12

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
            val reply = request("GET", q.toString(), key)
            parseSearchResults(reply.body)
        } catch (t: Throwable) {
            Log.w(TAG, "OpenSubtitles search failed", t)
            emptyList()
        }
    }

    /** Turns a `/subtitles` response into picker rows. */
    internal fun parseSearchResults(body: String): List<SubtitleSearchResult> {
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
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
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    /** Resolves the one-time download link and returns the subtitle text. */
    internal suspend fun download(context: Context, result: SubtitleSearchResult): SubtitleDownload =
        withContext(Dispatchers.IO) {
            val key = AppPreferences.getOpensubtitlesApiKey(context)
            if (key.isBlank()) {
                return@withContext SubtitleDownload.Failed(
                    "Subtitle download failed: add an OpenSubtitles API key in Settings"
                )
            }
            download(result.fileId, key)
        }

    /**
     * The two-step download: ask `/download` for a one-time link, then fetch it.
     *
     * The first step is a POST with a JSON body — that is the endpoint's
     * contract, and it is not negotiable: the API answers a GET with
     * "405 Method Not Allowed", so every row in the picker failed while the
     * search above it worked, because search is the endpoint that takes a GET.
     * [base] and the separate [apiKey] exist so the whole exchange can be driven
     * against a local server in tests.
     */
    internal fun download(
        fileId: Long,
        apiKey: String,
        base: String = BASE
    ): SubtitleDownload {
        val meta = request("POST", "$base/download", apiKey, """{"file_id":$fileId}""")
        if (meta.code !in 200..299) {
            Log.w(TAG, "OpenSubtitles /download HTTP ${meta.code}: ${meta.body.take(200)}")
            return SubtitleDownload.Failed("Subtitle download failed: " + failureReason(meta))
        }
        val link = runCatching { JSONObject(meta.body).optString("link") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (link == null) {
            Log.w(TAG, "OpenSubtitles /download had no link: ${meta.body.take(200)}")
            return SubtitleDownload.Failed("Subtitle download failed: no download link in the response")
        }
        val file = request("GET", link, apiKey)
        if (file.code !in 200..299) {
            Log.w(TAG, "OpenSubtitles subtitle file HTTP ${file.code}")
            return SubtitleDownload.Failed("Subtitle download failed: " + failureReason(file))
        }
        if (!looksLikeSubtitle(file.body)) {
            Log.w(TAG, "OpenSubtitles subtitle file was not a subtitle: ${file.body.take(200)}")
            return SubtitleDownload.Failed("Subtitle download failed: the server did not return a subtitle")
        }
        return SubtitleDownload.Ready(file.body)
    }

    /**
     * A subtitle payload has to at least look like one. An HTML body is an error
     * page the CDN served with a 200, and handing that to the player is a silent
     * no-cues failure instead of a message.
     */
    internal fun looksLikeSubtitle(body: String): Boolean {
        val head = body.trimStart()
        if (head.isEmpty()) return false
        val lower = head.take(64).lowercase()
        return !lower.startsWith("<!doctype html") && !lower.startsWith("<html")
    }

    /**
     * Whether a downloaded body can actually be drawn, not merely whether it is
     * an error page.
     *
     * [looksLikeSubtitle] catches the CDN's HTML served with a 200. This catches
     * the subtler shape that also arrives as [SubtitleDownload.Ready]: a
     * truncated file, a plain-text notice ("you have reached the download
     * limit") or binary junk - a body that is not HTML yet carries no cue the
     * player can render. Handing one of those on is a silent no-cues failure
     * dressed up as success, which is what the manual picker's "Subtitle
     * loaded" line and the auto-fetch toast both used to claim.
     *
     * It mirrors the player's own load path exactly, so a body this accepts is a
     * body the player can show: an ASS script counts when the engine can render
     * one, everything else has to parse into at least one cue. [assRenderable]
     * is the engine's answer - the ExoPlayer build's
     * `AssSubtitleRenderer.available`, or true for mpv, which renders ASS
     * itself. It stays a parameter so the rule is pure and unit tested rather
     * than discovered on the TV.
     */
    internal fun isUsableSubtitleBody(body: String, assRenderable: Boolean): Boolean =
        if (assRenderable && AssSubtitleSource.isAssContent(body)) {
            body.isNotBlank()
        } else {
            SubtitleFileParser.parse(body).isNotEmpty()
        }

    /** The API's own explanation when it sent one, else what the status means. */
    internal fun failureReason(reply: HttpReply): String {
        val message = runCatching { JSONObject(reply.body).optString("message") }
            .getOrNull()
            ?.trim()
            .orEmpty()
        return when {
            message.isNotBlank() -> message
            reply.code == 0 -> "could not reach OpenSubtitles"
            reply.code == 401 || reply.code == 403 -> "OpenSubtitles rejected the API key"
            reply.code == 429 -> "OpenSubtitles rate limit reached - try again later"
            else -> "OpenSubtitles returned HTTP ${reply.code}"
        }
    }

    /** Writes subtitle text into the app cache and returns a file:// Uri. */
    internal fun toCacheUri(context: Context, result: SubtitleSearchResult, body: String): Uri {
        val dir = com.kennyb1201.kbstream.data.cache.DiskSweep.subtitleDir(context)
        val ext = when {
            result.fileName.endsWith(".vtt", true) -> "vtt"
            result.fileName.endsWith(".ass", true) ||
                result.fileName.endsWith(".ssa", true) -> "ass"
            else -> "srt"
        }
        // Deterministic by file id: re-picking the same track overwrites its
        // own file instead of adding another one (see DiskSweep).
        val f = File(dir, "${result.fileId}.$ext")
        f.writeText(body)
        return Uri.fromFile(f)
    }

    /** A response's status and body - including the body of an error, which is
     *  where the API puts the reason ("you have downloaded your allowed
     *  subtitles"). */
    internal data class HttpReply(val code: Int, val body: String)

    private fun request(
        method: String,
        url: String,
        apiKey: String,
        body: String? = null
    ): HttpReply {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Api-Key", apiKey)
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json,text/plain")
                if (body != null) setRequestProperty("Content-Type", "application/json")
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpReply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (t: Throwable) {
            Log.w(TAG, "OpenSubtitles request failed: $method $url", t)
            HttpReply(0, "")
        } finally {
            conn?.disconnect()
        }
    }
}
