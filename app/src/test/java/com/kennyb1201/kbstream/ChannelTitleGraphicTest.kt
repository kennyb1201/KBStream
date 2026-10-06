package com.kennyb1201.kbstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a guide channel launch hands its load splashes as the title graphic.
 *
 * A channel has no backdrop and no TMDB clearlogo, so its icon is the only art
 * the splash can pulse - and the one the guide row already shows. Picking it is
 * a pure choice, so it is tested as one instead of through the source.
 */
class ChannelTitleGraphicTest {

    @Test
    fun `the channel's own logo wins`() {
        assertEquals(
            "https://cdn.example/bbc.png",
            channelTitleGraphic("https://cdn.example/bbc.png", "https://cdn.example/epg.png")
        )
    }

    @Test
    fun `a channel with no logo falls back to its guide icon`() {
        assertEquals(
            "https://cdn.example/epg.png",
            channelTitleGraphic(null, "https://cdn.example/epg.png")
        )
    }

    @Test
    fun `a blank logo is no logo, not an empty image request`() {
        assertEquals(
            "https://cdn.example/epg.png",
            channelTitleGraphic("   ", "https://cdn.example/epg.png")
        )
    }

    @Test
    fun `a blank guide icon is no icon either`() {
        assertNull(channelTitleGraphic("", "  "))
    }

    @Test
    fun `a channel with no art at all keeps the name fallback`() {
        assertNull(channelTitleGraphic(null, null))
    }
}
