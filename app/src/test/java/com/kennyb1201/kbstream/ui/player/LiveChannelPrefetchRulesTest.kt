package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry.ZapChannel
import com.kennyb1201.kbstream.data.player.StreamUserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules behind the guide prefetch.
 *
 * The stakes are all silent: a mis-parsed media-playlist URI warms the wrong
 * host, a header set that does not match the tune gets the provider to refuse
 * the warm (so the tune pays the cold handshake it was supposed to avoid), and
 * a `file://`/`rtsp://` URL asked for over OkHttp throws where nobody is
 * looking. None of them surface as a visible failure - hence the coverage.
 */
class LiveChannelPrefetchRulesTest {

    private fun channel(
        id: String = "c1",
        url: String = "https://host/live/master.m3u8",
        headers: Map<String, String> = emptyMap()
    ) = ZapChannel(channelId = id, name = id, streamUrl = url, logoUrl = null, headers = headers)

    // --- Media-playlist extraction -----------------------------------------

    @Test
    fun `the media playlist is the URI line after EXT-X-STREAM-INF`() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nchunklist.m3u8\n"
        assertEquals(
            "https://host/live/chunklist.m3u8",
            LiveChannelPrefetchRules.mediaPlaylistUri(body, "https://host/live/master.m3u8")
        )
    }

    @Test
    fun `a relative media URI resolves against the master URL`() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nsub/media.m3u8\n"
        assertEquals(
            "https://host/live/sub/media.m3u8",
            LiveChannelPrefetchRules.mediaPlaylistUri(body, "https://host/live/master.m3u8")
        )
    }

    @Test
    fun `an absolute media URI passes through unchanged`() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/x/media.m3u8\n"
        assertEquals(
            "https://cdn.example/x/media.m3u8",
            LiveChannelPrefetchRules.mediaPlaylistUri(body, "https://host/live/master.m3u8")
        )
    }

    @Test
    fun `a quoted media URI has its quotes stripped`() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n\"chunklist.m3u8\"\n"
        assertEquals(
            "https://host/live/chunklist.m3u8",
            LiveChannelPrefetchRules.mediaPlaylistUri(body, "https://host/live/master.m3u8")
        )
    }

    @Test
    fun `a media playlist has no media URI to derive`() {
        val body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"
        assertNull(LiveChannelPrefetchRules.mediaPlaylistUri(body, "https://host/live/media.m3u8"))
    }

    @Test
    fun `a master playlist with no URI line derives nothing`() {
        val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n#EXT-X-ENDLIST\n"
        assertNull(LiveChannelPrefetchRules.mediaPlaylistUri(body, "https://host/live/master.m3u8"))
    }

    // --- Scheme gate --------------------------------------------------------

    @Test
    fun `only http and https are worth a prefetch`() {
        assertTrue(LiveChannelPrefetchRules.isHttpUrl("http://host/x.m3u8"))
        assertTrue(LiveChannelPrefetchRules.isHttpUrl("HTTPS://host/x.m3u8"))
        assertFalse(LiveChannelPrefetchRules.isHttpUrl("rtsp://host/x"))
        assertFalse(LiveChannelPrefetchRules.isHttpUrl("file:///sdcard/x.m3u8"))
        assertFalse(LiveChannelPrefetchRules.isHttpUrl(""))
        assertFalse(LiveChannelPrefetchRules.isHttpUrl(null))
    }

    // --- Header merge order -------------------------------------------------

    @Test
    fun `a channel User-Agent wins and is sent exactly once`() {
        val headers = LiveChannelPrefetchRules.headersFor(
            channel(headers = mapOf("User-Agent" to "custom/1", "Referer" to "https://ref/"))
        )
        assertEquals("custom/1", headers["User-Agent"])
        assertEquals("https://ref/", headers["Referer"])
        assertEquals(2, headers.size)
    }

    @Test
    fun `a channel with no User-Agent asks as the app default`() {
        val headers = LiveChannelPrefetchRules.headersFor(channel(headers = mapOf("Referer" to "r")))
        assertEquals(StreamUserAgent.DEFAULT, headers["User-Agent"])
    }

    @Test
    fun `blank channel headers are dropped`() {
        val headers = LiveChannelPrefetchRules.headersFor(
            channel(headers = mapOf("Referer" to "", "X-Keep" to "yes"))
        )
        assertFalse(headers.containsKey("Referer"))
        assertEquals("yes", headers["X-Keep"])
    }

    // --- TTL ----------------------------------------------------------------

    @Test
    fun `a warm prefetch counts until the TTL, then expires`() {
        assertFalse(LiveChannelPrefetchRules.isWarm(null, 1_000L))
        assertTrue(LiveChannelPrefetchRules.isWarm(1_000L, 1_000L))
        assertTrue(LiveChannelPrefetchRules.isWarm(1_000L, 1_000L + LiveChannelPrefetchRules.TTL_MS))
        assertFalse(
            LiveChannelPrefetchRules.isWarm(
                1_000L,
                1_001L + LiveChannelPrefetchRules.TTL_MS
            )
        )
    }

    @Test
    fun `a clock that went backwards is not warm`() {
        assertFalse(LiveChannelPrefetchRules.isWarm(1_000L, 999L))
    }
}
