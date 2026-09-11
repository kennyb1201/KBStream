package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TrailerPlayerLauncher {

    private const val TAG = "TrailerLauncher"

    /**
     * Serializes resolve chains so concurrent callers for the same (or any)
     * video coalesce behind one network flight: the hero pre-warm and the
     * post-dwell resolve would otherwise both run the full InnerTube →
     * NewPipe → Piped chain, and hammering YouTube's anonymous player API
     * with duplicate calls is exactly what invites its bot-gating.
     */
    private val resolveMutex = Mutex()

    /** Cache of resolved playback sources per video ID (expire-based TTL). */
    private val sourceCache =
        java.util.concurrent.ConcurrentHashMap<String, CachedSource>()

    /**
     * Short-lived failure memory: videos that failed to resolve (region-
     * blocked, removed, resolver outage) are not retried on every hero
     * rotation. Without this, a single unavailable trailer re-runs the full
     * InnerTube → NewPipe → Piped chain (~5s of network churn) every time the
     * user's cursor passes over its card.
     */
    private val resolutionFailures =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val FAILURE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    /** Drops any cached source for [videoId] so the next resolve fetches a fresh signed URL. */
    fun invalidate(videoId: String) {
        sourceCache.remove(videoId)
    }

    private fun markFailed(videoId: String) {
        resolutionFailures[videoId] = System.currentTimeMillis()
    }

    private data class CachedSource(
        val source: PlayableSource,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        /**
         * Staleness is driven by the URL's own `expire` param, not a fixed
         * cache age. googlevideo signed URLs historically live ~6h while
         * the param advertises; keeping a URL past its expire is what 403s
         * mid-rotation. Fallback: 6h (well under the historical URL life).
         */
        val isStale: Boolean
            get() {
                val expiresAt = expiresAtOf(source)
                    ?: return fallbackStale(cachedAt)
                return System.currentTimeMillis() >= expiresAt
            }
    }

    /** Earliest `expire` claim across the source's URL(s), or null if absent/unparseable. */
    private fun expiresAtOf(source: PlayableSource): Long? {
        val urls = when (source) {
            is PlayableSource.Muxed -> listOf(source.url)
            is PlayableSource.Adaptive -> listOf(source.videoUrl, source.audioUrl)
        }
        return urls.mapNotNull { url ->
            runCatching {
                Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000L)
            }.getOrNull()
        }.minOrNull()
    }

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private fun fallbackStale(cachedAt: Long): Boolean =
        System.currentTimeMillis() - cachedAt > CACHE_TTL_MS

    /**
     * Resolves a YouTube URL/ID down to a playable stream source
     * without launching any UI.
     *
     * Resolution order:
     * 1. InnerTube direct player API (runs from the device's own network —
     *    a TV at home is on a residential IP, where YouTube still accepts
     *    anonymous player requests without any po-token infrastructure)
     * 2. NewPipe extractor (NewPipeManager falls back to Piped itself)
     *
     * Cached sources go stale when the signed URL's own `expire` param
     * passes (fallback: 6h) — never later than the URL can actually serve.
     */
    suspend fun resolvePlayableUrl(
        trailerUrlOrId: String,
        /**
         * False for background pre-warms: a speculative resolve that fails
         * (network hiccup, transient extractor outage) must NOT poison the
         * real post-dwell resolve via the failure cache.
         */
        recordFailure: Boolean = true
    ): Result<PlayableSource> = resolveMutex.withLock {

        val videoId = extractVideoId(trailerUrlOrId)

        if (videoId == null) {
            Log.e(
                TAG,
                "Could not extract YouTube video ID: $trailerUrlOrId"
            )

            return@withLock Result.failure(
                IllegalArgumentException(
                    "Could not extract YouTube video ID"
                )
            )
        }

        Log.w(
            TAG,
            "Extracted YouTube video ID: $videoId"
        )

        // Fail fast for videos that just failed to resolve — retrying a
        // region-blocked video through every resolver on each hero rotation
        // only adds seconds of network churn for a known-dead result.
        resolutionFailures[videoId]?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) {
                Log.w(TAG, "Skipping resolution for $videoId (recently failed)")
                return@withLock Result.failure(
                    IllegalStateException("Trailer $videoId recently failed to resolve")
                )
            }
            resolutionFailures.remove(videoId)
        }

        // Serve from cache if fresh
        sourceCache[videoId]?.let { cached ->
            if (!cached.isStale) {
                Log.w(TAG, "Trailer source cache hit for $videoId")
                return@withLock Result.success(cached.source)
            }
            sourceCache.remove(videoId)
        }

        // Primary: direct InnerTube player API. This runs from the device's
        // own network. A TV at home is on a residential IP — the exact kind
        // of network YouTube does NOT flag — so anonymous client requests
        // work here with no po-token/proxy infrastructure at all.
        val innerTubeSource = InnerTubeExtractor.extractPlaybackSource(videoId)
        if (innerTubeSource != null) {
            sourceCache[videoId] = CachedSource(innerTubeSource)
            resolutionFailures.remove(videoId)
            logResolved("InnerTube", innerTubeSource)
            return@withLock Result.success(innerTubeSource)
        }
        Log.w(TAG, "InnerTube extraction failed; falling back to NewPipe")

        // Fallback: NewPipe (with its internal Piped fallback)
        return NewPipeManager
            .getPlayableUrl(videoId)
            .onSuccess { source ->
                sourceCache[videoId] = CachedSource(source)
                resolutionFailures.remove(videoId)
                logResolved("NewPipe/Piped", source)
            }
            .onFailure { error ->
                if (recordFailure) {
                    markFailed(videoId)
                }
                Log.e(
                    TAG,
                    "All trailer resolvers failed (InnerTube + NewPipe/Piped)",
                    error
                )
            }
    }

    private fun logResolved(resolver: String, source: PlayableSource) {
        when (source) {
            is PlayableSource.Muxed -> {
                Log.w(
                    TAG,
                    "Trailer resolved via $resolver: host=" +
                        originHost(source.url) +
                        " ua=" + (source.userAgent ?: "default") +
                        " url=" +
                        source.url.take(120)
                )
            }

            is PlayableSource.Adaptive -> {
                Log.w(
                    TAG,
                    "Trailer resolved via $resolver: videoHost=" +
                        originHost(source.videoUrl) +
                        " audioHost=" +
                        originHost(source.audioUrl) +
                        " ua=" + (source.userAgent ?: "default") +
                        " video=" +
                        source.videoUrl.take(120)
                )
            }
        }
    }

    /** Fire TV suppresses debug-level logs, so resolution traces are warnings. */
    private fun originHost(url: String): String =
        runCatching {
            android.net.Uri.parse(url).host
        }.getOrNull() ?: "?"

    suspend fun playTrailer(
        context: Context,
        trailerUrlOrId: String
    ) {
        Log.d(
            TAG,
            "playTrailer called: $trailerUrlOrId"
        )

        val source =
            resolvePlayableUrl(trailerUrlOrId)
                .getOrElse { error ->
                    Log.e(
                        TAG,
                        "Failed to resolve playable trailer URL",
                        error
                    )
                    return
                }

        // Signing client's User-Agent (null for NewPipe/Piped sources).
        val sourceUserAgent = when (source) {
            is PlayableSource.Muxed -> source.userAgent
            is PlayableSource.Adaptive -> source.userAgent
        }

        val intent =
            Intent(
                context,
                NativePlayerActivity::class.java
            ).apply {

                when (source) {
                    is PlayableSource.Muxed -> {
                        putExtra(
                            "stream_url",
                            source.url
                        )
                    }

                    is PlayableSource.Adaptive -> {
                        putExtra(
                            "stream_url",
                            source.videoUrl
                        )

                        putExtra(
                            "audio_url",
                            source.audioUrl
                        )
                    }
                }

                // googlevideo only serves a signed URL to the UA of the
                // client it was signed for. The fullscreen player applies
                // "stream_headers" over its own default UA.
                sourceUserAgent?.let { ua ->
                    putExtra("stream_headers", "User-Agent: $ua")
                }

                putExtra(
                    "parent_type",
                    "movie"
                )

                putExtra(
                    "item_name",
                    "Trailer"
                )
            }

        Log.w(
            TAG,
            "Launching PlayerActivity for trailer"
        )

        context.startActivity(intent)
    }

    private fun extractVideoId(
        value: String
    ): String? {

        val input = value.trim()

        if (input.isBlank()) {
            return null
        }

        /*
         * Already a YouTube video ID.
         */
        if (
            !input.contains("/") &&
            !input.contains("?") &&
            !input.contains("&") &&
            !input.contains("=") &&
            input.length in 8..20
        ) {
            return input
        }

        return runCatching {

            val uri = Uri.parse(input)

            val host =
                uri.host
                    ?.lowercase()
                    .orEmpty()

            when {

                host == "youtu.be" ||
                    host.endsWith(".youtu.be") -> {

                    uri.pathSegments
                        .firstOrNull()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                }

                host == "youtube.com" ||
                    host.endsWith(".youtube.com") -> {

                    uri.getQueryParameter("v")
                        ?: when (
                            uri.pathSegments
                                .firstOrNull()
                                ?.lowercase()
                        ) {

                            "embed" ->
                                uri.pathSegments
                                    .getOrNull(1)

                            "shorts" ->
                                uri.pathSegments
                                    .getOrNull(1)

                            "live" ->
                                uri.pathSegments
                                    .getOrNull(1)

                            else ->
                                null
                        }
                }

                else ->
                    null
            }

        }.getOrNull()
    }
}
