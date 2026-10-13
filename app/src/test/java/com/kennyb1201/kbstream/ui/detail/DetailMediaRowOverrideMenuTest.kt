package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.images.ImageOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows a long press on a Media card offers.
 *
 * The card itself is a Compose tile and the menu is an overlay, neither of
 * which a unit test can press, so the part that decides what the menu SAYS and
 * which surface each row writes is pure and pinned here: a still on the
 * Backdrops page is a candidate for each of the surfaces that draw a
 * backdrop, a poster for the one that draws a poster everywhere, and the reset
 * row appears only once there is something to reset.
 *
 * Each row's `screen` is the value the menu hands to [ImageOverrides.choose], so a
 * row pointing at a surface the app never reads is a row that would silently do
 * nothing - which is what the surface list here catches.
 */
class DetailMediaRowOverrideMenuTest {

    @Test
    fun `a backdrop offers every surface that draws one`() {
        val rows = DetailMediaRow.overrideRows(MediaTab.BACKDROPS, hasOverride = false)

        assertEquals(
            listOf(
                "Set as Detail backdrop",
                "Set as Streams backdrop",
                "Set as Player splash",
                "Set as Home hero backdrop",
                "Set as Landscape card"
            ),
            rows.map { it.label }
        )
        assertEquals(
            listOf(
                ImageOverrides.SCREEN_DETAIL,
                ImageOverrides.SCREEN_STREAMS,
                ImageOverrides.SCREEN_PLAYER_SPLASH,
                ImageOverrides.SCREEN_HOME_HERO,
                ImageOverrides.SCREEN_LANDSCAPE_CARD
            ),
            rows.map { it.screen }
        )
        assertTrue(
            "every surface the store knows for a backdrop is offered, or a " +
                "viewer could never choose one",
            rows.mapNotNull { it.screen }.containsAll(ImageOverrides.screensFor(ImageOverrides.TYPE_BACKDROP))
        )
        assertTrue(
            "nothing here is destructive while it still writes a choice",
            rows.none { it.isDestructive }
        )
    }

    @Test
    fun `a poster offers one row, because one choice covers every surface`() {
        val rows = DetailMediaRow.overrideRows(MediaTab.POSTERS, hasOverride = false)

        assertEquals(listOf("Set as Poster"), rows.map { it.label })
        assertEquals(listOf(ImageOverrides.SCREEN_POSTER), rows.map { it.screen })
    }

    @Test
    fun `the reset row appears last, and only once there is a choice to reset`() {
        val without = DetailMediaRow.overrideRows(MediaTab.BACKDROPS, hasOverride = false)
        assertTrue(without.none { it.label == "Reset to default" })

        val with = DetailMediaRow.overrideRows(MediaTab.BACKDROPS, hasOverride = true)
        assertEquals("Reset to default", with.last().label)
        assertNull(
            "a reset is about the whole title, not one surface: the menu " +
                "answers null and clears every surface of the kind",
            with.last().screen
        )
        assertTrue(
            "and it is destructive, so it is drawn in the menu's last section",
            with.last().isDestructive
        )

        val posters = DetailMediaRow.overrideRows(MediaTab.POSTERS, hasOverride = true)
        assertEquals(listOf("Set as Poster", "Reset to default"), posters.map { it.label })
    }

    @Test
    fun `the trailers page offers no rows at all`() {
        assertTrue(
            "a video is not artwork, and there is no surface whose image a " +
                "trailer could stand in for",
            DetailMediaRow.overrideRows(MediaTab.TRAILERS, hasOverride = true).isEmpty()
        )
        assertTrue(
            "a card that belongs to no page (no art at all) offers nothing " +
                "either, so a long press there cannot open an empty menu",
            DetailMediaRow.overrideRows(null, hasOverride = true).isEmpty()
        )
    }

    @Test
    fun `every row says what it does`() {
        val rows = listOf(MediaTab.BACKDROPS, MediaTab.POSTERS).flatMap { tab ->
            DetailMediaRow.overrideRows(tab, hasOverride = true)
        }
        assertTrue(
            "a menu row with no description tells the viewer nothing the " +
                "label did not already say",
            rows.all { it.description.isNotBlank() }
        )
    }
}
