package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.TasteSignals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contracts of the Library tab's sort chips, pinned on the extracted pure
 * ordering ([sortLibraryItems]):
 *
 *  - ADDED preserves the store order (newest first — the ViewModel inserts
 *    at index 0, so ADDED must never reorder).
 *  - TITLE is case-insensitive ("a" before "B").
 *  - RELEASE_DATE sorts newest year first; missing years sink to the
 *    bottom (year null sorts as 0).
 *  - RATING sorts highest first, keyed by the same dedupe rule the merge
 *    pipeline uses; unrated titles sink below every rated one.
 *
 * Every one of those is the DESCENDING reading, which is [ascending]'s
 * default and the resting order a Library view opens in. The ascending half of
 * each pair is pinned separately: that is the FIRST tap on a sort chip (see
 * [LibraryViewModel.setSort]), and in that direction an unknown key — no year,
 * no resolved rating — must still sink to the bottom rather than float up.
 */
class LibrarySortTest {

    private fun item(
        title: String,
        year: Int? = null,
        imdb: String? = null,
        tmdb: Int? = null,
        mediaType: String = "movie",
        source: LibrarySource = LibrarySource.LOCAL
    ) = LibraryItem(
        source = source, mediaType = mediaType, title = title,
        year = year, imdbId = imdb, tmdbId = tmdb
    )

    // ── ADDED ────────────────────────────────────────────────────────

    @Test
    fun `ADDED preserves input order`() {
        val items = listOf(item("C"), item("A"), item("B"))
        assertEquals(items, sortLibraryItems(items, LibrarySort.ADDED, emptyMap()))
    }

    @Test
    fun `ADDED ascending reverses the store order`() {
        // The store is newest-first, so "oldest first" is its reverse. This is
        // the first tap on the ADDED chip.
        val items = listOf(item("C"), item("A"), item("B"))
        assertEquals(
            listOf("B", "A", "C"),
            sortLibraryItems(items, LibrarySort.ADDED, emptyMap(), ascending = true).map { it.title }
        )
    }

    // ── TITLE ────────────────────────────────────────────────────────

    @Test
    fun `TITLE ascending is case-insensitively A-first`() {
        // The first tap on the TITLE chip (ascending).
        val sorted = sortLibraryItems(
            listOf(item("banana"), item("Apple"), item("cherry")),
            LibrarySort.TITLE, emptyMap(), ascending = true
        )
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun `TITLE descending is case-insensitively Z-first`() {
        // ...and the second tap reverses exactly that pair.
        val sorted = sortLibraryItems(
            listOf(item("banana"), item("Apple"), item("cherry")),
            LibrarySort.TITLE, emptyMap(), ascending = false
        )
        assertEquals(listOf("cherry", "banana", "Apple"), sorted.map { it.title })
    }

    @Test
    fun `TITLE sort is stable for equal names`() {
        val a = item("Same", imdb = "tt1")
        val b = item("Same", imdb = "tt2")
        val sorted = sortLibraryItems(listOf(b, a), LibrarySort.TITLE, emptyMap(), ascending = true)
        // Equal keys keep their input relative order (sortedBy is stable).
        assertEquals(listOf(b, a), sorted)
    }

    // ── RELEASE_DATE ─────────────────────────────────────────────────

    @Test
    fun `RELEASE_DATE sorts newest first`() {
        val sorted = sortLibraryItems(
            listOf(item("Old", year = 1999), item("New", year = 2024), item("Mid", year = 2010)),
            LibrarySort.RELEASE_DATE, emptyMap()
        )
        assertEquals(listOf("New", "Mid", "Old"), sorted.map { it.title })
    }

    @Test
    fun `RELEASE_DATE sinks missing years to the bottom`() {
        val sorted = sortLibraryItems(
            listOf(item("NoYear"), item("Dated", year = 2001)),
            LibrarySort.RELEASE_DATE, emptyMap()
        )
        assertEquals(listOf("Dated", "NoYear"), sorted.map { it.title })
    }

    @Test
    fun `RELEASE_DATE ascending is oldest first, and still sinks missing years`() {
        // The first tap on the DATE chip. A row with no year is UNKNOWN, not a
        // year of zero: it must stay at the bottom rather than lead the list.
        val sorted = sortLibraryItems(
            listOf(item("Old", year = 1999), item("NoYear"), item("New", year = 2024)),
            LibrarySort.RELEASE_DATE, emptyMap(), ascending = true
        )
        assertEquals(listOf("Old", "New", "NoYear"), sorted.map { it.title })
    }

    // ── RATING ───────────────────────────────────────────────────────

    @Test
    fun `RATING sorts highest first and sinks unrated`() {
        val low = item("Low", imdb = "tt1")
        val high = item("High", imdb = "tt2")
        val unrated = item("Unrated", imdb = "tt3")
        val ratings = mapOf(
            "movie:tt1:-" to 6.5,
            "movie:tt2:-" to 9.1
        )
        val sorted = sortLibraryItems(
            listOf(unrated, low, high), LibrarySort.RATING, ratings
        )
        assertEquals(listOf("High", "Low", "Unrated"), sorted.map { it.title })
    }

    @Test
    fun `RATING ascending is lowest first, and still sinks unrated`() {
        // The first tap on the RATING chip - and the case a bare
        // `rating ?: 0.0` got wrong: an unrated row would have led the list.
        val low = item("Low", imdb = "tt1")
        val high = item("High", imdb = "tt2")
        val unrated = item("Unrated", imdb = "tt3")
        val ratings = mapOf(
            "movie:tt1:-" to 6.5,
            "movie:tt2:-" to 9.1
        )
        val sorted = sortLibraryItems(
            listOf(unrated, low, high), LibrarySort.RATING, ratings, ascending = true
        )
        assertEquals(listOf("Low", "High", "Unrated"), sorted.map { it.title })
    }

    @Test
    fun `RATING lookup uses the dedupe key including tmdb`() {
        // Two entries with the same imdb id but different tmdb ids get
        // different dedupe keys, so ratings can target one of them.
        val a = item("A", imdb = "tt9", tmdb = 1)
        val b = item("B", imdb = "tt9", tmdb = 2)
        val ratings = mapOf(
            "movie:tt9:1" to 4.0,
            "movie:tt9:2" to 8.0
        )
        val sorted = sortLibraryItems(listOf(a, b), LibrarySort.RATING, ratings)
        assertEquals(listOf("B", "A"), sorted.map { it.title })
    }

    @Test
    fun `RATING is stable among equally-rated titles`() {
        val a = item("A", imdb = "tt1")
        val b = item("B", imdb = "tt2")
        val ratings = mapOf("movie:tt1:-" to 7.0, "movie:tt2:-" to 7.0)
        val sorted = sortLibraryItems(listOf(b, a), LibrarySort.RATING, ratings)
        assertEquals(listOf(b, a), sorted)
    }

    // ── LIKED ────────────────────────────────────────────────────────

    /**
     * The STORE'S OWN spelling for one signalled title - the same function the
     * store writes its entries with, so a test cannot accidentally pin a key
     * the real store never produces.
     */
    private fun signalled(mediaType: String, vararg ids: String) =
        TasteSignals.keysFor(mediaType, ids.toList(), null, null)

    @Test
    fun `LIKED groups liked first, un-signalled in the middle, disliked last`() {
        val a = item("A", tmdb = 1)
        val b = item("B", tmdb = 2)
        val c = item("C", tmdb = 3)
        val d = item("D", tmdb = 4)
        val taste = TasteSignals.Snapshot(
            liked = signalled("movie", "tmdb:1", "tmdb:3"),
            disliked = signalled("movie", "tmdb:4")
        )
        val sorted = sortLibraryItems(
            listOf(a, b, c, d), LibrarySort.LIKED, emptyMap(), taste = taste
        )
        // Input order survives INSIDE a group: A and C are both liked and
        // nothing re-sorts them (no recency, no rating, no secondary key).
        assertEquals(listOf("A", "C", "B", "D"), sorted.map { it.title })
    }

    @Test
    fun `LIKED lists the new chip last so the existing strip is undisturbed`() {
        assertEquals(LibrarySort.LIKED, LibrarySort.entries.last())
    }

    @Test
    fun `LIKED ignores the direction flag, deliberately`() {
        val a = item("A", tmdb = 1)
        val b = item("B", tmdb = 2)
        val c = item("C", tmdb = 3)
        val taste = TasteSignals.Snapshot(
            liked = signalled("movie", "tmdb:3"),
            disliked = signalled("movie", "tmdb:1")
        )
        val descending = sortLibraryItems(
            listOf(a, b, c), LibrarySort.LIKED, emptyMap(), ascending = false, taste = taste
        )
        val ascending = sortLibraryItems(
            listOf(a, b, c), LibrarySort.LIKED, emptyMap(), ascending = true, taste = taste
        )
        assertEquals(listOf("C", "B", "A"), descending.map { it.title })
        assertEquals(
            "tapping the LIKED chip twice must yield the identical list",
            descending,
            ascending
        )
    }

    @Test
    fun `LIKED reads the add-on id flavor, so a twin-flavor signal lands`() {
        // Signalled from an add-on rail, which only knew the IMDB spelling:
        // that is stored untyped ("id::tt..."), not as "movie::tt...".
        val taste = TasteSignals.Snapshot(
            liked = TasteSignals.keysFor("movie", listOf("tt0133093"), null, null)
        )
        val sorted = sortLibraryItems(
            listOf(item("Plain", tmdb = 1), item("ByAddon", imdb = "tt0133093", tmdb = 603)),
            LibrarySort.LIKED, emptyMap(), taste = taste
        )
        assertEquals(listOf("ByAddon", "Plain"), sorted.map { it.title })
    }

    @Test
    fun `LIKED puts a row with no id in the middle group, never last`() {
        val noIds = item("NoIds")
        val disliked = item("Disliked", tmdb = 2)
        val taste = TasteSignals.Snapshot(
            liked = signalled("movie", "tmdb:1"),
            disliked = signalled("movie", "tmdb:2")
        )
        val sorted = sortLibraryItems(
            listOf(disliked, noIds, item("Liked", tmdb = 1)),
            LibrarySort.LIKED, emptyMap(), taste = taste
        )
        // An unknown key is not a small one: the id-less row is un-signalled,
        // so it sits between the liked row and the disliked one.
        assertEquals(listOf("Liked", "NoIds", "Disliked"), sorted.map { it.title })
    }

    @Test
    fun `LIKED with no signals is the input order`() {
        val items = listOf(item("C", tmdb = 3), item("A", tmdb = 1), item("B", tmdb = 2))
        assertEquals(
            items,
            sortLibraryItems(items, LibrarySort.LIKED, emptyMap())
        )
    }

    @Test
    fun `the other four sorts never read taste`() {
        val a = item("A", year = 2001, imdb = "tt1")
        val b = item("B", year = 2002, imdb = "tt2")
        val ratings = mapOf("movie:tt1:-" to 5.0, "movie:tt2:-" to 9.0)
        val taste = TasteSignals.Snapshot(
            liked = TasteSignals.keysFor("movie", listOf("tt1"), null, null),
            disliked = TasteSignals.keysFor("movie", listOf("tt2"), null, null)
        )
        LibrarySort.entries.filterNot { it == LibrarySort.LIKED }.forEach { sort ->
            listOf(false, true).forEach { ascending ->
                assertEquals(
                    "$sort must be untouched by taste",
                    sortLibraryItems(listOf(a, b), sort, ratings, ascending),
                    sortLibraryItems(listOf(a, b), sort, ratings, ascending, taste)
                )
            }
        }
    }
}
