package com.kennyb1201.kbstream.ui.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.player.StreamUserAgent
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "LIVE_PREFETCH"

/**
 * The pure parts of the live prefetch, kept apart from the Android/network
 * wiring so they can be tested without a TV or a socket.
 */
internal object LiveChannelPrefetchRules {

    /** Bytes of a playlist read before the request is abandoned. */
    const val MAX_BODY_BYTES = 64 * 1024

    /** How long a warm prefetch counts as useful (see [LiveChannelPrefetch]). */
    const val TTL_MS = 15_000L

    /** How long the guide must rest on a row before it is worth warming. */
    const val FOCUS_DEBOUNCE_MS = 400L

    private const val USER_AGENT = "User-Agent"

    private val STREAM_INF = Regex("^#EXT-X-STREAM-INF", RegexOption.IGNORE_CASE)

    /** True for the schemes the player can actually fetch. */
    fun isHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** Whether a fire at [firedAtMs] still counts as warm at [nowMs]. */
    fun isWarm(firedAtMs: Long?, nowMs: Long): Boolean {
        if (firedAtMs == null) return false
        val age = nowMs - firedAtMs
        return age in 0..TTL_MS
    }

    /**
     * The headers a tune of [channel] would send.
     *
     * The channel's own headers (minus its User-Agent, which is carried
     * separately) plus the resolved agent as THE User-Agent - the same split
     * `createPlayer()` makes, because the prefetch has to look like the tune to
     * a provider that gates on User-Agent / Referer.
     */
    fun headersFor(channel: LiveChannelZapRegistry.ZapChannel): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        channel.headers.forEach { (key, value) ->
            if (value.isNotBlank()) merged[key] = value
        }
        // Resolved last and under the canonical casing, so a case-variant
        // User-Agent in the channel headers cannot send the header twice.
        merged[USER_AGENT] = StreamUserAgent.resolve(channel.headers)
        return merged
    }

    /**
     * The first media-playlist URI in a master playlist [body], resolved against
     * [baseUrl], or null when [body] is not a master playlist (a media playlist
     * carries no `#EXT-X-STREAM-INF`) or names no URI.
     *
     * Handles the quoted and relative spellings some providers use: the URI is
     * whatever non-comment line follows the first `#EXT-X-STREAM-INF`, with
     * surrounding quotes stripped and resolved relative to the playlist URL.
     */
    fun mediaPlaylistUri(body: String, baseUrl: String): String? {
        val lines = body.lineSequence().map { it.trim() }.toList()
        val marker = lines.indexOfFirst { STREAM_INF.containsMatchIn(it) }
        if (marker < 0) return null
        val uriLine = lines.drop(marker + 1)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        val raw = uriLine.removeSurrounding("\"").trim()
        if (raw.isEmpty()) return null
        return resolveUrl(baseUrl, raw)
    }

    /** [raw] resolved against [baseUrl]; an absolute [raw] passes through. */
    fun resolveUrl(baseUrl: String, raw: String): String? =
        runCatching { java.net.URI(baseUrl).resolve(raw).toString() }.getOrNull()
}

/**
 * How the prefetch performs one GET.
 *
 * Split out so the debounce/TTL logic can be exercised without a socket; the
 * production build is [okHttpPrefetchTransport], which reuses the client the
 * player itself uses.
 */
internal fun interface PrefetchTransport {
    /**
     * GETs [url] with [headers] and hands at most
     * [LiveChannelPrefetchRules.MAX_BODY_BYTES] bytes to [onBody] off the main
     * thread (null when the request failed). Never throws.
     */
    fun get(url: String, headers: Map<String, String>, onBody: (String?) -> Unit)

    /**
     * Aborts the request [get] most recently started, if it is still in flight.
     * Called when the guide closes, where its warm-up is no longer worth a
     * socket. Best-effort; a no-op for transports without cancellable requests.
     */
    fun cancelInFlight() {}
}

/** [PrefetchTransport] backed by [client], reading a bounded body and closing. */
internal fun okHttpPrefetchTransport(client: OkHttpClient): PrefetchTransport {
    // The call the last [get] started, so a dismiss can abort it instead of
    // letting a warm-up the viewer has left finish against a closed guide. Held
    // in an atomic because [get] runs on the main thread while the cancel may
    // not.
    val inFlight = AtomicReference<Call?>(null)
    return object : PrefetchTransport {
        override fun get(url: String, headers: Map<String, String>, onBody: (String?) -> Unit) {
            val request = Request.Builder().url(url).get().apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
            runCatching {
                val call = client.newCall(request)
                inFlight.set(call)
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.d(TAG, "prefetch missed $url: ${e.message}")
                            onBody(null)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use { res -> onBody(readBoundedBody(res)) }
                        }
                    }
                )
            }.onFailure {
                Log.d(TAG, "prefetch failed: ${it.message}")
                onBody(null)
            }
        }

        override fun cancelInFlight() {
            inFlight.getAndSet(null)?.cancel()
        }
    }
}

/**
 * Reads no further than the cap: a direct-`.ts` URL must not be pulled down in
 * full just because focus landed on it.
 */
private fun readBoundedBody(response: Response): String? = runCatching {
    response.peekBody(LiveChannelPrefetchRules.MAX_BODY_BYTES.toLong()).string()
}.getOrNull()

/**
 * Warms the provider connection (DNS + TLS + TCP) and, for an HLS master
 * playlist, the media playlist itself for the channel the in-player guide is
 * resting on - so the next tune opens on a live socket.
 *
 * Why: every tune is a first contact to the network. `createPlayer()` used to
 * build a fresh OkHttpClient per player, so even the connection pool was thrown
 * away with it; this shares the one client (see the activity's `httpClient`)
 * and pays the handshake and the first playlist bytes while the viewer is still
 * browsing. Media3 re-fetches everything on the tune itself - the value is the
 * warmth, not a cached response.
 *
 * Deliberately best-effort and silent: it never touches the UI, swallows every
 * failure, and skips a channel warmed within [LiveChannelPrefetchRules.TTL_MS].
 */
internal class LiveChannelPrefetch(
    private val transport: PrefetchTransport,
    private val resolveHeaders: (LiveChannelZapRegistry.ZapChannel) -> Map<String, String>,
    private val currentChannelId: () -> String?,
    private val canFire: () -> Boolean,
    private val nowMs: () -> Long,
    private val schedule: (delayMs: Long, block: Runnable) -> Unit,
    private val cancelScheduled: (Runnable) -> Unit
) {

    /**
     * Production wiring: the shared [client], with the debounce posted to the
     * main looper.
     */
    constructor(
        client: OkHttpClient,
        resolveHeaders: (LiveChannelZapRegistry.ZapChannel) -> Map<String, String>,
        currentChannelId: () -> String?,
        canFire: () -> Boolean
    ) : this(
        transport = okHttpPrefetchTransport(client),
        resolveHeaders = resolveHeaders,
        currentChannelId = currentChannelId,
        canFire = canFire,
        nowMs = System::currentTimeMillis,
        schedule = { delay, block -> mainHandler.postDelayed(block, delay) },
        cancelScheduled = { block -> mainHandler.removeCallbacks(block) }
    )

    private var pending: Runnable? = null
    // Written off the main thread now that a warm is stamped only once its
    // request connects, and read on the main thread by [recentlyWarmed].
    private val lastFiredAt = ConcurrentHashMap<String, Long>()

    /**
     * The guide row that just took focus.
     *
     * The fire is debounced: browsing moves focus one row at a time and only a
     * row the viewer rests on is worth warming. A fresh focus cancels the
     * previous one, so the row passed over on the way down is never fetched.
     */
    fun onChannelFocused(channel: LiveChannelZapRegistry.ZapChannel?) {
        cancel()
        if (channel == null) return
        // Nothing to warm for the channel already playing.
        if (channel.channelId == currentChannelId()) return
        if (!LiveChannelPrefetchRules.isHttpUrl(channel.streamUrl)) return
        val block = Runnable {
            pending = null
            fire(channel)
        }
        pending = block
        schedule(LiveChannelPrefetchRules.FOCUS_DEBOUNCE_MS, block)
    }

    /** Drops the pending fire - the guide closed before the debounce elapsed. */
    fun cancel() {
        pending?.let(cancelScheduled)
        pending = null
    }

    /**
     * The guide closed: drop the pending fire and abort a warm-up already on the
     * wire, so a request for a row the viewer has left cannot resolve later and
     * hold a connection open for nothing.
     */
    fun release() {
        cancel()
        transport.cancelInFlight()
    }

    /** True when [channelId] was warmed within [LiveChannelPrefetchRules.TTL_MS]. */
    fun recentlyWarmed(channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        return LiveChannelPrefetchRules.isWarm(lastFiredAt[channelId], nowMs())
    }

    private fun fire(channel: LiveChannelZapRegistry.ZapChannel) {
        if (!canFire()) return
        val now = nowMs()
        // Sitting on a row re-fires focus; a second warm inside the TTL is pure
        // waste, because Media3 fetches the playlist itself either way.
        if (LiveChannelPrefetchRules.isWarm(lastFiredAt[channel.channelId], now)) return
        val headers = resolveHeaders(channel)
        // Stamp the warmth only once the request actually connects. Stamping it
        // up front marked a failed warm as done, so the row was skipped for the
        // whole TTL and the connection it was meant to pay for never happened.
        warm(channel.streamUrl, headers, deriveMediaPlaylist = true) {
            lastFiredAt[channel.channelId] = now
        }
    }

    /**
     * One GET whose body is read and discarded. A master playlist is asked for
     * its first media playlist too, which is the request the tune's own manifest
     * load will repeat. Best-effort: any parse failure leaves the single warm.
     */
    private fun warm(
        url: String,
        headers: Map<String, String>,
        deriveMediaPlaylist: Boolean,
        onConnected: () -> Unit = {}
    ) {
        if (!LiveChannelPrefetchRules.isHttpUrl(url)) return
        transport.get(url, headers) { body ->
            if (body == null) return@get
            onConnected()
            if (!deriveMediaPlaylist) return@get
            val media = LiveChannelPrefetchRules.mediaPlaylistUri(body, url) ?: return@get
            if (media == url) return@get
            warm(media, headers, deriveMediaPlaylist = false)
        }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
