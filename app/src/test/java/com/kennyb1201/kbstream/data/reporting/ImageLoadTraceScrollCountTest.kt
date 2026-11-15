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
            ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 1280, 720)
        }

        assertEquals(
            "memory hits are not decodes; the four disk decodes are the walk's cost",
            "scroll: heroDecodes=4 cardDiskDecodes=0",
            scrollLine()
        )
    }

    @Test
    fun `a rail tile decoded from the disk is counted separately`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 342, 192)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.NETWORK, 342, 192)

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
