package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity

object TrailerPlayerLauncher {

    private const val TAG = "TrailerLauncher"

    /** 3-hour cache of resolved playback sources per video ID. */
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
        val isStale: Boolean
            get() = System.currentTimeMillis() - cachedAt > CACHE_TTL_MS
    }

    private const val CACHE_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

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
     * Resolved sources are cached for 3 hours.
     */
    suspend fun resolvePlayableUrl(
        trailerUrlOrId: String
    ): Result<PlayableSource> {

        val videoId = extractVideoId(trailerUrlOrId)

        if (videoId == null) {
            Log.e(
                TAG,
                "Could not extract YouTube video ID: $trailerUrlOrId"
            )

            return Result.failure(
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
                return Result.failure(
                    IllegalStateException("Trailer $videoId recently failed to resolve")
                )
            }
            resolutionFailures.remove(videoId)
        }

        // Serve from cache if fresh
        sourceCache[videoId]?.let { cached ->
            if (!cached.isStale) {
                Log.w(TAG, "Trailer source cache hit for $videoId")
                return Result.success(cached.source)
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
            return Result.success(innerTubeSource)
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
                markFailed(videoId)
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
