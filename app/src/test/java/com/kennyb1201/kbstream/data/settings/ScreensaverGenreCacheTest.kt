package com.kennyb1201.kbstream.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted genre cache's format and its cap, without a device.
 *
 * These are the two things a disk-backed cache can get wrong silently: a line
 * that does not survive the round trip (the label disappears after a restart,
 * which is exactly the Fire Stick report this cache exists to fix), and a store
 * that grows without end. Both are pure functions, so both are pinned here.
 */
class ScreensaverGenreCacheTest {

    @Test
    fun `a stored map survives the round trip, in insertion order`() {
        val genres = linkedMapOf(
            "tmdb:1399:series" to "Action · Drama",
            "tt0111161:movie" to "Drama",
            "tmdb:550:movie" to "Thriller · Mystery"
        )
        assertEquals(genres, decodeScreensaverGenres(encodeScreensaverGenres(genres)))
    }

    @Test
    fun `separators and backslashes inside a value cannot split a record`() {
        val genres = linkedMapOf("tmdb:1:movie" to "Sci-Fi\tHorror\nPart\\Two")
        val encoded = encodeScreensaverGenres(genres)
        assertEquals(1, encoded.lines().size)
        assertEquals(genres, decodeScreensaverGenres(encoded))
    }

    @Test
    fun `nothing stored reads as an empty map, not a crash`() {
        assertTrue(decodeScreensaverGenres(null).isEmpty())
        assertTrue(decodeScreensaverGenres("").isEmpty())
        assertTrue(decodeScreensaverGenres("garbage-without-a-tab").isEmpty())
        assertTrue(decodeScreensaverGenres("\tno-key").isEmpty())
        assertTrue(decodeScreensaverGenres("key-only\t").isEmpty())
    }

    @Test
    fun `the cap evicts the oldest entry, and a re-resolved title is the newest`() {
        var genres = putScreensaverGenre(emptyMap(), "a", "Drama", limit = 3)
        genres = putScreensaverGenre(genres, "b", "Action", limit = 3)
        genres = putScreensaverGenre(genres, "c", "Comedy", limit = 3)
        assertEquals(setOf("a", "b", "c"), genres.keys)

        // Re-resolving "a" makes it newest, so the NEXT new title evicts "b".
        genres = putScreensaverGenre(genres, "a", "Drama · Crime", limit = 3)
        genres = putScreensaverGenre(genres, "d", "Horror", limit = 3)
        assertEquals(listOf("c", "a", "d"), genres.keys.toList())
        assertEquals("Drama · Crime", genres["a"])
    }

    @Test
    fun `a blank key or genre writes nothing`() {
        val existing = mapOf("a" to "Drama")
        assertEquals(existing, putScreensaverGenre(existing, "  ", "Action"))
        assertEquals(existing, putScreensaverGenre(existing, "b", "   "))
        assertEquals(mapOf("x" to "Horror"), putScreensaverGenre(emptyMap(), " x ", " Horror "))
    }

    @Test
    fun `a malformed line in an older file costs that line, not the cache`() {
        val raw = "tmdb:1:movie\tDrama\nbroken\n\ntmdb:2:series\tComedy"
        assertEquals(
            mapOf("tmdb:1:movie" to "Drama", "tmdb:2:series" to "Comedy"),
            decodeScreensaverGenres(raw)
        )
    }
}
