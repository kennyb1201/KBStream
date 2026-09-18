package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts of the ALL view's cross-source merge and the UNWATCHED chip.
 *
 *  - mergeById keeps the first occurrence (input order = priority: local
 *    rows before remote ones), so a title present in My List AND the
 *    Simkl watchlist AND an MDBList list renders exactly one row.
 *  - applyUnwatched drops watched rows, keeps rows that cannot be
 *    resolved to a badge key yet (no IMDB id), and is a no-op when off.
 */
class LibraryMergeTest {

    private fun item(
        title: String,
        source: LibrarySource,
        imdb: String? = null,
        tmdb: Int? = null,
        mediaType: String = "movie"
    ) = LibraryItem(
        source = source, mediaType = mediaType, title = title,
        imdbId = imdb, tmdbId = tmdb
    )

    // ── cross-source dedupe (mirrors the ViewModel's mergeById rule) ───

    @Test
    fun `first occurrence wins on dedupe collision`() {
        // LinkedHashMap#putIfAbsent semantics: the earlier entry stays.
        val local = item("Inception", LibrarySource.LOCAL, imdb = "tt1375666", tmdb = 27205)
        val simkl = item("Inception", LibrarySource.SIMKL_WATCHLIST, imdb = "tt1375666", tmdb = 27205)
        val seen = LinkedHashMap<String, LibraryItem>()
        listOf(local, simkl).forEach {
            seen.putIfAbsent(LocalLibraryStoreForTest.dedupeKey(it), it)
        }
        assertEquals(local, seen.values.first())
        assertEquals(1, seen.size)
    }

    @Test
    fun `same title across all three sources collapses to one row`() {
        val entries = listOf(
            item("Dune", LibrarySource.LOCAL, imdb = "tt15239678", tmdb = 447365),
            item("Dune", LibrarySource.MDBLIST_WATCHLIST, imdb = "tt15239678", tmdb = 447365),
            item("Dune", LibrarySource.LOCAL_LIST, imdb = "tt15239678", tmdb = 447365)
        )
        val seen = LinkedHashMap<String, LibraryItem>()
        entries.forEach { seen.putIfAbsent(LocalLibraryStoreForTest.dedupeKey(it), it) }
        assertEquals(1, seen.size)
    }

    @Test
    fun `distinct titles survive the merge`() {
        val a = item("A", LibrarySource.LOCAL, imdb = "tt1")
        val b = item("B", LibrarySource.SIMKL_WATCHLIST, imdb = "tt2")
        val seen = LinkedHashMap<String, LibraryItem>()
        listOf(a, b).forEach { seen.putIfAbsent(LocalLibraryStoreForTest.dedupeKey(it), it) }
        assertEquals(2, seen.size)
    }

    @Test
    fun `tv alias and type difference do not collapse distinct shows`() {
        val series = item("Severance", LibrarySource.LOCAL, imdb = "tt11280740", mediaType = "series")
        val movie = item("Severance", LibrarySource.MDBLIST_WATCHLIST, imdb = "tt11280740", mediaType = "movie")
        assertFalse(
            LocalLibraryStoreForTest.dedupeKey(series) ==
                LocalLibraryStoreForTest.dedupeKey(movie)
        )
    }

    // ── UNWATCHED filter (asserts against the real extracted helper) ───

    @Test
    fun `unwatched off keeps everything`() {
        val items = listOf(
            item("W", LibrarySource.LOCAL, imdb = "tt1"),
            item("U", LibrarySource.LOCAL, imdb = "tt2")
        )
        val out = applyUnwatched(
            items,
            watchedKeys = setOf("movie::tt1"),
            hide = false
        )
        assertEquals(items, out)
    }

    @Test
    fun `unwatched on drops watched rows only`() {
        val watched = item("Watched", LibrarySource.LOCAL, imdb = "tt1")
        val fresh = item("Fresh", LibrarySource.LOCAL, imdb = "tt2")
        val out = applyUnwatched(
            listOf(watched, fresh),
            watchedKeys = setOf("movie::tt1"),
            hide = true
        )
        assertEquals(listOf(fresh), out)
    }

    @Test
    fun `rows without a badge key are never hidden`() {
        val noKey = item("TmdbOnly", LibrarySource.LOCAL, imdb = null, tmdb = 42)
        val out = applyUnwatched(
            listOf(noKey),
            watchedKeys = setOf("movie::tt1", "series::tt2"),
            hide = true
        )
        assertEquals(listOf(noKey), out)
    }

    @Test
    fun `series watched key hides only series rows`() {
        val series = item("Show", LibrarySource.LOCAL, imdb = "tt9", mediaType = "series")
        val movie = item("Flick", LibrarySource.LOCAL, imdb = "tt9", mediaType = "movie")
        val out = applyUnwatched(
            listOf(series, movie),
            watchedKeys = setOf("series::tt9"),
            hide = true
        )
        assertEquals(listOf(movie), out)
    }

    @Test
    fun `empty watched set is a no-op even when hiding`() {
        val items = listOf(item("A", LibrarySource.LOCAL, imdb = "tt1"))
        assertEquals(
            items,
            applyUnwatched(items, watchedKeys = emptySet(), hide = true)
        )
    }

    // ── pipeline composition: sort then unwatched keeps both contracts ──

    @Test
    fun `sorted-then-filtered pipeline keeps ordering and drops watched`() {
        val a = item("Banana", LibrarySource.LOCAL, imdb = "tt1")
        val b = item("apple", LibrarySource.LOCAL, imdb = "tt2")
        val c = item("Cherry", LibrarySource.LOCAL, imdb = "tt3")
        val ratings = emptyMap<String, Double>()
        val watched = setOf("movie::tt2")

        val out = applyUnwatched(
            sortLibraryItems(listOf(a, b, c), LibrarySort.TITLE, ratings),
            watchedKeys = watched,
            hide = true
        )
        // "apple" (tt2) is watched and drops; the rest stay alphabetical.
        assertEquals(listOf("Banana", "Cherry"), out.map { it.title })
    }

    /** Test bridge: the store object is pure Kotlin for key computation. */
    private object LocalLibraryStoreForTest {
        fun dedupeKey(item: LibraryItem): String {
            val type = when (item.mediaType.lowercase()) {
                "tv", "series" -> "series"
                else -> "movie"
            }
            val imdb = item.imdbId?.trim()
                ?.removePrefix("tmdb:")
                ?.takeIf { it.isNotBlank() }
                ?: "-"
            return "$type:$imdb:${item.tmdbId ?: "-"}"
        }
    }
}
