package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Chapter markers are paint only, so the one thing worth pinning is where a
 * mark lands: a mark at 50% of the duration must draw at 50% of the track, a
 * mark past the end must be dropped rather than drawn at the edge, and an
 * unknown duration must draw nothing at all. [ChapterTicks.tickX] is pure for
 * exactly this - a SeekBar subclass cannot be instantiated in a JVM test.
 */
class ChapterTicksTest {

    @Test
    fun `a mark at half the duration draws at half the track width`() {
        val x = ChapterTicks.tickX(5_000L, 10_000L, trackLeft = 0f, trackRight = 100f)
        assertEquals(50f, x!!, 0.001f)
    }

    @Test
    fun `track padding is honoured`() {
        // The bar's own left/right padding is the track's extent, so a mark at
        // the start and one at the end land on those edges.
        assertEquals(
            10f,
            ChapterTicks.tickX(0L, 10_000L, trackLeft = 10f, trackRight = 210f)!!,
            0.001f
        )
        assertEquals(
            210f,
            ChapterTicks.tickX(10_000L, 10_000L, trackLeft = 10f, trackRight = 210f)!!,
            0.001f
        )
    }

    @Test
    fun `a mark past the duration is dropped`() {
        assertNull(ChapterTicks.tickX(10_001L, 10_000L, 0f, 100f))
    }

    @Test
    fun `a mark before the start is clamped to the track`() {
        assertEquals(0f, ChapterTicks.tickX(-500L, 10_000L, 0f, 100f)!!, 0.001f)
    }

    @Test
    fun `an unknown duration draws nothing`() {
        assertNull(ChapterTicks.tickX(1_000L, 0L, 0f, 100f))
        assertNull(ChapterTicks.tickX(1_000L, -1L, 0f, 100f))
    }
}
