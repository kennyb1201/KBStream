package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a bounded-range googlevideo chunk ends the stream.
 *
 * The bug behind "trailers keep stopping midplay" was reading any chunk that
 * served less than was asked for as the end of input. googlevideo truncates
 * chunks routinely, so that rule cut playback off at the first short chunk.
 * These pin the replacement: only an empty chunk (or one that consumes the
 * last of a known length) is the end.
 */
class YouTubeChunkingTest {

    @Test
    fun `a chunk that delivered nothing is the end of input`() {
        assertTrue(youTubeChunkEndsStream(0L, lengthKnown = false, remainingContentLength = -1L))
        assertTrue(youTubeChunkEndsStream(0L, lengthKnown = true, remainingContentLength = 5_000L))
    }

    @Test
    fun `a short chunk with an unknown length is not the end`() {
        // The regression: 1 MB asked for, 300 KB served, total length unknown.
        // Before this rule that truncated the trailer right there.
        assertFalse(
            youTubeChunkEndsStream(
                bytesDeliveredInChunk = 300_000L,
                lengthKnown = false,
                remainingContentLength = -1L
            )
        )
    }

    @Test
    fun `a full chunk with an unknown length is not the end`() {
        assertFalse(
            youTubeChunkEndsStream(
                bytesDeliveredInChunk = 1_048_576L,
                lengthKnown = false,
                remainingContentLength = -1L
            )
        )
    }

    @Test
    fun `a short chunk with length left over is not the end`() {
        assertFalse(
            youTubeChunkEndsStream(
                bytesDeliveredInChunk = 300_000L,
                lengthKnown = true,
                remainingContentLength = 9_000_000L
            )
        )
    }

    @Test
    fun `the first chunk is requested from byte 0`() {
        assertFalse(youTubeChunkUsesPosition(0L))
    }

    @Test
    fun `a later chunk is requested from the current position`() {
        // Any mid-stream chunk asks for the remainder from where we are, so it
        // carries no growing from-0 window (the mid-stream 403 that restarted
        // trailers).
        assertTrue(youTubeChunkUsesPosition(1L))
        assertTrue(youTubeChunkUsesPosition(1_048_576L))
        assertTrue(youTubeChunkUsesPosition(50_000_000L))
    }

    @Test
    fun `a chunk that consumes the known length ends the stream`() {
        assertTrue(
            youTubeChunkEndsStream(
                bytesDeliveredInChunk = 1_000_000L,
                lengthKnown = true,
                remainingContentLength = 1_000_000L
            )
        )
        assertTrue(
            youTubeChunkEndsStream(
                bytesDeliveredInChunk = 1_000_000L,
                lengthKnown = true,
                remainingContentLength = 400_000L
            )
        )
    }
}
