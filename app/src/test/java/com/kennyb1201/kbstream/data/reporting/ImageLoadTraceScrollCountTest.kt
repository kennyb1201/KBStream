package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two counts the dig could not get (spec 2026-11-15, scrolling round two F1).
 *
 * `decoded at:` keeps only six sizes, so a paste could say the hero's size
 * appeared and never say how often - which is exactly the question F2 and F3
 * have to answer. These cases pin the new line: hero-size decodes on one side,
 * rail tiles that had to come off the disk on the other.
 */
class ImageLoadTraceScrollCountTest {

    @Before
    fun clean() {
        ImageLoadTrace.reset()
    }

    private fun scrollLine(): String? =
        ImageLoadTrace.lines().firstOrNull { it.startsWith("scroll:") }

    @Test
    fun `hero-size decodes are counted across a walk`() {
        repeat(4) {
            ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 1280, 720)
        }
        repeat(4) {
            ImageLoadTrace.noteImage(
                ImageLoadTrace.Source.DISK,
                1280,
                720,
                walkActive = true
            )
        }

        assertEquals(
            "memory hits are not decodes; the four disk decodes are the walk's cost",
            "scroll: heroDecodes=4 cardDiskDecodes=0",
            scrollLine()
        )
    }

    @Test
    fun `a rail tile decoded from the disk is counted separately`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 342, 192, walkActive = true)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236, walkActive = true)
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.NETWORK,
            342,
            192,
            walkActive = true
        )

        assertEquals(
            "disk tiles are the wipe's cost; a network tile is a cold card, not a re-decode",
            "scroll: heroDecodes=0 cardDiskDecodes=2",
            scrollLine()
        )
    }

    @Test
    fun `a warm session prints no scroll line at all`() {
        repeat(10) {
            ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 342, 192)
        }

        assertEquals(
            "nothing decoded, nothing to report - the paste stays short",
            null,
            scrollLine()
        )
    }

    @Test
    fun `a decode outside a walk window is not the walk's cost`() {
        // The build-387 paste printed a 30-minute session's decodes under
        // "scroll:" - Detail pages, the credits panel, the settings trip - and
        // 306 could not be read as the walk at all. Only a decode that lands in
        // a gesture or its tail is counted now; the size audit stays session-wide,
        // because it answers a different question (which size did we warm).
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 1280, 720)

        assertEquals(
            "no gesture, no scroll line",
            null,
            scrollLine()
        )
        assertTrue(
            "but the sizes are still recorded for the warm's audit",
            ImageLoadTrace.lines().any { it.startsWith("decoded at:") }
        )
    }

    @Test
    fun `a session with no images prints nothing`() {
        assertEquals(emptyList<String>(), ImageLoadTrace.lines())
    }

    @Test
    fun `reset clears the new counts too`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 1280, 720)
        ImageLoadTrace.reset()
        assertTrue(
            "a reset that left the counts behind would report the previous " +
                "session's scroll",
            ImageLoadTrace.lines().isEmpty()
        )
    }
}
