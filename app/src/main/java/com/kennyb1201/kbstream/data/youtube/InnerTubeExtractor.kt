package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct YouTube player-API extractor (modeled after Nuvio's approach).
 *
 * Talks straight to YouTube's InnerTube `youtubei/v1/player` endpoint — the
 * same API the official YouTube apps use — with a 3-client fallback chain
 * (android_vr -> android -> ios). It caches the watch-page config
 * (INNERTUBE_API_KEY + VISITOR_DATA) for 3 hours and resolves progressive,
 * adaptive (video+audio) or HLS manifests. This is far more reliable than
 * NewPipe/Piped because there is no third-party extractor to break and no
 * public instance that can go down.
 */
object InnerTubeExtractor {

    private const val TAG = "InnerTubeExtractor"
    private const val EXTRACTOR_TIMEOUT_MS = 30_000L
    private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private const val PREFERRED_SEPARATE_CLIENT = "android_vr"
    private const val FALLBACK_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
    private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
    private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

    private data class YouTubeClient(
        val key: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val context: JSONObject,
        val priority: Int
    )

    private data class CachedConfig(
        val apiKey: String,
        val visitorData: String?,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private data class StreamCandidate(
        val client: String,
        val priority: Int,
        val url: String,
        val score: Double,
        val hasN: Boolean,
        val height: Int,
        val ext: String
    )

    private data class ManifestVariant(
        val url: String,
        val height: Int,
        val bandwidth: Long
    )

    private data class RequestResponse(
        val ok: Boolean,
        val status: Int,
        val body: String
    )

    private val DEFAULT_HEADERS = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to DEFAULT_USER_AGENT
    )

    private val CLIENTS = listOf(
        YouTubeClient(
            key = "android_vr",
            id = "28",
            version = "1.56.21",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip",
            context = JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.56.21")
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12")
                put("platform", "MOBILE")
                put("androidSdkVersion", 32)
                put("hl", "en")
                put("gl", "US")
            },
            priority = 0
        ),
        YouTubeClient(
            key = "android",
            id = "3",
            version = "20.10.35",
            userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
            context = JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", "20.10.35")
                put("osName", "Android")
                put("osVersion", "14")
                put("platform", "MOBILE")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", "US")
            },
            priority = 1
        ),
        YouTubeClient(
            key = "ios",
            id = "5",
            version = "20.10.1",
            userAgent = "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
            context = JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "20.10.1")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "17.4.0.21E219")
                put("platform", "MOBILE")
                put("hl", "en")
                put("gl", "US")
            },
            priority = 2
        )
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val cachedConfig = AtomicReference<CachedConfig?>(null)
    private val configMutex = Mutex()

    /**
     * Resolves a YouTube video to a directly playable [PlayableSource].
     * Returns null if no client could produce a usable stream.
     */
    suspend fun extractPlaybackSource(youtubeUrl: String): PlayableSource? =
        withContext(Dispatchers.IO) {
            if (youtubeUrl.isBlank()) return@withContext null

            Log.d(TAG, "Starting extraction for $youtubeUrl")

            var source: PlayableSource? = null
            try {
                source = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                    extractInternal(youtubeUrl, forceRefreshConfig = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.w(TAG, "Extractor failed: ${error.message}")
            }

            // Retry with a fresh watch config if the first attempt failed
            if (source == null) {
                Log.d(TAG, "First attempt failed; retrying with fresh config")
                try {
                    source = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                        extractInternal(youtubeUrl, forceRefreshConfig = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    Log.w(TAG, "Extractor retry failed: ${error.message}")
                }
            }

            if (source == null) {
                Log.w(TAG, "No playable source for $youtubeUrl")
            }
            source
        }

    private suspend fun extractInternal(
        youtubeUrl: String,
        forceRefreshConfig: Boolean
    ): PlayableSource? {
        val videoId = extractVideoId(youtubeUrl) ?: return null
        val config = ensureWatchConfig(forceRefresh = forceRefreshConfig)

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val hlsUrls = mutableListOf<Triple<String, Int, String>>()
        var loginRequiredCount = 0

        for (client in CLIENTS) {
            kotlinx.coroutines.yield()
            try {
                val playerResponse = fetchPlayerResponse(
                    apiKey = config.apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = config.visitorData
                )

                val playability = playerResponse.optJSONObject("playabilityStatus")
                val status = playability?.optString("status")
                if (status == "LOGIN_REQUIRED") {
                    loginRequiredCount++
                    Log.w(TAG, "Client ${client.key}: LOGIN_REQUIRED (visitor may be stale)")
                    continue
                }
                if (status != null && status != "OK") {
                    continue
                }

                val streamingData = playerResponse.optJSONObject("streamingData") ?: continue

                val hlsManifestUrl = streamingData.optString("hlsManifestUrl")
                if (hlsManifestUrl.isNotBlank()) {
                    hlsUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }

                parseFormats(streamingData.optJSONArray("formats")).forEach {
                    progressive += it
                }
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val format = adaptive.optJSONObject(i) ?: continue
                        val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
                        val mimeType = format.optString("mimeType").orEmpty()
                        if (mimeType.contains("video/")) {
                            adaptiveVideo += candidateFromFormat(client, format, url, mimeType, "video")
                        } else if (mimeType.startsWith("audio/")) {
                            adaptiveAudio += candidateFromFormat(client, format, url, mimeType, "audio")
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Client ${client.key} failed: ${error.message}")
            }
        }

        if (loginRequiredCount == CLIENTS.size) {
            Log.w(TAG, "All clients LOGIN_REQUIRED; invalidating config")
            invalidateConfig()
            return null
        }

        if (hlsUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return null
        }

        // Prefer HLS first. Media3 handles manifest segments, refreshes and
        // seeking without reopening googlevideo URLs at arbitrary byte offsets.
        val bestHls = pickBestHls(hlsUrls)
        if (bestHls != null) {
            Log.d(TAG, "Using HLS trailer manifest")
            return PlayableSource.Muxed(bestHls)
        }

        // Prefer adaptive video + audio (best quality) only when HLS is absent.
        val bestVideo = pickBest(adaptiveVideo, PREFERRED_SEPARATE_CLIENT)
        val bestAudio = pickBest(adaptiveAudio, PREFERRED_SEPARATE_CLIENT)

        val resolvedVideo = bestVideo?.url?.let { resolveReachableUrl(it) }
        val resolvedAudio =
            if (resolvedVideo != null) bestAudio?.url?.let { resolveReachableUrl(it) } else null

        if (resolvedVideo != null) {
            return if (resolvedAudio != null) {
                PlayableSource.Adaptive(resolvedVideo, resolvedAudio)
            } else {
                PlayableSource.Muxed(resolvedVideo)
            }
        }

        // Last resort: progressive (combined) stream
        val bestProgressive = progressive.sortedWith(
            compareByDescending<StreamCandidate> { it.score }
                .thenBy { if (it.hasN) 1 else 0 }
                .thenBy { it.priority }
        ).firstOrNull()

        val resolvedProgressive = bestProgressive?.url?.let { resolveReachableUrl(it) }
        if (resolvedProgressive != null) {
            return PlayableSource.Muxed(resolvedProgressive)
        }

        return null
    }

    private fun parseFormats(formats: JSONArray?): List<StreamCandidate> {
        if (formats == null) return emptyList()
        val out = mutableListOf<StreamCandidate>()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
            val mimeType = format.optString("mimeType").orEmpty()
            out += candidateFromFormat(CLIENTS[0], format, url, mimeType, "video")
        }
        return out
    }

    private fun candidateFromFormat(
        client: YouTubeClient,
        format: JSONObject,
        url: String,
        mimeType: String,
        kind: String
    ): StreamCandidate {
        val height = format.optInt("height", 0).takeIf { it > 0 }
            ?: parseQualityLabel(format.optString("qualityLabel"))
            ?: 0
        val fps = format.optInt("fps", 0)
        val bitrate = format.optDouble("bitrate", format.optDouble("averageBitrate", 0.0))
        val score = if (kind == "audio") {
            bitrate * 1_000_000.0 + format.optDouble("audioSampleRate", 0.0)
        } else {
            height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
        }
        return StreamCandidate(
            client = client.key,
            priority = client.priority,
            url = url,
            score = score,
            hasN = hasNParam(url),
            height = height,
            ext = if (mimeType.contains("webm")) "webm" else if (kind == "audio") "m4a" else "mp4"
        )
    }

    private fun pickBest(items: List<StreamCandidate>, preferredClient: String): StreamCandidate? {
        val preferred = items.filter { it.client == preferredClient }
        val pool = if (preferred.isNotEmpty()) preferred else items
        return pool.sortedWith(
            compareByDescending<StreamCandidate> { it.score }
                .thenBy { if (it.hasN) 1 else 0 }
                .thenBy { it.priority }
        ).firstOrNull()
    }

    private suspend fun pickBestHls(
        hlsUrls: List<Triple<String, Int, String>>
    ): String? {
        var best: Pair<ManifestVariant, Int>? = null
        for ((_, priority, manifestUrl) in hlsUrls) {
            try {
                val variant = parseHlsManifest(manifestUrl) ?: continue
                if (
                    best == null ||
                    variant.height > best.first.height ||
                    (variant.height == best.first.height && variant.bandwidth > best.first.bandwidth)
                ) {
                    best = variant to priority
                }
            } catch (error: Exception) {
                Log.w(TAG, "HLS manifest parse failed: ${error.message}")
            }
        }
        return best?.first?.url
    }

    // ── Watch config (api key + visitor data) ─────────────────────────

    private suspend fun ensureWatchConfig(forceRefresh: Boolean = false): CachedConfig {
        if (!forceRefresh) {
            cachedConfig.get()?.let {
                if (!isConfigStale(it)) return it
            }
        }
        return configMutex.withLock {
            if (!forceRefresh) {
                cachedConfig.get()?.let {
                    if (!isConfigStale(it)) return@withLock it
                }
            }

            Log.d(TAG, "Fetching watch page for visitor_data")
            val watchResponse = performRequest(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en",
                method = "GET",
                headers = DEFAULT_HEADERS
            )
            if (!watchResponse.ok) {
                val stale = cachedConfig.get()
                if (stale != null) {
                    Log.w(TAG, "Watch page failed (${watchResponse.status}); using stale config")
                    return@withLock stale
                }
                throw IllegalStateException("Failed to fetch watch page (${watchResponse.status})")
            }

            val apiKey = API_KEY_REGEX.find(watchResponse.body)?.groupValues?.getOrNull(1)
                ?: FALLBACK_API_KEY
            val visitorData = VISITOR_DATA_REGEX.find(watchResponse.body)?.groupValues?.getOrNull(1)

            val newConfig = CachedConfig(
                apiKey = apiKey,
                visitorData = visitorData
            )
            cachedConfig.set(newConfig)
            Log.d(TAG, "Watch config cached (visitor=${!visitorData.isNullOrBlank()})")
            newConfig
        }
    }

    private fun isConfigStale(config: CachedConfig): Boolean =
        System.currentTimeMillis() - config.fetchedAt > CONFIG_TTL_MS

    fun invalidateConfig() {
        cachedConfig.set(null)
        Log.d(TAG, "Watch config invalidated")
    }

    // ── Player API ────────────────────────────────────────────────────

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?
    ): JSONObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"

        val headers = buildMap {
            putAll(DEFAULT_HEADERS)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) {
                put("x-goog-visitor-id", visitorData)
            }
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put(
                "context",
                JSONObject().apply {
                    put("client", client.context)
                }
            )
            put(
                "playbackContext",
                JSONObject().apply {
                    put(
                        "contentPlaybackContext",
                        JSONObject().apply {
                            put("html5Preference", "HTML5_PREF_WANTS")
                        }
                    )
                }
            )
        }

        val response = performRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = payload.toString()
        )
        if (!response.ok) {
            throw IllegalStateException("player API ${client.key} failed (${response.status})")
        }
        return runCatching { JSONObject(response.body) }.getOrDefault(JSONObject())
    }

    // ── HLS parsing ───────────────────────────────────────────────────

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestVariant? {
        val response = performRequest(
            url = manifestUrl,
            method = "GET",
            headers = DEFAULT_HEADERS
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var best: ManifestVariant? = null
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = lines.getOrNull(i + 1) ?: continue
            if (next.startsWith("#")) continue

            val attrs = parseHlsAttrs(line)
            val height = attrs["RESOLUTION"]
                ?.split("x")
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val candidate = ManifestVariant(
                url = absolutizeUrl(manifestUrl, next),
                height = height,
                bandwidth = bandwidth
            )
            if (
                best == null ||
                candidate.height > best.height ||
                (candidate.height == best.height && candidate.bandwidth > best.bandwidth)
            ) {
                best = candidate
            }
        }
        return best
    }

    private fun parseHlsAttrs(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') inKey = false else key.append(ch)
                continue
            }
            if (ch == '"') {
                inQuote = !inQuote
                continue
            }
            if (ch == ',' && !inQuote) {
                val k = key.toString().trim()
                if (k.isNotEmpty()) out[k] = value.toString().trim()
                key.clear()
                value.clear()
                inKey = true
                continue
            }
            value.append(ch)
        }
        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) out[lastKey] = value.toString().trim()
        return out
    }

    // ── CDN reachability probe ────────────────────────────────────────

    /**
     * Probes googlevideo CDN nodes and returns the first reachable URL.
     * Handles YouTube's `mn` multi-node parameter and 403-prone node fallback.
     */
    private suspend fun resolveReachableUrl(url: String): String? {
        if (!url.contains("googlevideo.com")) return url

        // YouTube signs every googlevideo stream URL (sig/n/lsig params) for a
        // specific host. Rewriting the host to a different `mn` node — or even
        // appending a query param like ratebypass — invalidates that signature
        // and googlevideo replies 403 to every request. So probe the EXACT URL
        // we intend to play, byte unchanged, and only use it if it serves bytes.
        // If it 403s, return null so extractInternal falls back to HLS, then
        // the progressive (muxed) stream.
        return if (isUrlReachable(url)) url else null
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun isUrlReachable(url: String): Boolean =
        runCatching {
            // Probe the exact URL unchanged (same client UA Is the player uses),
            // requesting only the first byte via a Range header so the signature
            // stays valid for the actual playback request.
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", CLIENTS[0].userAgent)
                .build()
            probeClient.newCall(request).execute().use { response ->
                response.code == 200 || response.code == 206
            }
        }.getOrDefault(false)

    // ── Helpers ───────────────────────────────────────────────────────

    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return runCatching {
            val uri = Uri.parse(normalized)
            val host = uri.host?.lowercase().orEmpty()

            if (host.endsWith("youtu.be")) {
                val id = uri.pathSegments.firstOrNull()
                if (!id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id)) return id
            }

            val queryId = uri.getQueryParameter("v")
            if (!queryId.isNullOrBlank() && VIDEO_ID_REGEX.matches(queryId)) {
                return queryId
            }

            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val first = segments[0]
                val second = segments[1]
                if (
                    (first == "embed" || first == "shorts" || first == "live") &&
                    VIDEO_ID_REGEX.matches(second)
                ) {
                    return second
                }
            }
            null
        }.getOrNull()
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        return QUALITY_LABEL_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNParam(url: String): Boolean =
        runCatching {
            !Uri.parse(url).getQueryParameter("n").isNullOrBlank()
        }.getOrDefault(false)

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String =
        runCatching {
            URL(URL(baseUrl), maybeRelative).toString()
        }.getOrElse { maybeRelative }

    private suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): RequestResponse {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                requestBuilder.header(name, value)
            }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            RequestResponse(
                ok = response.isSuccessful,
                status = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }
}
