package com.kennyb1201.kbstream.ui.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV Detail's screensaver pool: the title the viewer is on first, then the same
 * related titles the page's own rail shows - and nothing the page itself would
 * not draw.
 *
 * The rules a device can only show: which slide comes first, and that the pool
 * never widens past the rail (no second filter, no extra titles).
 */
class DetailScreensaverPoolTest {

    private fun related(id: Int, name: String? = "Related $id", series: Boolean = false) =
        ScreensaverRelated(tmdbId = id, name = name, series = series)

    @Test
    fun `the title being viewed is the first slide`() {
        val pool = detailScreensaverCandidates(
            tmdbId = 603,
            title = "The Matrix",
            type = "movie",
            related = listOf(related(604), related(605))
        )

        assertEquals(
            "the viewer idles on THIS title, so the show opens with it",
            listOf("tmdb:603", "tmdb:604", "tmdb:605"),
            pool.map { it.id }
        )
        assertEquals("The Matrix", pool.first().name)
        assertEquals("movie", pool.first().type)
    }

    @Test
    fun `related titles keep the rail's order and are typed by the rail's own field`() {
        val pool = detailScreensaverCandidates(
            tmdbId = 1,
            title = "Seed",
            type = "movie",
            related = listOf(related(11), related(12, series = true), related(13))
        )

        assertEquals(listOf("tmdb:11", "tmdb:12", "tmdb:13"), pool.drop(1).map { it.id })
        assertEquals(
            "a recommendation with no film title but a series name is a show",
            listOf("movie", "series", "movie"),
            pool.drop(1).map { it.type }
        )
    }

    @Test
    fun `the pool is never wider than the rail it mirrors`() {
        val pool = detailScreensaverCandidates(
            tmdbId = 1,
            title = "Seed",
            type = "movie",
            related = (100..200).map { related(it) }
        )
        assertEquals(DETAIL_SCREENSAVER_RELATED_LIMIT + 1, pool.size)
        assertEquals(30, DETAIL_SCREENSAVER_RELATED_LIMIT)
    }

    @Test
    fun `a repeated or self-listing recommendation is not a second slide`() {
        val pool = detailScreensaverCandidates(
            tmdbId = 7,
            title = "Seed",
            type = "movie",
            related = listOf(related(8), related(7), related(8), related(9))
        )
        assertEquals(
            "the title being viewed cannot also be one of its own related slides",
            listOf("tmdb:7", "tmdb:8", "tmdb:9"),
            pool.map { it.id }
        )
    }

    @Test
    fun `the same TMDB number on both mediums is two titles`() {
        val pool = detailScreensaverCandidates(
            tmdbId = 1,
            title = "Seed",
            type = "movie",
            related = listOf(related(42), related(42, series = true))
        )
        assertEquals(
            "TMDB numbers films and shows separately",
            listOf("movie", "series"),
            pool.drop(1).map { it.type }
        )
        assertEquals(3, pool.size)
    }

    @Test
    fun `a title with nothing to resolve is a pool, and a resolve pass decides the show`() {
        val pool = detailScreensaverCandidates(
            tmdbId = null,
            title = null,
            type = "movie",
            related = listOf(related(5, name = " "), related(6))
        )
        assertEquals(
            "no current title is not a fabricated one: only the named related title is here",
            listOf("tmdb:6"),
            pool.map { it.id }
        )
        assertTrue(
            "the candidates carry no art of their own - the resolve pass is the gate",
            pool.all { it.background == null && it.logo == null }
        )
        assertEquals(
            "and an empty page arms nothing",
            emptyList<String>(),
            detailScreensaverCandidates(
                tmdbId = null,
                title = null,
                type = "movie",
                related = emptyList()
            ).map { it.id }
        )
    }
}
