package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The info panel's bitrate line.
 *
 * Two numbers feed it and they mean different things, so the things worth
 * pinning are which one is used, that the measured one is honestly labelled,
 * and that neither turns into a made-up number: the measured rate divides bytes
 * by playback time, and in the first seconds of a session that quotient is the
 * connection, not the file.
 */
class PlayerBitrateLabelTest {

    @Test
    fun `the declared rate wins when the container has one`() {
        // Even when a measurement exists, the exact declared value is shown
        // without the "(measured)" caveat.
        assertEquals(
            "Bitrate: 4500 kbps",
            bitrateLabel(declaredBps = 4_500_000, measuredBps = 0L)
        )
    }

    @Test
    fun `a source that declares nothing falls back to the measured rate`() {
        assertEquals(
            "Bitrate: 8000 kbps (measured)",
            bitrateLabel(declaredBps = 0, measuredBps = 8_000_000L)
        )
        // media3's NO_VALUE is -1, not 0: a negative declared rate must be
        // treated exactly like an absent one rather than rendered as "-1 kbps".
        assertEquals(
            "Bitrate: 8000 kbps (measured)",
            bitrateLabel(declaredBps = -1, measuredBps = 8_000_000L)
        )
    }

    @Test
    fun `nothing is shown when neither rate is known`() {
        assertNull(bitrateLabel(declaredBps = 0, measuredBps = 0L))
        assertNull(bitrateLabel(declaredBps = -1, measuredBps = 0L))
    }

    @Test
    fun `bytes over playback time is the rate`() {
        // 7.5 MB in 15 s of playback = 4 Mbps.
        assertEquals(
            4_000_000L,
            measuredBitrateBps(bytesLoaded = 7_500_000L, positionMs = 15_000L)
        )
        // Twice the bytes over the same time is twice the rate.
        assertEquals(
            8_000_000L,
            measuredBitrateBps(bytesLoaded = 15_000_000L, positionMs = 15_000L)
        )
    }

    @Test
    fun `the first seconds of a session measure the connection, not the file`() {
        // A burst of buffered-ahead data before anything has played is not a
        // bitrate. The helper refuses to answer until there is playback to
        // average against.
        assertEquals(
            0L,
            measuredBitrateBps(
                bytesLoaded = 50_000_000L,
                positionMs = MEASURED_BITRATE_MIN_POSITION_MS - 1
            )
        )
        assertTrue(
            "the first measurable moment must be inside the helper's constant",
            measuredBitrateBps(
                bytesLoaded = 50_000_000L,
                positionMs = MEASURED_BITRATE_MIN_POSITION_MS
            ) > 0L
        )
    }

    @Test
    fun `no progress and no bytes are both unmeasurable`() {
        assertEquals(0L, measuredBitrateBps(bytesLoaded = 0L, positionMs = 60_000L))
        assertEquals(0L, measuredBitrateBps(bytesLoaded = 1_000_000L, positionMs = 0L))
        assertEquals(0L, measuredBitrateBps(bytesLoaded = 1_000_000L, positionMs = -5L))
    }
}
