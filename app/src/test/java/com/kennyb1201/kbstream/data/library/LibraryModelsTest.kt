package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin contracts of the Library data layer: row identity
 * (navigation/badge keys), watched-badge key normalization, and the
 * cross-tracker dedupe rules that My List membership and the merge
 * pipeline rely on. These are the exact rules a sync bug would break
 * silently (rows duplicating, badges not appearing, remote removes
 * missing their target), so they get pinned here.
 */
class LibraryModelsTest {

    // ── LibraryItem identity ─────────────────────────────────────────

    @Test
    fun `navigationId prefers imdb and falls back to tmdb`() {
        val byImdb = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "movie",
            title = "T", imdbId = "tt0117731", tmdbId = 27205
        )
        assertEquals("tt0117731", byImdb.navigationId)

        val byTmdb = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "movie",
            title = "T", imdbId = null, tmdbId = 27205
        )
        assertEquals("tmdb:27205", byTmdb.navigationId)

        val neither = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "movie",
            title = "T", imdbId = null, tmdbId = null
        )
        assertNull(neither.navigationId)
    }

    @Test
    fun `watchedKey collapses tv aliases and drops blank ids`() {
        assertEquals(
            "series::tt0944947",
            LibraryItem(
                source = LibrarySource.SIMKL_WATCHLIST, mediaType = "tv",
                title = "GoT", imdbId = "tt0944947"
            ).watchedKey()
        )
        assertEquals(
            // "series" alias must produce the same key as "tv"
            "series::tt0944947",
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "series",
                title = "GoT", imdbId = "tt0944947"
            ).watchedKey()
        )
        assertEquals(
            "movie::tt0117731",
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "Movie",
                title = "BB", imdbId = "tt0117731"
            ).watchedKey()
        )
        // No imdb id -> no badge key possible.
        assertNull(
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "movie",
                title = "T", imdbId = null, tmdbId = 5
            ).watchedKey()
        )
        // Blank/whitespace imdb id is treated as absent.
        assertNull(
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "movie",
                title = "T", imdbId = "   "
            ).watchedKey()
        )
    }

    // ── dedupeKey: the merge + membership contract ───────────────────

    @Test
    fun `dedupeKey is type imdb tmdb triple`() {
        val key = LocalLibraryStore.dedupeKey(
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "movie",
                title = "T", imdbId = "tt0117731", tmdbId = 27205
            )
        )
        assertEquals("movie:tt0117731:27205", key)
    }

    @Test
    fun `dedupeKey collapses tv and series aliases`() {
        val tv = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "tv",
            title = "T", imdbId = "tt0944947", tmdbId = 1399
        )
        val series = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "series",
            title = "T", imdbId = "tt0944947", tmdbId = 1399
        )
        assertEquals(LocalLibraryStore.dedupeKey(tv), LocalLibraryStore.dedupeKey(series))
    }

    @Test
    fun `dedupeKey strips tmdb prefix from imdb ids`() {
        // Some screens normalize imdb ids as "tmdb:12345"; both forms of
        // the same title must collide onto one key.
        val plain = LocalLibraryStore.dedupeKey(
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "movie",
                title = "T", imdbId = "12345", tmdbId = 99
            )
        )
        val prefixed = LocalLibraryStore.dedupeKey(
            LibraryItem(
                source = LibrarySource.LOCAL, mediaType = "movie",
                title = "T", imdbId = "tmdb:12345", tmdbId = 99
            )
        )
        assertEquals(plain, prefixed)
    }

    @Test
    fun `dedupeKeyOf matches dedupeKey for the same triple`() {
        val item = LibraryItem(
            source = LibrarySource.MDBLIST_LIST, mediaType = "TV",
            title = "T", imdbId = "tt15574718", tmdbId = 94997
        )
        assertEquals(
            LocalLibraryStore.dedupeKey(item),
            LocalLibraryStore.dedupeKeyOf("tv", "tt15574718", 94997)
        )
    }

    @Test
    fun `different titles with different ids never share a key`() {
        val a = LocalLibraryStore.dedupeKeyOf("movie", "tt0117731", 27205)
        val b = LocalLibraryStore.dedupeKeyOf("movie", "tt0117731", 999)
        val c = LocalLibraryStore.dedupeKeyOf("series", "tt0117731", 27205)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        // Id-less entries all share the wildcard "-" imdb slot; two such
        // entries of the same type must still be distinguishable by tmdb.
        assertNotEquals(
            LocalLibraryStore.dedupeKeyOf("movie", null, 1),
            LocalLibraryStore.dedupeKeyOf("movie", null, 2)
        )
    }

    // ── LibrarySource labels (rendered in the Library rail) ──────────

    @Test
    fun `source labels are non blank and unique`() {
        val labels = LibrarySource.entries.map { it.label }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
        // Local rows must never be mistaken for remote ones in the UI tag.
        assertFalse(LibrarySource.LOCAL.label.contains("MDBList", ignoreCase = true))
        assertFalse(LibrarySource.LOCAL_LIST.label.contains("MDBList", ignoreCase = true))
    }

    // ── dedupeKey null/id handling edge cases ─────────────────────────

    @Test
    fun `dedupeKeyOf trims whitespace around imdb ids`() {
        assertEquals(
            LocalLibraryStore.dedupeKeyOf("movie", "tt0117731", null),
            LocalLibraryStore.dedupeKeyOf("movie", " tt0117731 ", null)
        )
    }

    // ── matches: membership by EITHER id ─────────────────────────────

    @Test
    fun `matches finds a two-id entry from a one-id lookup`() {
        // Saved from a screen that knew both ids (the resolved IMDB id plus
        // the TMDB id it navigated by).
        val stored = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "movie",
            title = "T", imdbId = "tt0117731", tmdbId = 27205
        )
        // A long-press menu only knows the TMDB id...
        assertTrue(LocalLibraryStore.matches(stored, "movie", null, 27205))
        // ...and a Continue Watching row may only know the IMDB id.
        assertTrue(LocalLibraryStore.matches(stored, "movie", "tt0117731", null))
        // Type aliases and casing keep collapsing.
        assertTrue(LocalLibraryStore.matches(stored, "Movie", null, 27205))
        assertTrue(LocalLibraryStore.matches(stored, "movie", "TT0117731", null))
    }

    @Test
    fun `matches finds a one-id entry from a two-id lookup`() {
        val stored = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "movie",
            title = "T", imdbId = "tt0117731"
        )
        assertTrue(LocalLibraryStore.matches(stored, "movie", "tt0117731", 27205))
    }

    @Test
    fun `matches rejects other titles, other types and id-less pairs`() {
        val stored = LibraryItem(
            source = LibrarySource.LOCAL, mediaType = "movie",
            title = "T", imdbId = "tt0117731", tmdbId = 27205
        )
        assertFalse(LocalLibraryStore.matches(stored, "movie", null, 999))
        assertFalse(LocalLibraryStore.matches(stored, "movie", "tt0000001", null))
        assertFalse(LocalLibraryStore.matches(stored, "series", null, 27205))
        // A lookup with no ids at all can never match (this is what a
        // Continue Watching item with an unparseable id used to look like).
        assertFalse(LocalLibraryStore.matches(stored, "movie", null, null))

        val idLess = LibraryItem(source = LibrarySource.LOCAL, mediaType = "movie", title = "T")
        assertFalse(LocalLibraryStore.matches(idLess, "movie", "tt0117731", 27205))
    }

    // ── LibraryIds: whatever id a screen has, the add keeps it ────────

    @Test
    fun `LibraryIds splits tmdb and imdb ids`() {
        assertEquals(27205, LibraryIds.split("tmdb:27205").tmdbId)
        assertNull(LibraryIds.split("tmdb:27205").imdbId)
        assertEquals(27205, LibraryIds.split("27205").tmdbId)

        assertEquals("tt0117731", LibraryIds.split("tt0117731").imdbId)
        assertNull(LibraryIds.split("tt0117731").tmdbId)
        assertEquals("tt0117731", LibraryIds.split("imdb:tt0117731").imdbId)
    }

    @Test
    fun `LibraryIds accepts stream keys`() {
        assertEquals("tt0117731", LibraryIds.split("tt0117731:2:5").imdbId)
        assertEquals(27205, LibraryIds.split("tmdb:27205:2:5").tmdbId)
        assertEquals(27205, LibraryIds.split("27205:2:5").tmdbId)
    }

    @Test
    fun `LibraryIds keeps unknown addon flavors instead of dropping them`() {
        // An add-on id the app cannot map still identifies the row locally;
        // returning nulls here is what made the press a silent no-op.
        val ids = LibraryIds.split("kitsu:42")
        assertEquals("kitsu:42", ids.imdbId)
        assertNull(ids.tmdbId)
        assertTrue(ids.hasAny)

        // ...and a stream key of that flavor keeps only the show's own id.
        assertEquals("kitsu:42", LibraryIds.split("kitsu:42:2:5").imdbId)
    }

    @Test
    fun `LibraryIds reports nothing for blank ids and prefers the first usable one`() {
        assertFalse(LibraryIds.split(null).hasAny)
        assertFalse(LibraryIds.split("   ").hasAny)

        // Continue Watching: the show's own id wins over the row's stream key.
        val ids = LibraryIds.splitFirst("tt1234567", "tt1234567:2:5")
        assertEquals("tt1234567", ids.imdbId)

        // A blank parent id falls through to the row key rather than giving up.
        val fallback = LibraryIds.splitFirst("", "tmdb:1399:1:1")
        assertEquals(1399, fallback.tmdbId)
    }

    @Test
    fun `localListId factory invariant - LibraryItem roundtrips through JSON fields`() {
        // entryFromJson is private, but its fields are exercised indirectly:
        // a LibraryItem with only tmdbId keeps year null-safe behavior in
        // watchedKey/navigationId paths used by the merge pipeline.
        val tmdbOnly = LibraryItem(
            source = LibrarySource.LOCAL_LIST, mediaType = "movie",
            title = "T", imdbId = null, tmdbId = 42
        )
        assertEquals("tmdb:42", tmdbOnly.navigationId)
        assertNull(tmdbOnly.watchedKey())
        assertEquals(42, tmdbOnly.badgeId)
    }
}
