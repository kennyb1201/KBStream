package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.fanart.FanartArt
import com.kennyb1201.kbstream.data.fanart.FanartImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fanart's art merging into the Detail Media row (spec 2026-11-15).
 *
 * The rail's two artwork pages were TMDB-only. These cases pin the merge that
 * changes that: TMDB first and in its own order, fanart appended, an exact-dupe
 * URL once, and - the case the field will actually show - a page that exists
 * BECAUSE fanart has art where TMDB has none.
 */
class DetailMediaArtTest {

    private fun asset(path: String?) = com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset(
        filePath = path
    )

    private fun fanart(
        backgrounds: List<String> = emptyList(),
        posters: List<String> = emptyList()
    ) = FanartArt(
        backgrounds = backgrounds.map { FanartImage(url = it) },
        posters = posters.map { FanartImage(url = it) }
    )

    @Test
    fun `TMDB's backdrops stay first and in order, fanart appends`() {
        val cards = DetailMediaArt.backdrops(
            tmdb = listOf(asset("/tmdb-1.jpg"), asset("/tmdb-2.jpg")),
            fanart = fanart(
                backgrounds = listOf(
                    "https://assets.fanart.tv/fanart/movies/1/backdrop/a.jpg",
                    "https://assets.fanart.tv/fanart/movies/1/backdrop/b.jpg"
                )
            )
        )

        assertEquals(4, cards.size)
        assertEquals(
            "TMDB first, always: the fanart entries must never displace one",
            listOf(
                "backdrop:/tmdb-1.jpg",
                "backdrop:/tmdb-2.jpg",
                "backdrop:https://assets.fanart.tv/fanart/movies/1/backdrop/a.jpg",
                "backdrop:https://assets.fanart.tv/fanart/movies/1/backdrop/b.jpg"
            ),
            cards.map { it.id }
        )
    }

    @Test
    fun `a fanart card draws the preview at card size and the original fullscreen`() {
        val url = "https://assets.fanart.tv/fanart/movies/1/backdrop/a.jpg"
        val card = DetailMediaArt.backdrops(tmdb = emptyList(), fanart = fanart(listOf(url)))
            .single()

        assertEquals(
            "a 1920-wide image in a few-hundred-wide tile is a decode the card never uses",
            "https://assets.fanart.tv/bigpreview/movies/1/backdrop/a.jpg",
            card.imageUrl
        )
        assertEquals("the fullscreen view draws the real thing", url, card.fullImageUrl)
    }

    @Test
    fun `a fanart card offers no override path, because it has none`() {
        val card = DetailMediaArt.backdrops(
            tmdb = emptyList(),
            fanart = fanart(listOf("https://assets.fanart.tv/fanart/movies/1/backdrop/a.jpg"))
        ).single()

        assertNull(
            "imagePath is TMDB's file_path; a URL must never be stored as one",
            card.imagePath
        )
        assertEquals("and no source badge: the rail has never labelled art", null, card.badge)
    }

    @Test
    fun `a fanart-only title gains the page it did not have`() {
        val cards = DetailMediaArt.backdrops(
            tmdb = emptyList(),
            fanart = fanart(listOf("https://assets.fanart.tv/fanart/tv/1/showbackground/a.jpg"))
        )

        assertEquals(
            "S3: a page with nothing on it is not offered, and this one now has something",
            1,
            cards.size
        )
        assertTrue(
            "so the tab strip counts it (the count is the merged list's size)",
            DetailMediaRow.tabs(
                videoCount = 0,
                backdropCount = cards.size,
                posterCount = 0
            ).contains(MediaTab.BACKDROPS)
        )
    }

    @Test
    fun `an exact-duplicate URL appears once`() {
        val same = "https://assets.fanart.tv/fanart/movies/1/backdrop/a.jpg"
        val cards = DetailMediaArt.backdrops(
            tmdb = emptyList(),
            fanart = fanart(listOf(same, same))
        )

        assertEquals("S5: the same URL is one card", 1, cards.size)
    }

    @Test
    fun `a fanart URL that is already a TMDB card URL appears once`() {
        // The one case the spec's "same URL" rule can actually catch: fanart
        // answering with an image.tmdb.org URL.
        val path = "/abc123.jpg"
        val tmdbFull = "https://image.tmdb.org/t/p/w1280$path"
        val cards = DetailMediaArt.backdrops(
            tmdb = listOf(asset(path)),
            fanart = fanart(listOf(tmdbFull))
        )

        assertEquals(
            "a URL TMDB already draws must not be drawn twice",
            1,
            cards.size
        )
    }

    @Test
    fun `no fanart means exactly the TMDB-only page`() {
        val tmdbOnly = listOf(asset("/a.jpg"), asset("/b.jpg"))
        val cards = DetailMediaArt.backdrops(tmdb = tmdbOnly, fanart = null)

        assertEquals(
            "S4: the fail-soft answer is today's rail, card for card",
            DetailMediaRow.backdrops(tmdbOnly),
            cards
        )
        assertEquals(0, DetailMediaArt.backdrops(emptyList(), null).size)
    }

    @Test
    fun `a blank TMDB path is skipped rather than drawn as a broken tile`() {
        val cards = DetailMediaArt.backdrops(
            tmdb = listOf(asset(null), asset(""), asset("/ok.jpg")),
            fanart = null
        )

        assertEquals(listOf("backdrop:/ok.jpg"), cards.map { it.id })
    }

    @Test
    fun `the poster page is portrait and merges the same way`() {
        val cards = DetailMediaArt.posters(
            tmdb = listOf(asset("/tmdb-poster.jpg")),
            fanart = fanart(posters = listOf("https://assets.fanart.tv/fanart/movies/1/poster/a.jpg"))
        )

        assertEquals(2, cards.size)
        assertTrue("the Posters page is the row's one portrait page", cards.all { it.portrait })
        assertEquals(
            "fanart's poster card is its preview too",
            "https://assets.fanart.tv/bigpreview/movies/1/poster/a.jpg",
            cards.last().imageUrl
        )
        assertEquals(
            "and the fullscreen view gets the original",
            "https://assets.fanart.tv/fanart/movies/1/poster/a.jpg",
            cards.last().fullImageUrl
        )
    }

    @Test
    fun `a poster page with no fanart posters does not gain a page`() {
        assertEquals(0, DetailMediaArt.posters(emptyList(), FanartArt()).size)
    }
}
