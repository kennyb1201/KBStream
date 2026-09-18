package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
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

    // ── TITLE ────────────────────────────────────────────────────────

    @Test
    fun `TITLE sorts case-insensitively`() {
        val sorted = sortLibraryItems(
            listOf(item("banana"), item("Apple"), item("cherry")),
            LibrarySort.TITLE, emptyMap()
        )
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun `TITLE sort is stable for equal names`() {
        val a = item("Same", imdb = "tt1")
        val b = item("Same", imdb = "tt2")
        val sorted = sortLibraryItems(listOf(b, a), LibrarySort.TITLE, emptyMap())
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
}
