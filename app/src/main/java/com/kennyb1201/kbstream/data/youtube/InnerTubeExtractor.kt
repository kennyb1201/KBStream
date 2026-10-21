package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct YouTube player-API extractor (modeled after KB's approach).
 *
 * Talks straight to YouTube's InnerTube `youtubei/v1/player` endpoint — the
 * same API the official YouTube apps use — with the client chain in
 * [YouTubeClients] (android -> tv -> ios -> web_embedded -> android_vr). Every
 * format it returns carries the client that signed it, because googlevideo only
 * serves a signed URL to that client's User-Agent.
 *
 * The watch config (INNERTUBE_API_KEY + VISITOR_DATA) is minted from the VIDEO'S
 * OWN EMBED PAGE and cached for 3 hours: the visitor belongs to the embedded
 * client context - the one built for third-party playback - instead of an
 * unrelated desktop watch page that no mobile/VR client context ever used. The
 * extractor then resolves progressive, adaptive (video+audio) or HLS manifests.
 * This is far more reliable than NewPipe/Piped because there is no third-party
 * extractor to break and no public instance that can go down.
 */
object InnerTubeExtractor {

    private const val TAG = "InnerTubeExtractor"
    // Hard cap per extraction pass. Two passes (initial + fresh-config retry)
    // can therefore never exceed ~30s total; trailers are background content
    // and a resolution that drags past this is worse than falling back to the
    // backdrop quickly.
    private const val EXTRACTOR_TIMEOUT_MS = 15_000L
    private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

    /**
     * How long a resolve may wait for a PO token before proceeding without one.
     *
     * Trailers are background content: attestation must never become the long
     * pole in a resolve that caps at [EXTRACTOR_TIMEOUT_MS]. A cold BotGuard
     * mint is normally 1-3 s; past this budget the resolve takes the tokenless
     * path, which is exactly what it did before tokens existed.
     */
    private const val TOKEN_BUDGET_MS = 4_000L

    private const val DEFAULT_USER_AGENT = YouTubeClients.DEFAULT_USER_AGENT

    /**
     * Preferred client for adaptive video/audio picks. android_vr used to be
     * first but its sessions have started returning LOGIN_REQUIRED at higher
     * rates; android/tv/ios remain the reliable anonymous clients. Every source
     * this extractor returns carries its client's UA so playback requests
     * match the signature.
     */
    internal const val PREFERRED_SEPARATE_CLIENT = "android"

    /**
     * TV playback ceiling for trailers. YouTube serves up to 4K in adaptive
     * formats; 4K VP9 overwhelms TV hardware decoders (Realtek TCL stutters,
     * Firestick never buffers enough and gives up). Trailers are hero/inline
     * content — 1080p is the right tradeoff, and H.264 has far wider hardware
     * decode coverage than VP9/AV1 on TV SoCs.
     */
    private const val MAX_TRAILER_HEIGHT = 1080
    private const val PREFERRED_VIDEO_MIME = "avc"

    /**
     * How many audio candidates to CDN-probe (best-first) before giving up
     * on the adaptive pair and falling back to a muxed progressive stream.
     * Bounds worst-case resolve latency: each probe is a ~1 MB ranged GET
     * with a 2 s connect / 5 s read timeout.
     */
    private const val MAX_AUDIO_PROBES = 3


    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
    private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
    private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

    private data class CachedConfig(
        val apiKey: String,
        val visitorData: String?,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    internal data class StreamCandidate(
        val client: String,
        val priority: Int,
        val url: String,
        val score: Double,
        val hasN: Boolean,
        val height: Int,
        val ext: String
    )

    /**
     * What one client's player response contributes to the pools.
     *
     * [status] is `playabilityStatus.status` verbatim (null when the response
     * carried none). A LOGIN_REQUIRED response is counted, not pooled - it is
     * the signal that the identity itself is being refused, and when every
     * client answers that way the cached config is thrown away.
     */
    internal data class ClientHarvest(
        val status: String?,
        val hlsUrl: String? = null,
        val progressive: List<StreamCandidate> = emptyList(),
        val video: List<StreamCandidate> = emptyList(),
        val audio: List<StreamCandidate> = emptyList()
    ) {
        val loginRequired: Boolean get() = status == "LOGIN_REQUIRED"

        /** False for a real error status (UNPLAYABLE, ERROR, ...). */
        val playable: Boolean get() = status == null || status == "OK"
    }

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
        // The id is known before the config call on purpose: the visitor is
        // minted on THIS video's embed page, not on an unrelated one.
        val config = ensureWatchConfig(videoId, forceRefresh = forceRefreshConfig)
        // One mint per resolve covers both contexts: the WebView loads this
        // video's embed page and the page's player produces its player token
        // and its googlevideo token together. Either may be null.
        val tokens = mintTokens(videoId, config.visitorData)

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val hlsUrls = mutableListOf<Triple<String, Int, String>>()
        var loginRequiredCount = 0

        for (client in YouTubeClients.all) {
            kotlinx.coroutines.yield()
            try {
                val playerResponse = fetchPlayerResponse(
                    apiKey = config.apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = config.visitorData,
                    playerToken = tokens.player
                )

                val harvest = harvestPlayerResponse(client, playerResponse)
                if (harvest.loginRequired) {
                    loginRequiredCount++
                    Log.w(TAG, "Client ${client.key}: LOGIN_REQUIRED (visitor may be stale)")
                    continue
                }
                if (!harvest.playable) {
                    continue
                }

                harvest.hlsUrl?.takeIf { it.isNotBlank() }?.let {
                    hlsUrls += Triple(client.key, client.priority, it)
                }
                progressive += harvest.progressive
                adaptiveVideo += harvest.video
                adaptiveAudio += harvest.audio
            } catch (error: Exception) {
                Log.w(TAG, "Client ${client.key} failed: ${error.message}")
            }
        }

        if (loginRequiredCount == YouTubeClients.all.size) {
            Log.w(TAG, "All clients LOGIN_REQUIRED; invalidating config")
            invalidateConfig()
            return null
        }

        if (hlsUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return null
        }

        // Prefer HLS first. Media3 handles manifest segments, refreshes and
        // seeking without reopening googlevideo URLs at arbitrary byte offsets.
        // HLS manifests are signed for the client that received them, so the
        // manifest fetch (and its segment fetches) must carry that client's UA.
        val bestHls = pickBestHls(hlsUrls)
        if (bestHls != null) {
            Log.d(TAG, "Using HLS trailer manifest (client=${bestHls.first})")
            return PlayableSource.Muxed(
                bestHls.third,
                userAgent = userAgentFor(bestHls.first)
            ).withResolveTokens(videoId, tokens)
        }

        // Prefer adaptive video + audio (best quality) only when HLS is absent.
        val bestVideo = pickBest(adaptiveVideo, PREFERRED_SEPARATE_CLIENT)

        val resolvedVideo = bestVideo?.let {
            resolveReachableUrl(it.url, userAgentFor(it.client))
        }

        // Probe more than one audio candidate: googlevideo rejects probes
        // per-CDN-node, so the single best audio URL 403ing does NOT mean the
        // title has no usable audio. Falling back to the video-only stream
        // here used to ship silent trailers (Adaptive video tracks carry no
        // audio) — exactly the "trailers play muted" report.
        var resolvedAudio: String? = null
        if (resolvedVideo != null) {
            val audioCandidates = adaptiveAudio.sortedWith(
                compareByDescending<StreamCandidate> { it.score }
                    .thenBy { if (it.hasN) 1 else 0 }
                    .thenBy { it.priority }
            )
            var probes = 0
            for (candidate in audioCandidates) {
                if (probes >= MAX_AUDIO_PROBES) break
                probes++
                val probed = resolveReachableUrl(
                    candidate.url,
                    userAgentFor(candidate.client)
                )
                if (probed != null) {
                    resolvedAudio = probed
                    break
                }
            }
            if (resolvedAudio == null) {
                Log.w(
                    TAG,
                    "No adaptive audio URL reachable after $probes probes " +
                        "— will fall back to a muxed stream"
                )
            }
        }

        if (resolvedVideo != null && resolvedAudio != null) {
            val clientUa = bestVideo?.let { userAgentFor(it.client) }
            // Which client signed the winning source is the field check for
            // this identity work: a full-length play on a tv/ios-signed source
            // with android-signed still 403ing at ~36 s says the remaining wall
            // is attestation, not the client table.
            Log.d(TAG, "Using adaptive video+audio (client=${bestVideo?.client})")
            return PlayableSource.Adaptive(resolvedVideo, resolvedAudio, userAgent = clientUa)
                .withResolveTokens(videoId, tokens)
        }

        // No verified adaptive audio (or no video at all): fall through to
        // the progressive (combined) stream — genuinely muxed, so it carries
        // audio even when every adaptive audio URL failed its probe.
        if (resolvedVideo != null) {
            Log.w(
                TAG,
                "Adaptive video resolved but audio probe failed; " +
                    "falling through to muxed progressive"
            )
        }
        val bestProgressive = progressive.sortedWith(
            compareByDescending<StreamCandidate> { it.score }
                .thenBy { if (it.hasN) 1 else 0 }
                .thenBy { it.priority }
        ).firstOrNull()

        val resolvedProgressive = bestProgressive?.let {
            resolveReachableUrl(it.url, userAgentFor(it.client))
        }
        if (resolvedProgressive != null) {
            Log.d(
                TAG,
                "Falling back to muxed progressive stream " +
                    "(audio safety, client=${bestProgressive.client})"
            )
            return PlayableSource.Muxed(
                resolvedProgressive,
                userAgent = userAgentFor(bestProgressive.client)
            ).withResolveTokens(videoId, tokens)
        }

        return null
    }

    /** What one resolve minted; either half may be null (the tokenless path). */
    private data class ResolveTokens(val player: String?, val gvs: String?)

    /**
     * Mints the resolve's tokens, waiting at most [TOKEN_BUDGET_MS] for each.
     *
     * The GVS mint comes first because it is the one that runs the WebView: it
     * loads this video's embed page, and the same session yields the player
     * token, which the provider then serves from cache. Asking for the player
     * token first would only find an empty cache - a player token has no video
     * to load and is never minted on its own.
     *
     * No visitor means nothing to bind a token to (and nothing for the player
     * request to identify itself with), so the resolve goes tokenless.
     */
    private suspend fun mintTokens(videoId: String, visitorData: String?): ResolveTokens {
        if (visitorData.isNullOrBlank()) return ResolveTokens(null, null)
        val provider = PoTokens.provider()
        val gvs = tokenWithinBudget { provider.gvsToken(videoId, visitorData) }
        val player = tokenWithinBudget { provider.playerToken(visitorData) }
        return ResolveTokens(player, gvs)
    }

    /** A bounded wait for one token; any failure or timeout means "no token". */
    private suspend fun tokenWithinBudget(mint: suspend () -> String?): String? =
        try {
            withTimeoutOrNull(TOKEN_BUDGET_MS) { mint() }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            // The token is an enhancement layer: a broken minter must degrade to
            // the pre-spec request, never fail the resolve.
            Log.w(TAG, "PO token unavailable (${error.javaClass.simpleName}); resolving without")
            null
        }

    /**
     * Attaches the GVS token to the source being returned, and records what the
     * resolve actually carried for the diagnostics report.
     */
    private fun PlayableSource.withResolveTokens(
        videoId: String,
        tokens: ResolveTokens
    ): PlayableSource {
        val decorated = withPotToken(tokens.gvs)
        PoTokenTrace.recordResolve(
            videoId = videoId,
            playerToken = !tokens.player.isNullOrBlank(),
            pot = decorated.carriesPot()
        )
        return decorated
    }

    /**
     * Pools one client's player response: what its HLS/progressive/adaptive
     * formats contribute, and whether the response was refused outright.
     *
     * The owning [client] is threaded into every candidate. A format is signed
     * for the client whose response carried it, so its UA is what probes and
     * playback must send. The parser used to hand EVERY client's progressive
     * formats to `CLIENTS[0]` (android), which made an iOS- or VR-signed URL
     * get probed and played under the Android UA - the false-403 pattern this
     * package documents elsewhere.
     */
    internal fun harvestPlayerResponse(
        client: YouTubeClient,
        playerResponse: JSONObject
    ): ClientHarvest {
        val status = playerResponse.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK") return ClientHarvest(status = status)

        val streamingData = playerResponse.optJSONObject("streamingData")
            ?: return ClientHarvest(status = status)

        val video = mutableListOf<StreamCandidate>()
        val audio = mutableListOf<StreamCandidate>()
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val format = adaptive.optJSONObject(i) ?: continue
                val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mimeType = format.optString("mimeType").orEmpty()
                if (mimeType.contains("video/")) {
                    video += candidateFromFormat(client, format, url, mimeType, "video")
                } else if (mimeType.startsWith("audio/")) {
                    audio += candidateFromFormat(client, format, url, mimeType, "audio")
                }
            }
        }

        return ClientHarvest(
            status = status,
            hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() },
            progressive = parseFormats(streamingData.optJSONArray("formats"), client),
            video = video,
            audio = audio
        )
    }

    /** Progressive (muxed video+audio) formats, tagged with their own client. */
    internal fun parseFormats(
        formats: JSONArray?,
        client: YouTubeClient
    ): List<StreamCandidate> {
        if (formats == null) return emptyList()
        val out = mutableListOf<StreamCandidate>()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
            val mimeType = format.optString("mimeType").orEmpty()
            out += candidateFromFormat(client, format, url, mimeType, "video")
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
        // Codec-aware, height-capped score for video: strongly prefer H.264
        // (avc1) at or below the TV ceiling, then lower resolutions, then
        // anything else. Audio keeps the pure-bitrate score.
        val score = if (kind == "audio") {
            bitrate * 1_000_000.0 + format.optDouble("audioSampleRate", 0.0)
        } else {
            val isAvc = mimeType.contains(PREFERRED_VIDEO_MIME, ignoreCase = true)
            val cappedHeight = minOf(height, MAX_TRAILER_HEIGHT)
            if (isAvc && height <= MAX_TRAILER_HEIGHT) {
                // Best tier: hardware-friendly codec within the ceiling.
                4.0e12 + cappedHeight * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
            } else if (height <= MAX_TRAILER_HEIGHT) {
                // Within ceiling but VP9/AV1 - decode may fall back to software.
                2.0e12 + cappedHeight * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
            } else {
                // Above the ceiling - least desirable for TV playback.
                bitrate
            }
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

    /** UA of the client a candidate URL was signed for (probes must match it). */
    private fun userAgentFor(clientKey: String): String? = YouTubeClients.userAgentFor(clientKey)

    private suspend fun pickBestHls(
        hlsUrls: List<Triple<String, Int, String>>
    ): Triple<String, Int, String>? {
        // Prefer the highest variant at or below the TV ceiling; if every
        // variant exceeds it (rare), fall back to the lowest available
        // rather than refusing to play.
        var best: Pair<ManifestVariant, Triple<String, Int, String>>? = null
        var lowest: Pair<ManifestVariant, Triple<String, Int, String>>? = null
        for (entry in hlsUrls) {
            val manifestUrl = entry.third
            try {
                // Signed for `entry.first`'s client: fetch its manifest with
                // that client's UA (see parseHlsManifest comment).
                val variant = parseHlsManifest(manifestUrl, userAgentFor(entry.first)) ?: continue
                if (lowest == null || variant.height < lowest.first.height) {
                    lowest = variant to entry
                }
                if (variant.height > MAX_TRAILER_HEIGHT) continue
                if (
                    best == null ||
                    variant.height > best.first.height ||
                    (variant.height == best.first.height && variant.bandwidth > best.first.bandwidth)
                ) {
                    best = variant to entry
                }
            } catch (error: Exception) {
                Log.w(TAG, "HLS manifest parse failed: ${error.message}")
            }
        }
        return (best ?: lowest)?.second
    }

    // ── Watch config (api key + visitor data) ─────────────────────────

    /** What one page's ytcfg yields: the API key and the visitor it minted. */
    internal data class ScrapedConfig(
        val apiKey: String?,
        val visitorData: String?
    )

    /**
     * The ytcfg values of one page, or an empty scrape for a page that did not
     * arrive (null/blank).
     *
     * The same two regexes read the embed page and the watch page: both embed
     * the player config as `"INNERTUBE_API_KEY":"…"` /
     * `"VISITOR_DATA":"…"` pairs in their bootstrap JSON.
     */
    internal fun scrapeConfig(page: String?): ScrapedConfig {
        if (page.isNullOrBlank()) return ScrapedConfig(null, null)
        return ScrapedConfig(
            apiKey = API_KEY_REGEX.find(page)?.groupValues?.getOrNull(1),
            visitorData = VISITOR_DATA_REGEX.find(page)?.groupValues?.getOrNull(1)
        )
    }

    /**
     * Which scrape the config comes from: the embed page's when it yielded a
     * key, otherwise the watch page's (the only source before this change).
     * [watchConfig] is null when the watch page was never needed.
     */
    internal fun mintedConfig(embed: ScrapedConfig, watchConfig: ScrapedConfig?): ScrapedConfig =
        if (!embed.apiKey.isNullOrBlank()) embed else watchConfig ?: embed

    /**
     * The watch config: an InnerTube API key plus the visitor data it was
     * minted with.
     *
     * The visitor comes from THIS video's embed page. It used to be scraped
     * from a hardcoded desktop watch page (`watch?v=dQw4w9WgXcQ`) and then
     * presented to the mobile/TV/VR client contexts, which never minted it - an
     * identity those contexts do not own, in a context built for an unrelated
     * video. The embed page is the embedded client's own page, which is the
     * context this app builds for third-party playback.
     *
     * Falls back to the watch page, exactly as before, when the embed fetch or
     * parse yields no key. TTL, invalidateConfig() and stale-on-failure are
     * unchanged.
     */
    private suspend fun ensureWatchConfig(
        videoId: String,
        forceRefresh: Boolean = false
    ): CachedConfig {
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

            val embedUrl = youtubeEmbedUrl(videoId)
            Log.d(TAG, "Minting watch config from the embed page for $videoId")
            val embedConfig = scrapeConfig(
                // A failed embed fetch is not a failure of the mint: it just
                // means the watch page has to provide the key (below).
                runCatching {
                    performRequest(url = embedUrl, method = "GET", headers = DEFAULT_HEADERS)
                }.getOrNull()?.takeIf { it.ok }?.body
            )

            val watchConfig = if (embedConfig.apiKey.isNullOrBlank()) {
                Log.d(TAG, "Embed page yielded no API key; falling back to the watch page")
                val watchResponse = performRequest(
                    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en",
                    method = "GET",
                    headers = DEFAULT_HEADERS
                )
                if (!watchResponse.ok) {
                    val stale = cachedConfig.get()
                    if (stale != null) {
                        Log.w(
                            TAG,
                            "Watch page failed (${watchResponse.status}); using stale config"
                        )
                        return@withLock stale
                    }
                    throw IllegalStateException(
                        "Failed to fetch watch page (${watchResponse.status})"
                    )
                }
                scrapeConfig(watchResponse.body)
            } else {
                null
            }

            // The scraped key is the source of truth; the build-time
            // BuildConfig value is an optional, blank-by-default fallback -
            // never a key baked into the source (see app/build.gradle.kts).
            val scraped = mintedConfig(embedConfig, watchConfig)
            val apiKey = scraped.apiKey ?: com.kennyb1201.kbstream.BuildConfig.INNERTUBE_API_KEY
            val visitorData = scraped.visitorData

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
        // A refused identity refuses the tokens minted FOR it: the player token
        // is bound to the visitor just thrown away, and a stream token for the
        // same video would only be spent on a URL YouTube has already refused.
        // Dropping both here is what keeps a refused token from being spent
        // twice; the next resolve mints for the fresh visitor.
        PoTokens.invalidate()
        // Warning, not debug: this is now the field's evidence that the
        // playback side invalidated the identity (a 403 retry on a TV), and
        // Fire OS suppresses debug-level logs - the same reason the trailer
        // resolution traces above are warnings.
        Log.w(TAG, "Watch config invalidated")
    }

    // ── Player API ────────────────────────────────────────────────────

    /**
     * The InnerTube player request body for one client.
     *
     * The ONLY difference a PO token makes is the added
     * `serviceIntegrityDimensions` object: without a token the payload is
     * byte-identical to the pre-spec one, which is what makes "minting disabled"
     * a no-op rather than a different request.
     */
    internal fun playerRequestPayload(
        videoId: String,
        client: YouTubeClient,
        playerToken: String? = null
    ): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        put(
            "context",
            // Per video: the embedded client declares this video's embed
            // page as its thirdParty context (see YouTubeClient.contextFor).
            client.contextFor(videoId)
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
        if (!playerToken.isNullOrBlank()) {
            put(
                "serviceIntegrityDimensions",
                JSONObject().apply {
                    put("poToken", playerToken)
                }
            )
        }
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        playerToken: String?
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

        val payload = playerRequestPayload(videoId, client, playerToken)

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

    private suspend fun parseHlsManifest(
        manifestUrl: String,
        userAgent: String? = null
    ): ManifestVariant? {
        // The manifest must be fetched with the SAME User-Agent of the client
        // it was signed for — googlevideo binds signed HLS manifests to the
        // requesting client's UA, and fetching with the generic Chrome UA
        // 403s on some CDN edges (reported on Fire TV: trailers never played
        // there while other devices were fine). The Chrome default stays
        // only as a fallback for the legacy single-arg call sites.
        val headers = if (userAgent.isNullOrBlank()) {
            DEFAULT_HEADERS
        } else {
            DEFAULT_HEADERS + ("User-Agent" to userAgent)
        }
        val response = performRequest(
            url = manifestUrl,
            method = "GET",
            headers = headers
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
    private suspend fun resolveReachableUrl(url: String, userAgent: String? = null): String? {
        if (!url.contains("googlevideo.com")) return url

        // YouTube signs every googlevideo stream URL (sig/n/lsig params) for a
        // specific host. Rewriting the host to a different `mn` node — or even
        // appending a query param like ratebypass — invalidates that signature
        // and googlevideo replies 403 to every request. So probe the EXACT URL
        // we intend to play, byte unchanged, and only use it if it serves bytes.
        // If it 403s, return null so extractInternal falls back to HLS, then
        // the progressive (muxed) stream.
        return if (isUrlReachable(url, userAgent)) url else null
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            // The reachability probe pulls a full 1 MB range (playback-sized),
            // not just a byte — give the read enough room on slow links.
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun isUrlReachable(url: String, userAgent: String? = null): Boolean =
        runCatching {
            // Probe the exact URL unchanged with a playback-sized range.
            // A 1-byte probe (bytes=0-0) passes on URLs googlevideo then 403s
            // for real ranges, so the probe must request a full 1 MB chunk —
            // exactly what playback requests — to prove the URL actually
            // serves data. googlevideo binds signed URLs to the client
            // User-Agent that requested them, so the probe must carry the SAME
            // client UA the URL was signed for - probing an android-signed URL
            // with the android_vr UA (or vice versa) gets a false 403 and a
            // working stream is thrown away.
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-1048575")
                .header("User-Agent", userAgent ?: YouTubeClients.android.userAgent)
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
