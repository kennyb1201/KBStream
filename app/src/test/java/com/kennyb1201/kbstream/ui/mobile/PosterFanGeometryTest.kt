package com.kennyb1201.kbstream.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone header fan's drawn geometry, without a device.
 *
 * `Modifier.offset` moves where a poster is DRAWN while the parent still
 * measures its full width, so the fan's drawn width has to be computed rather
 * than summed: it is what the header row gives the title beside it.
 *
 * The widths below are the header's own list (44/50/58/50/44 dp, 10 dp of
 * overlap), so a change to the fan's sizes shows up here as a failed
 * expectation rather than a silently narrower title.
 */
class PosterFanGeometryTest {

    private val headerWidths = listOf(44f, 50f, 58f, 50f, 44f)

    @Test
    fun `five posters fan into 206dp`() {
        val fan = posterFanGeometry(headerWidths)

        assertEquals(listOf(0f, 34f, 74f, 122f, 162f), fan.offsets)
        assertEquals(206f, fan.width, 0.01f)
    }

    @Test
    fun `three posters fan into 132dp`() {
        val fan = posterFanGeometry(headerWidths.take(3))

        assertEquals(listOf(0f, 34f, 74f), fan.offsets)
        assertEquals(132f, fan.width, 0.01f)
    }

    @Test
    fun `one poster is drawn at zero and measures its own width`() {
        val fan = posterFanGeometry(headerWidths.take(1))

        assertEquals(listOf(0f), fan.offsets)
        assertEquals(44f, fan.width, 0.01f)
    }

    @Test
    fun `no posters draw no fan`() {
        val fan = posterFanGeometry(emptyList())

        assertTrue("nothing to draw", fan.offsets.isEmpty())
        assertEquals(0f, fan.width, 0.01f)
        assertEquals(0f, posterFanGeometry(emptyList(), overlap = 4f).width, 0.01f)
    }

    @Test
    fun `the overlap step is an input, not a constant buried in the layout`() {
        val fan = posterFanGeometry(headerWidths.take(2), overlap = 6f)

        assertEquals(listOf(0f, 38f), fan.offsets)
        assertEquals(88f, fan.width, 0.01f)
    }

    @Test
    fun `the width follows the posters it is given`() {
        val fan = posterFanGeometry(listOf(60f, 60f))

        assertEquals(listOf(0f, 50f), fan.offsets)
        assertEquals(110f, fan.width, 0.01f)
    }
}
