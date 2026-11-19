package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The image-load half of the 2026-11-14 scrolling spec's evidence (F1).
 *
 * The spec's first job is to prove which cause dominates before changing code,
 * and the report had no way to say it: "the cache was wiped mid-scroll and every
 * card re-decoded" and "the cache was fine and something else is slow" produced
 * the same paste. These tests pin the counting that makes the difference
 * visible, and the shape of the lines so a reader of the paste does not have to
 * guess what a number means.
 */
class ImageLoadTraceTest {

    @Before
    fun clean() {
        ImageLoadTrace.reset()
    }

    @Test
    fun `a session that has loaded nothing prints nothing`() {
        assertEquals(
            "an untouched ring must not add lines to a report from a session that " +
                "never scrolled",
            emptyList<String>(),
            ImageLoadTrace.lines()
        )
    }

    @Test
    fun `every completed request is counted by where its pixels came from`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 342, 192)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 342, 192)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 342, 192)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.NETWORK, 1280, 720)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.OTHER, 300, 450)

        val loads = ImageLoadTrace.lines().first { it.startsWith("loads:") }
        assertEquals(
            "hits and decodes are the whole argument: a warm scroll is mostly " +
                "memory=, a wiped cache is disk= growing",
            "loads: memory=2 disk=1 network=1 other=1",
            loads
        )
    }

    @Test
    fun `only real decodes name a size`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 999, 999)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 342, 192)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 342, 192)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.NETWORK, 1280, 720)

        val sizes = ImageLoadTrace.lines().first { it.startsWith("decoded at:") }
        assertEquals(
            "a cache hit is not a decode, so its dimensions must not take a slot - " +
                "the point of the line is what the surfaces really decoded, and a " +
                "4K hero showing up here is the bug F2 was written for",
            "decoded at: 342x192 x2, 1280x720 x1",
            sizes
        )
    }

    @Test
    fun `a size the platform could not resolve is skipped, not counted as zero`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 0, 0)
        assertEquals(
            "one load, no sizes - never a 0x0 entry",
            listOf("loads: memory=0 disk=1 network=0 other=0"),
            ImageLoadTrace.lines()
        )
    }

    @Test
    fun `the size list is capped so a long session cannot bury the surfaces`() {
        (1..10).forEach { n ->
            ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, n * 10, n * 10)
        }
        val sizes = ImageLoadTrace.lines().first { it.startsWith("decoded at:") }
        assertEquals(
            "six sizes, and the newest are the ones kept",
            6,
            sizes.removePrefix("decoded at: ").split(", ").size
        )
        assertTrue(
            "the first four were evicted",
            !sizes.contains("10x10") && sizes.contains("100x100")
        )
    }

    @Test
    fun `trim levels are recorded by name, with their own counts`() {
        ImageLoadTrace.noteTrim("RUNNING_LOW")
        ImageLoadTrace.noteTrim("RUNNING_LOW")
        ImageLoadTrace.noteTrim("RUNNING_CRITICAL")

        val trim = ImageLoadTrace.lines().single()
        assertEquals(
            "F1 needs the LEVELS: a RUNNING_LOW that no longer clears the cache " +
                "and a RUNNING_CRITICAL that does have to be tellable apart in " +
                "the paste",
            "trim: RUNNING_LOW x2, RUNNING_CRITICAL x1",
            trim
        )
    }

    @Test
    fun `a trim with nothing loaded still reports - it is pressure evidence`() {
        ImageLoadTrace.noteTrim("RUNNING_LOW")
        assertEquals(
            "the trim happened before any image was needed, which is exactly the " +
                "state a fix has to survive",
            listOf("trim: RUNNING_LOW x1"),
            ImageLoadTrace.lines()
        )
    }

    // ── F4 (2026-11-19, churn): when a decode landed ─────────────────

    @Test
    fun `a decode stamps the frame meter's clock, a memory hit does not`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 342, 192)
        assertEquals(
            "a hit decodes nothing, so it must not mark a frame as decoded",
            0L,
            ImageLoadTrace.lastDecodeEndNanos()
        )
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 342, 192)
        assertTrue(
            "the disk decode is what the frame meter has to attribute",
            ImageLoadTrace.lastDecodeEndNanos() > 0L
        )
    }

    @Test
    fun `reset clears both halves`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 1, 1)
        ImageLoadTrace.noteTrim("RUNNING_LOW")
        ImageLoadTrace.reset()
        assertEquals(emptyList<String>(), ImageLoadTrace.lines())
    }
}
