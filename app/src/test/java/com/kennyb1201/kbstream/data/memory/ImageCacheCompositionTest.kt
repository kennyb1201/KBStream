package com.kennyb1201.kbstream.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache-composition read (diagnostics, 2026-11-19).
 *
 * These pin the question `47MB of 48MB` could not answer: whether the cache is
 * full of the rail on screen or of hero-size backdrops the walk already left.
 * The split is by each entry's OWN width and OWN byte size, so a 1280x720 warm
 * and a 420x236 tile land in different buckets however they were decoded.
 */
class ImageCacheCompositionTest {

    private fun sample(widthPx: Int, sizeBytes: Long) =
        ImageCacheComposition.Sample(widthPx = widthPx, sizeBytes = sizeBytes)

    @Test
    fun `the split is the decode line's 960 px rule`() {
        assertEquals(960, ImageCacheComposition.HERO_MIN_WIDTH_PX)

        val reading = ImageCacheComposition.read(
            totalBytes = 0L,
            maxBytes = 0L,
            samples = listOf(
                sample(960, 100L),
                sample(959, 10L),
                sample(1280, 200L),
                sample(420, 20L)
            )
        )

        assertTrue(
            "960 is hero-size and 959 is not: the same boundary the decode " +
                "funnel uses, so the two lines cannot disagree",
            reading.heroEntries == 2 && reading.heroBytes == 300L &&
                reading.otherEntries == 2 && reading.otherBytes == 30L
        )
    }

    @Test
    fun `bytes come from the entry, not from its pixels`() {
        // A hardware or non-ARGB bitmap does not report width * height * 4, and
        // the cap is held to the cache's own accounting - so that is what is
        // summed, not a figure recomputed here.
        val reading = ImageCacheComposition.read(
            totalBytes = 1_000L,
            maxBytes = 1_000L,
            samples = listOf(sample(1280, 7L))
        )

        assertEquals(7L, reading.heroBytes)
    }

    @Test
    fun `a cache full of hero backdrops reads as hero`() {
        // The build-386 shape: twelve 1280x720 ARGB_8888 entries (~3.5MB each)
        // and eight rail tiles. A thirteenth backdrop is all a 48MB cache has
        // room for.
        val hero = 3_686_400L
        val tile = 396_480L
        val reading = ImageCacheComposition.read(
            totalBytes = hero * 12 + tile * 8,
            maxBytes = 48L * 1_048_576,
            samples = List(12) { sample(1280, hero) } + List(8) { sample(420, tile) }
        )

        assertEquals(
            "image cache: 45MB of 48MB hero=42MB/12 other=3MB/8",
            ImageCacheComposition.line(reading)
        )
    }

    @Test
    fun `still-alive evicted entries are named, not folded in`() {
        // Coil's `keys` includes values the GC has not collected since they left
        // the strong cache, and those bytes are NOT in `size`. Printing the live
        // total keeps such a read from simply looking like more than the cap.
        val reading = ImageCacheComposition.read(
            totalBytes = 40L * 1_048_576,
            maxBytes = 48L * 1_048_576,
            samples = listOf(
                sample(1280, 30L * 1_048_576),
                sample(420, 14L * 1_048_576)
            )
        )

        assertEquals(
            "image cache: 40MB of 48MB hero=30MB/1 other=14MB/1 live=44MB",
            ImageCacheComposition.line(reading)
        )
    }

    @Test
    fun `a zero-byte entry is not an entry`() {
        val reading = ImageCacheComposition.read(
            totalBytes = 0L,
            maxBytes = 48L * 1_048_576,
            samples = listOf(sample(1280, 0L), sample(0, -5L))
        )

        assertEquals(0, reading.entries)
        assertEquals(
            "an empty cache keeps the line it always had",
            "image cache: 0MB of 48MB",
            ImageCacheComposition.line(reading)
        )
    }
}
