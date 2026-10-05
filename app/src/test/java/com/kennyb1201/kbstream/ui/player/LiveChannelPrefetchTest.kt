package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry.ZapChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefetcher's debounce, gating and fire, driven through injected seams so
 * nothing touches a socket or the main looper.
 *
 * The behaviours that matter are the ones that would otherwise be invisible:
 * focus must not fire a request per row the viewer passes over (debounce), a
 * `file://` or already-playing channel must never be fetched, and sitting on a
 * row must not re-fire inside the TTL. A wrong answer here is a wasted request
 * at best and a refused-but-unseen one at worst.
 */
class LiveChannelPrefetchTest {

    private class Harness {
        var now = 1_000L
        var currentId: String? = null
        var canFire = true
        var body: String? = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"

        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val cancelled = mutableListOf<Runnable>()
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var inFlightCancels = 0

        val prefetch = LiveChannelPrefetch(
            transport = object : PrefetchTransport {
                override fun get(url: String, headers: Map<String, String>, onBody: (String?) -> Unit) {
                    requests += url to headers
                    onBody(body)
                }

                override fun cancelInFlight() {
                    inFlightCancels++
                }
            },
            resolveHeaders = { mapOf("User-Agent" to "ua") },
            currentChannelId = { currentId },
            canFire = { canFire },
            nowMs = { now },
            schedule = { delay, block -> scheduled += delay to block },
            cancelScheduled = { block -> cancelled += block }
        )

        fun lastBlock(): Runnable = scheduled.last().second
    }

    private fun channel(
        id: String = "c1",
        url: String = "https://host/live/c1.m3u8"
    ) = ZapChannel(channelId = id, name = id, streamUrl = url, logoUrl = null)

    @Test
    fun `focus debounces then fires once after the delay`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        assertEquals(1, h.scheduled.size)
        assertEquals(
            LiveChannelPrefetchRules.FOCUS_DEBOUNCE_MS,
            h.scheduled.first().first
        )
        // Nothing is fetched until the debounce elapses.
        assertTrue(h.requests.isEmpty())
        h.lastBlock().run()
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `a second focus cancels the first`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel(id = "a", url = "https://host/live/a.m3u8"))
        val first = h.lastBlock()
        h.prefetch.onChannelFocused(channel(id = "b", url = "https://host/live/b.m3u8"))
        assertTrue(h.cancelled.contains(first))
        assertEquals(2, h.scheduled.size)
        // Running only the surviving block fetches only the row landed on.
        h.lastBlock().run()
        assertEquals(listOf("https://host/live/b.m3u8"), h.requests.map { it.first })
    }

    @Test
    fun `focus on nothing drops the pending fire`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        val pending = h.lastBlock()
        h.prefetch.onChannelFocused(null)
        assertTrue(h.cancelled.contains(pending))
        assertEquals(1, h.scheduled.size)
    }

    @Test
    fun `the channel already playing is never re-warmed`() {
        val h = Harness()
        h.currentId = "a"
        h.prefetch.onChannelFocused(channel(id = "a"))
        assertTrue(h.scheduled.isEmpty())
    }

    @Test
    fun `a non-http channel is skipped`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel(url = "rtsp://host/live/a"))
        assertTrue(h.scheduled.isEmpty())
    }

    @Test
    fun `a closed guide cancels the pending fire`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        val pending = h.lastBlock()
        h.prefetch.cancel()
        assertTrue(h.cancelled.contains(pending))
    }

    @Test
    fun `release drops the pending fire and aborts the in-flight request`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        val pending = h.lastBlock()
        h.prefetch.release()
        assertTrue(h.cancelled.contains(pending))
        assertEquals(1, h.inFlightCancels)
    }

    @Test
    fun `a plain cancel leaves the in-flight request alone`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        h.prefetch.cancel()
        assertEquals(0, h.inFlightCancels)
    }

    @Test
    fun `a failed warm does not mark the row warm`() {
        val h = Harness()
        h.body = null
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(1, h.requests.size)
        // Nothing connected, so the row is still cold: the next focus retries
        // instead of being skipped as already warmed for the whole TTL.
        assertFalse(h.prefetch.recentlyWarmed("c1"))
        h.now += 1_000L
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(2, h.requests.size)
    }

    @Test
    fun `a fire outside the guide does nothing`() {
        val h = Harness()
        h.canFire = false
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `a master playlist warms the media playlist too`() {
        val h = Harness()
        h.body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchunklist.m3u8\n"
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(
            listOf(
                "https://host/live/c1.m3u8",
                "https://host/live/chunklist.m3u8"
            ),
            h.requests.map { it.first }
        )
    }

    @Test
    fun `a media playlist warms once`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `sitting on a row does not re-fire inside the TTL`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(1, h.requests.size)

        // Re-focus the same row a moment later: one warm covers it.
        h.now += 1_000L
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `a re-fire past the TTL happens again`() {
        val h = Harness()
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()

        h.now += LiveChannelPrefetchRules.TTL_MS + 1
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertEquals(2, h.requests.size)
    }

    @Test
    fun `recentlyWarmed tracks the TTL`() {
        val h = Harness()
        assertFalse(h.prefetch.recentlyWarmed("c1"))
        h.prefetch.onChannelFocused(channel())
        h.lastBlock().run()
        assertTrue(h.prefetch.recentlyWarmed("c1"))
        h.now += LiveChannelPrefetchRules.TTL_MS + 1
        assertFalse(h.prefetch.recentlyWarmed("c1"))
    }
}
