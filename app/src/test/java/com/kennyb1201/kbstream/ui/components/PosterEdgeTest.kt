package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poster-corner setting ("Poster Edges") is stored as a plain Int in the
 * data layer, so the ordinal -> enum mapping and the default both have to be
 * pinned. A reordered enum or a changed default must fail here rather than
 * silently reshape every existing install's posters.
 */
class PosterEdgeTest {

    @Test
    fun `stored ordinals map to their edges`() {
        assertEquals(PosterEdge.STRAIGHT, PosterEdge.fromStored(0))
        assertEquals(PosterEdge.ROUNDED, PosterEdge.fromStored(1))
        assertEquals(PosterEdge.PILL, PosterEdge.fromStored(2))
    }

    @Test
    fun `the default is the rounded card shape`() {
        assertEquals(PosterEdge.ROUNDED, PosterEdge.DEFAULT)
    }

    @Test
    fun `an out-of-range value falls back instead of throwing`() {
        assertEquals(PosterEdge.DEFAULT, PosterEdge.fromStored(-1))
        assertEquals(PosterEdge.DEFAULT, PosterEdge.fromStored(99))
    }

    @Test
    fun `every edge draws a shape and carries a distinct label`() {
        val labels = PosterEdge.entries.map { it.label }
        assertTrue("labels must be non-blank", labels.all { it.isNotBlank() })
        assertEquals("labels must be distinct", labels.size, labels.distinct().size)
        // The three shapes must actually differ, or the setting would have no
        // visible effect.
        assertNotEquals(PosterEdge.STRAIGHT.shape(), PosterEdge.ROUNDED.shape())
        assertNotEquals(PosterEdge.ROUNDED.shape(), PosterEdge.PILL.shape())
    }
}
