package com.kennyb1201.kbstream.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graded memory trim (spec 2026-11-15, scrolling round two F4).
 *
 * The old response to RUNNING_CRITICAL was `clear()`: every decoded bitmap gone,
 * including the rail on screen, which then re-decoded as the viewer kept
 * scrolling. These cases pin what replaces it - half the cache, biggest first,
 * hero-size bitmaps before tiles - because the ordering IS the behavior: evict
 * the wrong half and the fix is a plain clear with extra steps.
 */
class ImageCacheTrimTest {

    private fun entry(width: Int, height: Int, sizeBytes: Long) =
        ImageCacheTrim.Entry(width = width, height = height, sizeBytes = sizeBytes)

    @Test
    fun `hero-size bitmaps go before rail tiles`() {
        val entries = listOf(
            entry(342, 192, 200_000),       // 0 rail tile
            entry(1280, 720, 3_700_000),    // 1 hero backdrop
            entry(342, 192, 210_000),       // 2 rail tile
            entry(1280, 720, 3_600_000)     // 3 hero backdrop
        )

        val evicted = ImageCacheTrim.evictIndices(entries, residentBytes = 7_710_000)

        assertEquals(
            "the two hero-size entries are the first to go, not the oldest tiles",
            listOf(1, 3),
            evicted.take(2)
        )
    }

    @Test
    fun `the biggest bitmap goes first inside a group`() {
        val entries = listOf(
            entry(342, 192, 100_000),
            entry(342, 192, 900_000),
            entry(342, 192, 500_000)
        )

        // 1MB of a 2MB cache: the 900k tile first, then the 500k one, and the
        // 100k tile is still cheaper to keep than to decode again.
        val evicted = ImageCacheTrim.evictIndices(entries, residentBytes = 2_000_000)

        assertEquals(
            "biggest first: the most reclaim per eviction",
            listOf(1, 2),
            evicted
        )
    }

    @Test
    fun `a trim frees half, not everything`() {
        val entries = (1..10).map { entry(342, 192, 1_000_000) }

        val evicted = ImageCacheTrim.evictIndices(entries, residentBytes = 10_000_000)

        assertEquals(
            "half the resident bytes is the target",
            5,
            evicted.size
        )
        assertTrue(
            "and it must never be all ten - that would be the clear this replaces",
            evicted.size < entries.size
        )
    }

    @Test
    fun `an empty or unknown cache evicts nothing`() {
        assertEquals(
            emptyList<Int>(),
            ImageCacheTrim.evictIndices(emptyList(), residentBytes = 10)
        )
        assertEquals(
            "a cache that reports no size has nothing to grade",
            emptyList<Int>(),
            ImageCacheTrim.evictIndices(listOf(entry(1280, 720, 0)), residentBytes = 0)
        )
        assertEquals(
            "a zero target is a no-op, not a clear",
            emptyList<Int>(),
            ImageCacheTrim.evictIndices(
                listOf(entry(1280, 720, 5)),
                residentBytes = 5,
                targetFraction = 0.0
            )
        )
    }

    @Test
    fun `a bitmap with no reported size still counts as a candidate`() {
        val entries = listOf(
            entry(1280, 720, 0),
            entry(342, 192, 1_000)
        )

        val evicted = ImageCacheTrim.evictIndices(entries, residentBytes = 1_000)

        assertTrue(
            "the hero-size entry with an unknown size is still evicted first",
            evicted.first() == 0
        )
    }

    @Test
    fun `the target is a fraction of what is resident, not of the entries listed`() {
        val entries = listOf(entry(342, 192, 100), entry(342, 192, 100))

        val evicted = ImageCacheTrim.evictIndices(entries, residentBytes = 1_000)

        assertEquals(
            "500 bytes are targeted, and 200 available, so both go - the rule " +
                "stops when it runs out of candidates, it does not fail",
            2,
            evicted.size
        )
    }
}
