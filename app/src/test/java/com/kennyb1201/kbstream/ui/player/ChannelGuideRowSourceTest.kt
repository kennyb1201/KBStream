package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player guide's row list builds a row only when it is asked for.
 *
 * The overlay used to materialise one row per channel before it could paint,
 * which is the shape of the ANR Sentry caught (ANDROID-Z): opening the guide on
 * a long IPTV group spent the main thread's whole input window building rows
 * nobody had scrolled to yet. RecyclerView binds only the rows on screen, so
 * these pin that a long lineup costs the list construction nothing and that a
 * bind is the only thing that builds a row.
 */
class ChannelGuideRowSourceTest {

    private fun row(index: Int) = ChannelGuideRow(
        channelNumber = null,
        name = "channel $index",
        nowTitle = null,
        nowTime = null,
        nowProgressPermille = null,
        nextTitle = null,
        isCurrent = false,
        onClick = {}
    )

    @Test
    fun `a long lineup is sized without building a single row`() {
        val built = mutableListOf<Int>()
        val rows: List<ChannelGuideRow> = ChannelGuideRowSource(200_000) { index ->
            built += index
            row(index)
        }

        assertEquals(200_000, rows.size)
        assertTrue("constructing the source must not build rows", built.isEmpty())
    }

    @Test
    fun `only the rows that are read are built`() {
        val built = mutableListOf<Int>()
        val rows: List<ChannelGuideRow> = ChannelGuideRowSource(200_000) { index ->
            built += index
            row(index)
        }

        assertEquals("channel 0", rows[0].name)
        assertEquals("channel 199999", rows[199_999].name)
        assertEquals("a bind builds exactly one row", listOf(0, 199_999), built)
    }

    @Test
    fun `a position outside the lineup is refused`() {
        val rows: List<ChannelGuideRow> = ChannelGuideRowSource(3) { row(it) }

        assertThrows(IndexOutOfBoundsException::class.java) { rows[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { rows[3] }
    }
}
