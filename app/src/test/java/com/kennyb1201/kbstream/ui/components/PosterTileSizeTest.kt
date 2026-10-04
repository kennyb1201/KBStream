package com.kennyb1201.kbstream.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global "Landscape Posters" tile is PROPORTIONAL to the poster it replaces,
 * not a fixed size and not a 16:9 crop of the poster's own width.
 *
 * Reported from the field: the global landscape tiles read as far too small,
 * because they were sized as the caller's poster width at 16:9 (a 124dp poster
 * became a 124x70 thumbnail) while the Home rail the viewer compares them to
 * draws a 210x118 card. These pin the two properties that fix it: the Medium
 * poster lands exactly on Home's numbers, and every size keeps the same ratio -
 * so the setting scales with the viewer's Poster Size instead of overflowing a
 * screen at Large or vanishing at Small.
 */
class PosterTileSizeTest {

    private fun landscapeWidth(posterWidth: Float) = landscapeTileWidth(posterWidth.dp).value

    private fun landscapeHeight(posterWidth: Float) = landscapeTileHeight(posterWidth.dp).value

    @Test
    fun `a medium poster draws the home rail's landscape card`() {
        assertEquals(210f, landscapeWidth(124f), 0.01f)
        assertEquals(118f, landscapeHeight(124f), 0.25f)
    }

    @Test
    fun `the tile keeps the home rail's ratio at every poster size`() {
        // The three PosterSize widths.
        val widths = listOf(110f, 124f, 140f)
        val ratios = widths.map { landscapeWidth(it) / it }
        ratios.forEach { ratio ->
            assertEquals(ratios.first(), ratio, 1e-4f)
        }
    }

    @Test
    fun `the tile is 16 by 9 and wider than the poster it replaces`() {
        val widths = listOf(110f, 124f, 140f)
        widths.forEach { posterWidth ->
            val width = landscapeWidth(posterWidth)
            val height = landscapeHeight(posterWidth)
            assertEquals(width * 9f / 16f, height, 0.01f)
            // Wider, which is why a caller pinned to the poster width would clip
            // it - see rememberPosterTileWidth.
            assertTrue("landscape must be wider than the poster", width > posterWidth)
        }
    }
}
