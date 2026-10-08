package com.kennyb1201.kbstream.data.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient

/**
 * Parallel fetch lanes for heavy sources.
 *
 * A single OkHttp stream is one TCP connection, and an HTTP/2 connection
 * multiplexes every request onto it: all parallel reads share one window, so
 * the connection's own flow control — not the server and not the box — is what
 * caps throughput on a file far above realtime. A 67 Mbps 4K remux is where
 * that shows: the connection cannot hold much more than a few tens of Mbps of
 * goodput against a distant CDN, so the buffer drains at exactly the bitrate
 * the file needs.
 *
 * The fix is more than one connection: [STREAM_LANES] OkHttp instances, each
 * with its own connection pool and dispatcher, and each reader handed to one of
 * them round-robin. Independent pools are the point — sharing a pool would
 * reuse the same HTTP/2 connection and buy nothing. This is the shape Nuvio's
 * player uses (summarized from github.com/NuvioMedia/NuvioTV, GPL-3.0).
 *
 * Gated deliberately: only a source that is known to be heavy gets lanes. A
 * 1080p stream is nowhere near the single-connection ceiling, and giving it
 * four pools would cost four times the connections and handshakes for no
 * throughput. Everything under the gate keeps the app's one shared OkHttp
 * client — the warm DNS/TLS/TCP pool that makes a source switch fast.
 */

/**
 * The bitrate at or above which a source is worth more than one connection.
 *
 * ~20 Mbps: comfortably above what a 1080p stream needs (so those keep the
 * shared single-stream client, per the spec's own gate) and below the 4K
 * remux-tier bitrates — tens of Mbps — that a single connection cannot carry.
 */
internal const val STREAM_LANE_BITRATE_BPS = 20_000_000

/** How many lanes a heavy source gets. */
internal const val STREAM_LANES = 4

/**
 * How many fetch lanes a source should use.
 *
 * @param declaredBitrateBps the bitrate the session knows, 0 when it does not
 *   know one yet.
 * @param claimsUhd the source's own text claims 2160p/4K. Used only while the
 *   bitrate is unknown, because the first open happens before a track exists:
 *   a 4K remux is exactly the ~20+ Mbps case this gate is for, and a 4K claim
 *   that turns out to be a low-bitrate upscale is settled by the measured
 *   bitrate on the next reader rather than paid for forever.
 * @param isLive a live channel. Always one lane: a channel is one continuous
 *   stream a viewer zaps through, so four pools per zap is connection churn on
 *   the one screen where latency is the point, and a live channel plays at
 *   whatever rate the provider sends rather than seeking into a big file.
 */
internal fun fetchLanesFor(
    declaredBitrateBps: Int,
    claimsUhd: Boolean,
    isLive: Boolean = false
): Int {
    if (isLive) return 1
    val heavy = declaredBitrateBps >= STREAM_LANE_BITRATE_BPS ||
        (declaredBitrateBps == 0 && claimsUhd)
    return if (heavy) STREAM_LANES else 1
}

/**
 * A [DataSource.Factory] that hands every reader one of [lanes] independent
 * OkHttp stacks, round-robin.
 *
 * Each reader gets its own [OkHttpClient], so concurrent loads (and each
 * re-open a seek or a recovery causes) land on a different connection pool
 * instead of queueing behind one HTTP/2 window.
 *
 * Per-playback by construction: it is built inside the player's own
 * configuration and [release] drops every connection it opened, so the extra
 * pools exist exactly as long as the session does — no work outlives it.
 */
@UnstableApi
internal class LanePoolDataSourceFactory(
    private val lanes: Int,
    userAgent: String,
    headers: Map<String, String>,
    laneClient: () -> OkHttpClient
) : DataSource.Factory {

    private val clients: List<OkHttpClient> =
        List(lanes.coerceAtLeast(1)) { laneClient() }

    private val laneFactories: List<OkHttpDataSource.Factory> = clients.map { client ->
        OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
    }

    private val next = AtomicInteger(0)

    /** How many lanes this session is actually fetching on, for the report. */
    val laneCount: Int get() = laneFactories.size

    override fun createDataSource(): DataSource =
        laneFactories[Math.floorMod(next.getAndIncrement(), laneFactories.size)]
            .createDataSource()

    /**
     * Tears the lanes down with the session: every pool is emptied and every
     * in-flight call cancelled, so nothing keeps a socket or a reader thread
     * alive after the player that owned them is gone.
     */
    fun release() {
        clients.forEach { client ->
            runCatching { client.connectionPool.evictAll() }
            runCatching { client.dispatcher.cancelAll() }
        }
        next.set(0)
    }
}
