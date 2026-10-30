package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.evictOldest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TrailerPlayerLauncher : MemoryPressure.CacheOwner {

    /**
     * Launch extra carrying the YouTube video id of a trailer session.
     *
     * The player needs it to tell a trailer from the title it opened as: a
     * player-visible 403 on a googlevideo URL in a TRAILER session is YouTube
     * refusing the client identity, which the session reports by invalidating
     * the cached watch config and closing cleanly instead of showing the
     * main-content error ladder.
     */
    const val EXTRA_TRAILER_VIDEO_ID = "trailer_video_id"

    private const val TAG = "TrailerLauncher"

    /**
     * Serializes resolve chains so concurrent callers for the same (or any)
     * video coalesce behind one network flight: the hero pre-warm and the
     * post-dwell resolve would otherwise both run the full InnerTube →
     * NewPipe → Piped chain, and hammering YouTube's anonymous player API
     * with duplicate calls is exactly what invites its bot-gating.
     */
    private val resolveMutex = Mutex()

    /**
     * Cache of resolved playback sources per video ID (expire-based TTL),
     * capped at [MAX_SOURCE_ENTRIES] (see [pruneCaches]).
     */
    private val sourceCache =
        java.util.concurrent.ConcurrentHashMap<String, CachedSource>()

    /**
     * Short-lived failure memory: videos that failed to resolve (region-
     * blocked, removed, resolver outage) are not retried on every hero
     * rotation. Without this, a single unavailable trailer re-runs the full
     * InnerTube → NewPipe → Piped chain (~5s of network churn) every time the
     * user's cursor passes over its card. Capped at [MAX_FAILURE_ENTRIES].
     */
    private val resolutionFailures =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val FAILURE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Videos YouTube refused even AFTER the watch config was rebuilt: the
     * stream 403'd on a fresh visitor too, so re-signing again is not going to
     * help this session.
     *
     * Session-scoped on purpose (an in-memory set, gone with the process): the
     * refusal is about the identity this process is holding, not about the
     * video forever. Kept apart from [resolutionFailures] because that one is a
     * short-lived "don't hammer a resolve that just failed" throttle, while this
     * is "do not resolve this at all until something explicit asks again".
     * Neither is capped: the only writer is a playback failure, so the set can
     * hold no more ids than the viewer sat through trailers for.
     */
    private val identityBlocked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Caps for the two caches above, which live for the whole process (this is
     * an `object`).
     *
     * Nothing used to remove an entry except a later lookup of the *same* video
     * id, so browsing past hundreds of hero titles kept every resolved source
     * and every failed id resident for the session. That is the same shape of
     * leak the TMDB caches had, and bounded here the same way: an evicted
     * source costs one network resolve to rebuild, an evicted failure just a
     * retry.
     */
    private const val MAX_SOURCE_ENTRIES = 100
    private const val MAX_FAILURE_ENTRIES = 200

    init {
        // Observed but NOT releasable, which is why this is CacheOwner and not
        // Releasable: the sizes are worth seeing in diagnostics, but dropping a
        // cached source means re-resolving it over the network against
        // YouTube's anonymous player API, and that churn is what these caches
        // exist to prevent. Pressure must not take them.
        MemoryPressure.register(this)
    }

    /**
     * Brings both caches back under their caps, oldest first.
     *
     * Called from inside [resolveMutex], which serializes every resolve, so
     * there is no concurrent writer to race the eviction.
     */
    private fun pruneCaches() {
        // Shared with the TMDB caches (data.memory.evictOldest): same rule, and
        // these two maps differ only in where the timestamp lives -- the source
        // in its value's cachedAt, the failure in the value itself.
        evictOldest(sourceCache, { it.cachedAt }, MAX_SOURCE_ENTRIES)
        evictOldest(resolutionFailures, { it }, MAX_FAILURE_ENTRIES)
    }

    /** Both caches against their caps, for the diagnostics dump. */
    override fun cacheStats(): String =
        "trailer: sources=${sourceCache.size}/$MAX_SOURCE_ENTRIES" +
            " failures=${resolutionFailures.size}/$MAX_FAILURE_ENTRIES"

    /**
     * Drops any cached source for [videoId] so the next resolve fetches a fresh
     * signed URL, and lifts a previous [markIdentityBlocked]: an explicit
     * invalidation is a caller saying "try this one again".
     */
    fun invalidate(videoId: String) {
        sourceCache.remove(videoId)
        identityBlocked.remove(normalizeId(videoId))
    }

    /**
     * Records that YouTube refused [videoIdOrUrl] even on a freshly fetched
     * watch config (see [isYouTubeIdentityBlocked] and its callers). Resolves
     * for it short-circuit until [invalidate] or process death.
     */
    fun markIdentityBlocked(videoIdOrUrl: String) {
        val videoId = normalizeId(videoIdOrUrl)
        identityBlocked.add(videoId)
        // The signed URL for it is worthless too, so drop it: a later manual
        // invalidate then has nothing stale to serve.
        sourceCache.remove(videoId)
        Log.w(
            TAG,
            "Trailer $videoId refused by YouTube on a fresh identity; blocked for this session"
        )
    }

    /**
     * True when this video is known to be refused by the YouTube identity this
     * process holds. Accepts the id or a watch URL, like [resolvePlayableUrl].
     */
    fun isIdentityBlocked(videoIdOrUrl: String): Boolean =
        identityBlocked.contains(normalizeId(videoIdOrUrl))

    /**
     * The client that signed the source this process last resolved for
     * [videoIdOrUrl], or null when it is not known.
     *
     * Read at a trailer death, BEFORE the caller invalidates anything: the
     * resume has to exclude exactly this client, and the cached entry it is
     * recorded on is what makes the answer video-specific. The trace's last
     * resolve is the fallback for a cache entry already gone (evicted, or a
     * resolve from a previous launch that this one served from the cache).
     */
    fun signingClient(videoIdOrUrl: String): String? =
        sourceCache[normalizeId(videoIdOrUrl)]?.client ?: PoTokenTrace.resolveClient()

    /**
     * The client recorded on THIS video's own cached entry, or null when there
     * is none - deliberately WITHOUT [signingClient]'s trace fallback.
     *
     * This is the exclusion's source (see HeroTrailerResumeLadder): the set has
     * to grow by the client that actually signed the URL that died, and the
     * trace's answer is whatever resolve ran last in the process - a pre-warm
     * for another title, a hero rotation - so excluding it would skip a client
     * that never signed this trailer and hide the class that did. Null means
     * "unknown client": the ladder grows nothing and relies on its attempt cap.
     */
    fun cachedSigningClient(videoIdOrUrl: String): String? =
        sourceCache[normalizeId(videoIdOrUrl)]?.client

    /**
     * The stored form of a video key: the extracted id when one can be read, so
     * the hero (which passes a bare id) and the detail screen (which passes a
     * watch URL) agree on the entry.
     */
    private fun normalizeId(videoIdOrUrl: String): String =
        extractVideoId(videoIdOrUrl) ?: videoIdOrUrl.trim()

    private fun markFailed(videoId: String) {
        resolutionFailures[videoId] = System.currentTimeMillis()
    }

    private data class CachedSource(
        val source: PlayableSource,
        /**
         * The client whose response carried the winning source's URL(s), or
         * null for the NewPipe/Piped fallback (which names no client).
         *
         * Kept beside the source because a trailer that dies at its first chunk
         * boundary has to be re-resolved EXCLUDING this client: its URL class
         * refuses every offset read, so re-signing with it only produces
         * another URL that dies at the same place.
         */
        val client: String? = null,
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
        recordFailure: Boolean = true,
        /**
         * The clients whose URL classes have died, skipped when the winners are
         * chosen (see [InnerTubeExtractor.extractPlaybackSourceWithClient]).
         *
         * A SET and not one key: the hero's resume ladder excludes one class per
         * step, and the next step has to exclude the first two (see
         * HeroTrailerResumeLadder).
         *
         * A non-empty set also BYPASSES the per-video cache: the cached URL may
         * be exactly the class being excluded, so answering the resume from it
         * would hand back the dead source and look like the resume did nothing.
         * The replacement is stored, so the next ordinary resolve serves the
         * working source.
         */
        excludeClients: Set<String> = emptySet()
    ): Result<PlayableSource> = resolveMutex.withLock {

        // Keep the two process-wide caches bounded (see [pruneCaches]). Every
        // resolve runs under resolveMutex, so this is both the only writer and
        // the only pruner.
        pruneCaches()

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

        // A video YouTube already refused on a fresh identity stays refused:
        // skip the watch-page fetch and the whole resolve. The hero re-arms on
        // every foreground return, so without this a refused trailer burns a
        // config fetch + full resolve each time it cycles back around.
        if (identityBlocked.contains(videoId)) {
            Log.w(TAG, "Skipping resolution for $videoId (refused by YouTube on a fresh identity)")
            return@withLock Result.failure(
                IllegalStateException("Trailer $videoId is blocked by YouTube for this session")
            )
        }

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

        // Serve from cache if fresh - unless this is an exclusion resolve, whose
        // whole point is a source DIFFERENT from the one the cache holds.
        if (excludeClients.isEmpty()) {
            sourceCache[videoId]?.let { cached ->
                if (!cached.isStale) {
                    Log.w(TAG, "Trailer source cache hit for $videoId")
                    return@withLock Result.success(cached.source)
                }
                sourceCache.remove(videoId)
            }
        }

        // Primary: direct InnerTube player API. This runs from the device's
        // own network. A TV at home is on a residential IP — the exact kind
        // of network YouTube does NOT flag — so anonymous client requests
        // work here with no po-token/proxy infrastructure at all.
        val resolved = InnerTubeExtractor.extractPlaybackSourceWithClient(
            videoId,
            excludeClients = excludeClients
        )
        if (resolved != null) {
            sourceCache[videoId] = CachedSource(resolved.source, resolved.client)
            resolutionFailures.remove(videoId)
            logResolved("InnerTube", resolved.source)
            return@withLock Result.success(resolved.source)
        }
        Log.w(TAG, "InnerTube extraction failed; falling back to NewPipe")

        // Fallback: NewPipe (with its internal Piped fallback)
        return NewPipeManager
            .getPlayableUrl(videoId)
            .onSuccess { source ->
                // NewPipe names no InnerTube client, so the entry carries none:
                // a later death starts its exclusion from the trace's fallback
                // rather than from a client that never signed anything.
                sourceCache[videoId] = CachedSource(source, client = null)
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

    internal fun logResolved(resolver: String, source: PlayableSource) {
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

/** A resolved source's VIDEO url: the one a trailer's format lives on. */
internal fun PlayableSource.signedUrl(): String = when (this) {
    is PlayableSource.Muxed -> url
    is PlayableSource.Adaptive -> videoUrl
}

/**
 * The shape a death line names for this source, in the trace's own vocabulary
 * (`hls | adaptive | progressive`).
 *
 * Read from the SOURCE rather than from `PoTokenTrace.resolveKind`, which is
 * whatever resolve ran last in the process: the Detail hero's death line has to
 * describe the stream that just died, not another title's pre-warm.
 */
internal fun PlayableSource.deathKind(): String = when (this) {
    is PlayableSource.Muxed ->
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) "hls"
        else "progressive"

    is PlayableSource.Adaptive -> "adaptive"
}

/**
 * A googlevideo signed URL's `name` query parameter, or null.
 *
 * [googlevideoItag] and [googlevideoBytes] are the two facts a mid-play death
 * has to carry: which FORMAT died (the `itag` a capture can compare against a
 * later attempt's) and how big the stream is (which turns the chunk that killed
 * it into a media-time skip).
 */
internal fun googlevideoParam(url: String, name: String): String? =
    runCatching { Uri.parse(url).getQueryParameter(name) }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** The `itag` of the format a googlevideo URL serves, or null. */
internal fun googlevideoItag(url: String): String? = googlevideoParam(url, "itag")

/** The stream's byte length as its own signed URL claims it (`clen`), or null. */
internal fun googlevideoBytes(url: String): Long? =
    googlevideoParam(url, "clen")?.toLongOrNull()?.takeIf { it > 0L }
