package com.kennyb1201.kbstream.data.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind a viewer's image choice: the key it is filed under, the id it
 * is filed by, and what a surface draws once it is there.
 *
 * The store's SQL is pinned against a real database in [ImageOverrideDaoTest];
 * what is pinned here is the part with no database in it - the composed key, the
 * id spellings a card can arrive with, and the fallback that makes "Reset to
 * default" mean TMDB's art rather than a blank. Pure, so a mistake in any of
 * them fails here rather than as an image that quietly never changes.
 */
class ImageOverridesRulesTest {

    @Test
    fun `a choice is keyed by the title, the kind of art and the surface`() {
        assertEquals(
            "12345:backdrop:detail",
            ImageOverrides.key(12345, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)
        )
        assertEquals(
            "12345:poster:poster",
            ImageOverrides.key(12345, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER)
        )
    }

    @Test
    fun `every backdrop surface is one list, so a reset cannot miss one`() {
        assertEquals(
            listOf("detail", "streams", "player_splash", "home_hero", "landscape_card"),
            ImageOverrides.screensFor(ImageOverrides.TYPE_BACKDROP)
        )
        assertEquals(
            "a poster is one choice for every surface it is drawn on",
            listOf("poster"),
            ImageOverrides.screensFor(ImageOverrides.TYPE_POSTER)
        )
        assertTrue(
            "an unknown kind has no surfaces to write, so nothing can be " +
                "cleared by a rule that does not name them",
            ImageOverrides.screensFor("logo").isEmpty()
        )
    }

    @Test
    fun `a surface's id spellings all reach the same number`() {
        assertEquals(603, ImageOverrides.tmdbIdOf("tmdb:603"))
        assertEquals(603, ImageOverrides.tmdbIdOf("tmdb_603"))
        assertEquals(603, ImageOverrides.tmdbIdOf("603"))
        assertEquals(603, ImageOverrides.tmdbIdOf(" 603 "))
        assertNull(
            "an add-on id is a different namespace: bridging it is a network " +
                "lookup, and a card is not the place to make one",
            ImageOverrides.tmdbIdOf("tt0133093")
        )
        assertNull(ImageOverrides.tmdbIdOf(null))
        assertNull(ImageOverrides.tmdbIdOf(""))
        assertNull(ImageOverrides.tmdbIdOf("0"))
    }

    @Test
    fun `a path is found by number or by the id spelling a card holds`() {
        val paths = mapOf("603:backdrop:detail" to "/chosen.jpg")

        assertEquals(
            "/chosen.jpg",
            ImageOverrides.pathFor(paths, 603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)
        )
        assertEquals(
            "the same row, reached from a rail that only has a \"tmdb:603\"",
            "/chosen.jpg",
            ImageOverrides.pathFor(paths, "tmdb:603", ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)
        )
        assertNull(
            "a surface with no choice of its own is not covered by another " +
                "surface's",
            ImageOverrides.pathFor(paths, "tmdb:603", ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_STREAMS)
        )
        assertNull(
            "a card with no id to look up by keeps TMDB's art",
            ImageOverrides.pathFor(paths, "tt0133093", ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)
        )
    }

    @Test
    fun `the chosen path wins over TMDB's and a missing one falls back to it`() {
        val paths = mapOf("603:backdrop:streams" to "/chosen.jpg")

        assertEquals(
            "/chosen.jpg",
            ImageOverrides.applied(
                paths, 603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_STREAMS, "/tmdb.jpg"
            )
        )
        assertEquals(
            "a cleared choice is not a blank image, it is TMDB's art again",
            "/tmdb.jpg",
            ImageOverrides.applied(
                paths, 603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL, "/tmdb.jpg"
            )
        )
        assertNull(
            ImageOverrides.applied(
                paths, null, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_STREAMS, null
            )
        )
    }

    @Test
    fun `a reset row is offered when any surface of the kind holds a choice`() {
        val none = emptyMap<String, String>()
        assertFalse(
            "nothing to reset: the row would be a press that does nothing",
            ImageOverrides.hasOverride(none, 603, ImageOverrides.TYPE_BACKDROP)
        )

        val oneSurface = mapOf("603:backdrop:detail" to "/chosen.jpg")
        assertTrue(
            "the row is offered for the title, not for the surface the viewer " +
                "happens to be looking at - a reset is about the title",
            ImageOverrides.hasOverride(oneSurface, 603, ImageOverrides.TYPE_BACKDROP)
        )
        assertFalse(
            "a backdrop choice is not a poster choice",
            ImageOverrides.hasOverride(oneSurface, 603, ImageOverrides.TYPE_POSTER)
        )
        assertFalse(
            "and another title's choice is not this one's",
            ImageOverrides.hasOverride(oneSurface, 604, ImageOverrides.TYPE_BACKDROP)
        )

        val posterChoice = mapOf("603:poster:poster" to "/poster.jpg")
        assertTrue(
            ImageOverrides.hasOverride(posterChoice, 603, ImageOverrides.TYPE_POSTER)
        )
    }
}
