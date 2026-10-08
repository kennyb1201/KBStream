package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parallel fetch lanes, gated to the sources that can use them.
 *
 * One OkHttp stream is one TCP connection, and HTTP/2 multiplexes every request
 * onto it, so all parallel reads share a single window: a 67 Mbps 4K remux is
 * beyond what that connection can carry against a distant CDN no matter how
 * fast the line is. Several lanes, each on its own connection pool, is the fix
 * (the shape Nuvio's player uses, summarized from github.com/NuvioMedia/NuvioTV,
 * GPL-3.0).
 *
 * The gate matters as much as the lanes: a 1080p stream is nowhere near the
 * single-connection ceiling, and giving it four pools would cost four times the
 * connections and handshakes for no throughput. Everything under the gate keeps
 * the app's one shared, warm client.
 */
class StreamFetchLanesTest {

    @Test
    fun `a known heavy source gets lanes`() {
        assertEquals(STREAM_LANES, fetchLanesFor(declaredBitrateBps = STREAM_LANE_BITRATE_BPS, claimsUhd = false))
        assertEquals(STREAM_LANES, fetchLanesFor(declaredBitrateBps = 67_000_000, claimsUhd = false))
    }

    @Test
    fun `an ordinary source keeps the single stream`() {
        assertEquals(1, fetchLanesFor(declaredBitrateBps = 0, claimsUhd = false))
        assertEquals(1, fetchLanesFor(declaredBitrateBps = 8_000_000, claimsUhd = false))
        assertEquals(1, fetchLanesFor(declaredBitrateBps = AVERAGE_1080P_BPS, claimsUhd = false))
    }

    @Test
    fun `the gate is the spec's twenty megabits`() {
        assertEquals(20_000_000, STREAM_LANE_BITRATE_BPS)
        assertEquals(1, fetchLanesFor(declaredBitrateBps = STREAM_LANE_BITRATE_BPS - 1, claimsUhd = false))
    }

    @Test
    fun `a 4K claim earns lanes only while the bitrate is unknown`() {
        // The first open happens before a track exists, so the session knows no
        // bitrate yet: a source whose own text says 2160p is exactly the remux
        // case the gate is for.
        assertEquals(STREAM_LANES, fetchLanesFor(declaredBitrateBps = 0, claimsUhd = true))
        // Once a bitrate IS known, the measurement wins over the label: a 4K
        // claim that is really a low-bitrate upscale must not pay for four pools.
        assertEquals(1, fetchLanesFor(declaredBitrateBps = 6_000_000, claimsUhd = true))
        // And a heavy bitrate gets lanes whatever the label says.
        assertEquals(STREAM_LANES, fetchLanesFor(declaredBitrateBps = 67_000_000, claimsUhd = false))
    }

    @Test
    fun `a live channel keeps the single stream however heavy it claims to be`() {
        // A channel is one continuous stream that a viewer zaps through: four
        // pools per zap is connection churn exactly where latency is the point,
        // and a live channel plays at whatever rate the provider sends instead
        // of seeking into a big file.
        assertEquals(1, fetchLanesFor(declaredBitrateBps = 67_000_000, claimsUhd = true, isLive = true))
        assertEquals(1, fetchLanesFor(declaredBitrateBps = 0, claimsUhd = true, isLive = true))
    }

    @Test
    fun `the lane count is the spec's four and is not a knob for one stream`() {
        assertEquals(4, STREAM_LANES)
        assertTrue("one lane would be the single stream with extra steps", STREAM_LANES > 1)
    }

    @Test
    fun `a lane pool builds one data source per lane and stays usable`() {
        val factory = LanePoolDataSourceFactory(
            lanes = STREAM_LANES,
            userAgent = "KBStream/test",
            headers = mapOf("Referer" to "https://example.invalid/"),
            laneClient = { okhttp3.OkHttpClient() }
        )
        assertEquals(STREAM_LANES, factory.laneCount)
        repeat(STREAM_LANES * 2) {
            assertNotNull("every reader gets a lane", factory.createDataSource())
        }
    }

    @Test
    fun `release evicts every pool and leaves the factory reusable`() {
        val factory = LanePoolDataSourceFactory(
            lanes = STREAM_LANES,
            userAgent = "KBStream/test",
            headers = emptyMap(),
            laneClient = { okhttp3.OkHttpClient() }
        )
        factory.release()
        factory.release()
        // Nothing here may throw, and the session tearing down is not a reason
        // the object becomes unusable: a rebuild builds a fresh pool anyway.
        assertNotNull(factory.createDataSource())
    }

    @Test
    fun `a zero lane request still yields one lane rather than an empty pool`() {
        val factory = LanePoolDataSourceFactory(
            lanes = 0,
            userAgent = "KBStream/test",
            headers = emptyMap(),
            laneClient = { okhttp3.OkHttpClient() }
        )
        assertEquals(1, factory.laneCount)
        assertNotNull(factory.createDataSource())
    }

    private companion object {
        /** Around what a 1080p stream asks for, well under the gate. */
        const val AVERAGE_1080P_BPS = 12_000_000
    }
}
