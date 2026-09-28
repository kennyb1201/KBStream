package com.kennyb1201.kbstream.ui.kb

import com.kennyb1201.kbstream.ui.components.landscapeArtKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides whether a KB folder item is worth a TMDB artwork lookup,
 * and the key that item is filed under either way.
 *
 * Those two answers have to be independent. The resolver used to give both with
 * one normalizer and the layouts answered the key question with a second one:
 * the resolver dropped any type it did not recognize (filing nothing at all)
 * while the layouts read every unrecognized type as "movie", so for such an item
 * the reader looked up a key that could not exist and the resolver re-attempted
 * it on every rails change. Everything below is about that pair of questions
 * staying apart.
 */
class KBArtworkLookupTest {

    @Test
    fun `a film or a show is worth a lookup`() {
        listOf("movie", "anime.movie").forEach { type ->
            assertEquals("movie", kbArtworkLookupType(type))
        }
        listOf("series", "show", "tv", "anime", "anime.series").forEach { type ->
            assertEquals("series", kbArtworkLookupType(type))
        }
    }

    @Test
    fun `a type TMDB has no answer for costs no round trip`() {
        // Files and channels reach the folder screens from add-ons with their
        // own vocabulary. One fruitless request per item on a box this small is
        // the cost this rule exists to avoid.
        listOf("channel", "channels", "folder", "", "  ").forEach { type ->
            assertNull(type, kbArtworkLookupType(type))
        }
    }

    @Test
    fun `an item skipped by the lookup is still filed under a readable key`() {
        // The resolver files a null-artwork entry for these, and the layouts
        // must be able to name it - otherwise the entry is dead weight and the
        // item is looked up again on every rails change.
        assertNull(kbArtworkLookupType("channel"))
        assertEquals("channel:7", landscapeArtKey("channel", "7"))
    }

    @Test
    fun `a KB item and a Home rail item file under the same key`() {
        // One format across the two screens that resolve this artwork: a "tv"
        // folder item and a "series" rail item are the same medium, and the
        // whole point of the shared normalizer is that they cannot drift apart.
        assertEquals(
            landscapeArtKey("tv", "tt1"),
            landscapeArtKey("series", "tt1")
        )
        assertNotEquals(
            landscapeArtKey("tv", "tt1"),
            landscapeArtKey("movie", "tt1")
        )
    }
}
