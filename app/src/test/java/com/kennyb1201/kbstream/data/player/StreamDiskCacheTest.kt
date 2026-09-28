package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How much disk the player's read-ahead cache may hold.
 *
 * The budget used to be a fixed 256 MB, so it was the same number on a stick
 * with 400 MB free as on one with 20 GB — the largest single store in the app
 * that could not answer the device it was running on.
 */
class StreamDiskCacheTest {

    private val mb = 1024L * 1024L

    @Test
    fun `a roomy device is capped at the ceiling`() {
        assertEquals(StreamDiskCache.MAX_BYTES, streamCacheBudgetBytes(20L * 1024 * mb))
        // 2.56 GB of free space is where the share reaches the ceiling.
        assertEquals(StreamDiskCache.MAX_BYTES, streamCacheBudgetBytes(2560L * mb))
    }

    @Test
    fun `the share of free space is what a middling device gets`() {
        assertEquals(50 * mb, streamCacheBudgetBytes(1_000L * mb))
        assertEquals(100 * mb, streamCacheBudgetBytes(2_000L * mb))
    }

    @Test
    fun `a nearly-full device keeps only the read-ahead floor`() {
        // 5% of 400 MB is 20 MB, under the floor; the floor is deliberately the
        // last word, because a device this full still has to PLAY something.
        assertEquals(StreamDiskCache.MIN_BYTES, streamCacheBudgetBytes(400L * mb))
        assertEquals(StreamDiskCache.MIN_BYTES, streamCacheBudgetBytes(0L))
    }

    @Test
    fun `the share takes over exactly where the floor lets go`() {
        // 5% of 640 MB is the 32 MB floor, so 640 MB is the pivot: below it the
        // floor is the answer, above it the share is.
        assertEquals(StreamDiskCache.MIN_BYTES, streamCacheBudgetBytes(640L * mb))
        assertEquals(32 * mb + 1, streamCacheBudgetBytes(640L * mb + 20))
    }

    @Test
    fun `a nonsensical free-space reading cannot produce a nonsensical budget`() {
        // usableSpace is a long the platform can report as 0, and a negative
        // reading would otherwise size the cache below zero.
        assertEquals(StreamDiskCache.MIN_BYTES, streamCacheBudgetBytes(-1L))
    }

    @Test
    fun `the ceiling stays under the old fixed budget`() {
        assertEquals(128L * mb, StreamDiskCache.MAX_BYTES)
        assertEquals(32L * mb, StreamDiskCache.MIN_BYTES)
    }
}
