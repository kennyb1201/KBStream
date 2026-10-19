package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Media row's rules: which pages it offers, which page a LEFT/RIGHT reaches,
 * and what a card of any of them is.
 *
 * The row is one entry of the vertical focus chain that holds several rails, so
 * the things that decide what a viewer sees when they press a key in it - the
 * set of pages and the page a step lands on - are pinned here rather than in the
 * screen, whose key handler no unit test can press.
 */
class DetailMediaRowTest {

    private fun video(
        key: String = "abc123",
        site: String = "YouTube",
        type: String = "Trailer",
        name: String? = "Official Trailer",
        thumbnail: String? = null
    ) = TmdbVideo(key = key, site = site, type = type, name = name, thumbnail = thumbnail)

    // ── tabs: a page with nothing on it is not offered ──────────────────

    @Test
    fun `every page the title has art for is offered, in strip order`() {
        assertEquals(
            listOf(MediaTab.TRAILERS, MediaTab.BACKDROPS, MediaTab.POSTERS),
            DetailMediaRow.tabs(videoCount = 4, backdropCount = 9, posterCount = 27)
        )
        assertEquals(
            "the pages are offered in the strip's own order whatever the " +
                "counts, so a left-to-right walk is trailer, stills, posters",
            listOf(MediaTab.TRAILERS, MediaTab.POSTERS),
            DetailMediaRow.tabs(videoCount = 4, backdropCount = 0, posterCount = 27)
        )
    }

    @Test
    fun `a page with nothing on it is not offered`() {
        assertEquals(
            "videos only: a Backdrops or Posters tab would be a press whose " +
                "only outcome is nothing under it",
            listOf(MediaTab.TRAILERS),
            DetailMediaRow.tabs(videoCount = 4, backdropCount = 0, posterCount = 0)
        )
        assertEquals(
            listOf(MediaTab.BACKDROPS),
            DetailMediaRow.tabs(videoCount = 0, backdropCount = 9, posterCount = 0)
        )
        assertEquals(
            listOf(MediaTab.POSTERS),
            DetailMediaRow.tabs(videoCount = 0, backdropCount = 0, posterCount = 27)
        )
        assertTrue(
            "and a title with none of the three has no Media row at all - " +
                "which is what makes the row's presence in the chain the same " +
                "expression as the strip having a page",
            DetailMediaRow.tabs(videoCount = 0, backdropCount = 0, posterCount = 0).isEmpty()
        )
    }

    // ── the strip's LEFT/RIGHT ──────────────────────────────────────────

    @Test
    fun `a step moves one page over`() {
        assertEquals(1, DetailMediaRow.switched(index = 0, step = 1, count = 2))
        assertEquals(0, DetailMediaRow.switched(index = 1, step = -1, count = 2))
    }

    @Test
    fun `a step past the end of the strip clamps instead of wrapping`() {
        assertEquals(
            "LEFT on the first page is a press with nowhere to go: wrapping " +
                "would jump to the last page, from a key that reads as back",
            0,
            DetailMediaRow.switched(index = 0, step = -1, count = 2)
        )
        assertEquals(
            1,
            DetailMediaRow.switched(index = 1, step = 1, count = 2)
        )
    }

    @Test
    fun `a strip with no pages answers with a usable index`() {
        assertEquals(0, DetailMediaRow.switched(index = 0, step = 1, count = 0))
        assertEquals(0, DetailMediaRow.switched(index = 3, step = -1, count = 0))
    }

    // ── the trailer page ───────────────────────────────────────────────

    @Test
    fun `every video TMDB lists becomes a card, in TMDB's order`() {
        val cards = DetailMediaRow.trailers(
            listOf(
                video(key = "aaa", type = "Teaser"),
                video(key = "bbb", type = "Trailer"),
                video(key = "ccc", type = "Clip")
            )
        )

        assertEquals(3, cards.size)
        assertEquals(listOf("TEASER", "TRAILER", "CLIP"), cards.map { it.badge })
        assertEquals(
            "the card plays the video it was built from",
            listOf("aaa", "bbb", "ccc"),
            cards.map { it.video?.key }
        )
    }

    @Test
    fun `a YouTube video gets the still the video host serves for its key`() {
        val card = DetailMediaRow.trailers(listOf(video(key = "dQw4w9WgXcQ"))).single()

        assertEquals(
            "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            card.imageUrl
        )
    }

    @Test
    fun `a video on another site falls back to TMDB's own thumbnail`() {
        val vimeo = DetailMediaRow.trailers(
            listOf(
                video(
                    key = "12345",
                    site = "Vimeo",
                    thumbnail = "https://i.vimeocdn.com/video/12345.jpg"
                )
            )
        ).single()

        assertEquals(
            "a Vimeo key pasted into a YouTube path would paint a " +
                "broken-image tile",
            "https://i.vimeocdn.com/video/12345.jpg",
            vimeo.imageUrl
        )
        assertNull(
            "and with no thumbnail of its own there is no image, which the " +
                "card renders as its own name",
            DetailMediaRow.trailers(listOf(video(site = "Vimeo"))).single().imageUrl
        )
    }

    @Test
    fun `a video on a site the player cannot speak becomes an unplayable card, not no card`() {
        // Every video TMDB lists is a card of this page - that is the page's
        // whole point, and the deliberate design a test below already pins. What
        // this adds is the verdict the card needs to SAY: the trailer player
        // resolves one shape of source (`youtube.com/watch?v=<key>`), so a Vimeo
        // or Dailymotion entry can be drawn but not opened, and a press on it
        // used to build a YouTube URL out of a foreign key and fail silently.
        val cards = DetailMediaRow.trailers(
            listOf(
                video(key = "yt", site = "YouTube"),
                video(key = "12345", site = "Vimeo"),
                video(key = "x1y2z3", site = "Dailymotion"),
                video(key = "", site = "YouTube")
            )
        )

        assertEquals(
            "the card is kept, in TMDB's order, site and all",
            listOf("yt", "12345", "x1y2z3", ""),
            cards.map { it.video?.key }
        )
        assertEquals(
            "and only the YouTube one with a usable key is marked playable",
            listOf(true, false, false, false),
            cards.map { it.playable }
        )
    }

    @Test
    fun `the playability verdict is the site check the header's own pick makes`() {
        assertTrue("a YouTube video with a key plays", DetailMediaRow.isPlayable(video()))
        assertTrue(
            "the site name is matched like every other site check in the app",
            DetailMediaRow.isPlayable(video(key = "abcdef", site = "youtube"))
        )
        assertFalse(
            "another host is not a source the player can resolve",
            DetailMediaRow.isPlayable(video(site = "Vimeo"))
        )
        assertFalse(
            "and neither is a blank key on YouTube itself",
            DetailMediaRow.isPlayable(video(key = ""))
        )
    }

    @Test
    fun `artwork cards are not asked about playability`() {
        assertTrue(
            "a still carries no video, so the flag is never read for it: the " +
                "press opens the artwork either way",
            DetailMediaRow.backdrops(listOf(TmdbImageAsset(filePath = "/one.jpg"))).all {
                it.video == null && it.playable
            }
        )
    }

    @Test
    fun `a card is keyed uniquely even when TMDB lists one key twice`() {
        // A 4K and an HD copy of the same trailer: a lazy list's keys have to
        // be unique, and the videos themselves are not.
        val cards = DetailMediaRow.trailers(
            listOf(video(key = "same", name = "Trailer 4K"), video(key = "same", name = "Trailer HD"))
        )

        assertEquals(2, cards.size)
        assertEquals(cards.map { it.id }.toSet().size, cards.size)
    }

    @Test
    fun `a video with no type and no name still has a title`() {
        val card = DetailMediaRow.trailers(listOf(video(type = "", name = null))).single()

        assertNull("no type means no badge rather than an empty pill", card.badge)
        assertEquals("", card.title)
    }

    // ── the backdrop page ──────────────────────────────────────────────

    @Test
    fun `every backdrop becomes a card at the card and fullscreen rungs`() {
        val cards = DetailMediaRow.backdrops(
            listOf(
                TmdbImageAsset(filePath = "/one.jpg", width = 1920, height = 1080),
                TmdbImageAsset(filePath = "/two.jpg", iso6391 = "en")
            )
        )

        assertEquals(2, cards.size)
        assertEquals(
            listOf(
                "https://image.tmdb.org/t/p/w780/one.jpg",
                "https://image.tmdb.org/t/p/w780/two.jpg"
            ),
            cards.map { it.imageUrl }
        )
        assertEquals(
            "the card is a 240dp crop, so the press that opens it has to " +
                "show the frame the crop cut",
            listOf(
                "https://image.tmdb.org/t/p/w1280/one.jpg",
                "https://image.tmdb.org/t/p/w1280/two.jpg"
            ),
            cards.map { it.fullImageUrl }
        )
        assertTrue(
            "a still has no video: this is what tells the card to open the " +
                "viewer rather than the player",
            cards.all { it.video == null }
        )
    }

    @Test
    fun `a backdrop with no path is not a card`() {
        val cards = DetailMediaRow.backdrops(
            listOf(
                TmdbImageAsset(filePath = null),
                TmdbImageAsset(filePath = "   "),
                TmdbImageAsset(filePath = "/real.jpg")
            )
        )

        assertEquals(1, cards.size)
        assertEquals("backdrop:/real.jpg", cards.single().id)
    }

    // ── the poster page ────────────────────────────────────────────────

    @Test
    fun `every poster becomes a portrait card at the card and fullscreen rungs`() {
        val cards = DetailMediaRow.posters(
            listOf(
                TmdbImageAsset(filePath = "/one.jpg", width = 2000, height = 3000),
                TmdbImageAsset(filePath = "/two.jpg", iso6391 = "en")
            )
        )

        assertEquals(2, cards.size)
        assertEquals(
            listOf(
                "https://image.tmdb.org/t/p/w500/one.jpg",
                "https://image.tmdb.org/t/p/w500/two.jpg"
            ),
            cards.map { it.imageUrl }
        )
        assertEquals(
            "the card is a w500 poster, so the press that opens it shows the " +
                "artwork at the rung above the card",
            listOf(
                "https://image.tmdb.org/t/p/w780/one.jpg",
                "https://image.tmdb.org/t/p/w780/two.jpg"
            ),
            cards.map { it.fullImageUrl }
        )
        assertTrue(
            "a poster draws 2:3 portrait, unlike every still on the pages " +
                "beside it - which is the one thing the card needs told",
            cards.all { it.portrait }
        )
        assertTrue(
            "and it has no video: the press opens the artwork, not the player",
            cards.all { it.video == null }
        )
    }

    @Test
    fun `a still card is landscape and a poster card is not`() {
        assertTrue(
            "a trailer's still is 16:9 like every other card of the row",
            DetailMediaRow.trailers(listOf(video())).all { !it.portrait }
        )
        assertTrue(
            DetailMediaRow.backdrops(listOf(TmdbImageAsset(filePath = "/one.jpg")))
                .all { !it.portrait }
        )
    }

    @Test
    fun `a poster with no path is not a card`() {
        val cards = DetailMediaRow.posters(
            listOf(
                TmdbImageAsset(filePath = null),
                TmdbImageAsset(filePath = "   "),
                TmdbImageAsset(filePath = "/real.jpg")
            )
        )

        assertEquals(1, cards.size)
        assertEquals("poster:/real.jpg", cards.single().id)
    }

    @Test
    fun `a backdrop carries no badge`() {
        val cards = DetailMediaRow.backdrops(listOf(TmdbImageAsset(filePath = "/one.jpg")))

        assertNull(
            "the badge is the video TYPE, and a still has no type to speak",
            cards.single().badge
        )
    }
}
