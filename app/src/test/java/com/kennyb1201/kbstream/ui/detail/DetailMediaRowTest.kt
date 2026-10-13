package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Media row's rules: which pages it offers, which page a LEFT/RIGHT reaches,
 * and what a card of either page is.
 *
 * The row is one entry of the vertical focus chain that holds two rails, so the
 * two things that decide what a viewer sees when they press a key in it - the
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
    fun `both pages are offered when the title has both`() {
        assertEquals(
            listOf(MediaTab.TRAILERS, MediaTab.BACKDROPS),
            DetailMediaRow.tabs(videoCount = 4, backdropCount = 9)
        )
    }

    @Test
    fun `a page with nothing on it is not offered`() {
        assertEquals(
            "videos only: a Backdrops tab would be a press whose only " +
                "outcome is nothing under it",
            listOf(MediaTab.TRAILERS),
            DetailMediaRow.tabs(videoCount = 4, backdropCount = 0)
        )
        assertEquals(
            listOf(MediaTab.BACKDROPS),
            DetailMediaRow.tabs(videoCount = 0, backdropCount = 9)
        )
        assertTrue(
            "and a title with neither has no Media row at all - which is " +
                "what makes the row's presence in the chain the same " +
                "expression as the strip having a page",
            DetailMediaRow.tabs(videoCount = 0, backdropCount = 0).isEmpty()
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

    @Test
    fun `a backdrop carries no badge`() {
        val cards = DetailMediaRow.backdrops(listOf(TmdbImageAsset(filePath = "/one.jpg")))

        assertNull(
            "the badge is the video TYPE, and a still has no type to speak",
            cards.single().badge
        )
    }
}
