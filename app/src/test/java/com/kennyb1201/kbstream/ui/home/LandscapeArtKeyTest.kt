package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The one thing a landscape card's artwork lookup cannot fail loudly at: the key
 * it is filed under.
 *
 * The writer and the reader live in different files - the rail builders in
 * `HomeViewModel`, the card in `HomeScreen`, the folder screens in `ui/kb` - and
 * are never compiled against each other, so a drifted format produces no error
 * at all: cards quietly fall back to their add-on backdrop, and every rebuild
 * re-resolves artwork the app had already resolved.
 *
 * These pin the shape rather than the mechanism. If the format ever has to
 * change, the failure is the point: every consumer - the rail builders, the
 * landscape card, the view model's resolved-art map and the KB folder layouts -
 * has to be changed with it.
 */
class LandscapeArtKeyTest {

    @Test
    fun `the key is the media type and the id joined by a colon`() {
        assertEquals("movie:tmdb:603", landscapeArtKey("movie", "tmdb:603"))
        assertEquals("series:tt0944947", landscapeArtKey("series", "tt0944947"))
    }

    @Test
    fun `the type is part of the identity, not decoration`() {
        // A TMDB id and an add-on's id for a show can be the same string; the
        // type is what keeps a film's backdrop off a show's card.
        assertNotEquals(
            landscapeArtKey("movie", "603"),
            landscapeArtKey("series", "603")
        )
    }

    @Test
    fun `an id that carries its own separator is not split again`() {
        // Add-on rails namespace their ids ("tmdb:603"), so a real key holds
        // more than one colon. The key is opaque: built and compared, never
        // taken apart - a caller that split on ':' would read the namespace as
        // the type and never match a filed entry.
        assertEquals("movie:tmdb:603", landscapeArtKey("movie", "tmdb:603"))
        assertNotEquals(
            landscapeArtKey("movie", "tmdb:603"),
            landscapeArtKey("movie", "tmdb")
        )
    }

    @Test
    fun `every spelling of a medium files under one key`() {
        // The type in the key is the type the artwork was looked up BY, so a
        // show typed "tv" by one add-on and "series" by another shares a single
        // entry instead of paying a second lookup for the second spelling.
        val spellings = listOf("movie", "anime.movie")
        spellings.forEach { type ->
            assertEquals("movie:603", landscapeArtKey(type, "603"))
        }

        listOf("series", "show", "tv", "anime", "anime.series").forEach { type ->
            assertEquals("series:603", landscapeArtKey(type, "603"))
        }

        // Case and padding are the same type, not a new entry.
        assertEquals("movie:603", landscapeArtKey("  MOVIE ", "603"))
    }

    @Test
    fun `a type TMDB cannot name still gets a key`() {
        // The key has to be derivable whatever the type is, or the entry the
        // resolver filed as "no artwork" would be invisible to the reader and
        // the item would be re-attempted on every build.
        assertEquals("channel:7", landscapeArtKey("channel", "7"))
        assertEquals("channel:7", landscapeArtKey("CHANNEL", "7"))
    }

    @Test
    fun `an item files under the same key as its parts`() {
        // The extension is what the view model and the card actually call, so it
        // must stay a delegation: a second spelling of the format here would be
        // exactly the drift these tests exist to catch.
        val meta = MetaPreview(id = "tmdb:603", type = "movie", name = "A Film")
        assertEquals(landscapeArtKey(meta.type, meta.id), meta.landscapeArtKey())
        assertEquals("movie:tmdb:603", meta.landscapeArtKey())
    }
}
