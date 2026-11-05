package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row rules the BingeCat section depends on.
 *
 * Two of them are load-bearing: a row with no id this app can open is dropped
 * rather than drawn as a broken card (spec F4), and a shelf id never collides
 * with a Trakt, MDBList or local one, because the Library addresses all four
 * through the same Int.
 */
class BingeCatRowsTest {

    private fun media(
        title: String? = "Heat",
        year: Int? = 1995,
        tmdb: Int? = null,
        imdb: String? = null
    ) = BingeCatMedia(
        title = title,
        year = year,
        ids = BingeCatIds(tmdb = tmdb, imdb = imdb)
    )

    @Test
    fun `a row keeps the ids its page needs`() {
        val row = bingecatRow(media(tmdb = 949, imdb = "tt0113277"), isMovie = true)!!
        assertEquals("Heat", row.title)
        assertEquals(1995, row.year)
        assertEquals(949, row.tmdbId)
        assertEquals("tt0113277", row.imdbId)
        assertEquals("movie", row.mediaType)
        assertEquals("series", bingecatRow(media(tmdb = 1), isMovie = false)!!.mediaType)
    }

    @Test
    fun `a title with no id at all is skipped, never guessed`() {
        assertNull(
            "an unresolvable row must be absent, not a card that opens nothing",
            bingecatRow(media(), isMovie = true)
        )
        assertNull("a blank title is not a row", bingecatRow(media(title = " "), isMovie = true))
        assertNull("no media object at all", bingecatRow(null, isMovie = true))
    }

    @Test
    fun `one usable id is enough`() {
        assertEquals("tt1", bingecatRow(media(imdb = "tt1"), isMovie = true)!!.imdbId)
        assertEquals(7, bingecatRow(media(tmdb = 7), isMovie = true)!!.tmdbId)
    }

    @Test
    fun `the watched key prefers imdb, like every other badge set`() {
        assertEquals(
            "movie::tt0113277",
            bingecatRow(media(tmdb = 949, imdb = "tt0113277"), isMovie = true)!!.watchedKey()
        )
        assertEquals(
            "series::tmdb:949",
            bingecatRow(media(tmdb = 949), isMovie = false)!!.watchedKey()
        )
    }

    @Test
    fun `the wire rows convert through the one rule`() {
        val saved = BingeCatSavedItem(
            type = "show",
            show = media(tmdb = 1399, imdb = "tt0944947")
        )
        assertEquals("series", bingecatSavedRow(saved)!!.mediaType)
        assertEquals(
            1399,
            bingecatListRow(
                BingeCatListItem(type = "show", show = media(tmdb = 1399))
            )!!.tmdbId
        )
        assertEquals(
            "movie::tt1",
            bingecatHistoryRow(
                BingeCatHistoryItem(type = "movie", movie = media(imdb = "tt1"))
            )!!.watchedKey()
        )
        // The kind field is the discriminator: a movie field on a show row is
        // not the media for that row.
        assertNull(
            bingecatSavedRow(BingeCatSavedItem(type = "show", movie = media(tmdb = 1)))
        )
    }

    @Test
    fun `a shelf id cannot collide with another source's`() {
        val first = bingecatShelfId(0)
        val second = bingecatShelfId(1)
        assertNotEquals(first, second)
        // Local list ids are negative creation times; MDBList ids positive;
        // Trakt owns Int.MIN_VALUE's run. BingeCat must sit outside all three.
        assertTrue("must not land in Trakt's run", first != Int.MIN_VALUE)
        assertTrue("must be outside the positive range", first < 0)
        assertNotEquals(
            "and clear of Trakt's first shelf",
            com.kennyb1201.kbstream.data.library.traktListShelfId(1),
            first
        )
    }
}
