package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The credits thumbs' identity arithmetic, pure.
 *
 * The key construction lives in a helper rather than inline in three players, so
 * it can be pinned here: the both-flavor rule (one signal for a title reached
 * from a TMDB rail and from an add-on rail) is what makes a thumb pressed in the
 * panel read back on that title's Detail page, and the TYPE SPELLING is where it
 * would silently break - `WatchedStatusKeys.key` lowercases and maps nothing, so
 * a raw `tv` or `show` from a player route has to have been mapped to `series`
 * by the caller before it gets here. A `tv::tmdb:1399` key would simply never
 * match anything the store, the rails or Detail write.
 */
class FinishedTitleKeysTest {

    @Test
    fun `both flavors are emitted, the IMDB one first`() {
        assertEquals(
            listOf("series::tt0133093", "series::tmdb:603"),
            finishedTitleKeys(mediaType = "series", tmdbId = 603, imdbId = "tt0133093")
        )
        assertEquals(
            listOf("movie::tt0133093", "movie::tmdb:603"),
            finishedTitleKeys(mediaType = "movie", tmdbId = 603, imdbId = "tt0133093")
        )
    }

    @Test
    fun `an unresolved IMDB twin leaves the TMDB flavor alone`() {
        assertEquals(
            "the panel opens on time with the TMDB flavor, rather than not opening",
            listOf("movie::tmdb:603"),
            finishedTitleKeys(mediaType = "movie", tmdbId = 603, imdbId = null)
        )
        assertEquals(
            "a blank resolution is no resolution",
            listOf("movie::tmdb:603"),
            finishedTitleKeys(mediaType = "movie", tmdbId = 603, imdbId = "   ")
        )
        assertEquals(
            "and a padded one is trimmed before it is keyed",
            listOf("movie::tt0133093", "movie::tmdb:603"),
            finishedTitleKeys(mediaType = "movie", tmdbId = 603, imdbId = " tt0133093 ")
        )
    }

    @Test
    fun `the type that reaches the key is the mapped one, never the route's raw spelling`() {
        // bywMediaType is the caller's half of the rule; this asserts both halves
        // land on `series`, which is the spelling every other surface writes.
        assertEquals("series", bywMediaType("series"))
        assertEquals("series", bywMediaType("tv"))
        assertEquals("series", bywMediaType("show"))
        assertEquals("series", bywMediaType("SERIES"))
        assertEquals("movie", bywMediaType("movie"))
        assertEquals("movie", bywMediaType(""))

        assertEquals(
            listOf("series::tmdb:1399"),
            finishedTitleKeys(mediaType = bywMediaType("tv"), tmdbId = 1399, imdbId = null)
        )
    }

    @Test
    fun `the store receives id flavors, never a type-prefixed key`() {
        val finished = FinishedTitle(
            keys = finishedTitleKeys("series", 603, "tt0133093"),
            title = "Fight Club",
            mediaType = "series"
        )

        // The store keys an id itself (HiddenTitles.keyFor): what it takes is the
        // flavor, with the media type travelling beside it.
        assertEquals(listOf("tt0133093", "tmdb:603"), finished.storeIds)
        assertEquals("series", finished.mediaType)
        assertEquals("Fight Club", finished.title)
    }

    @Test
    fun `a title-level identity carries no artwork of its own`() {
        // The panel supplies none: the store entry's poster is diagnostics-only,
        // and inventing one here would be a fetch the panel does not need.
        val finished = FinishedTitle(
            keys = listOf("movie::tmdb:603"),
            title = "Fight Club",
            mediaType = "movie"
        )
        assertNull(finished.posterUrl)
    }

    @Test
    fun `an id-flavor list that carries nothing usable stays empty`() {
        val finished = FinishedTitle(keys = listOf("::", ""), title = "x", mediaType = "movie")
        assertEquals(emptyList<String>(), finished.storeIds)
    }
}
