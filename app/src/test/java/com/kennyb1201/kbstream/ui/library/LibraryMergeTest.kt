package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Contracts of the ALL view's cross-source merge and the UNWATCHED chip.
 *
 *  - mergeById keeps the first occurrence (input order = priority: local
 *    rows before remote ones), so a title present in My List AND the
 *    Simkl watchlist AND an MDBList list renders exactly one row.
 *  - applyUnwatched drops watched rows, keeps rows that cannot be
 *    resolved to a badge key yet (no IMDB id), and is a no-op when off.
 *  - applyYears backfills a release year a source never sent (the pinned
 *    "Top Today" catalogs send none) without ever overwriting one that a
 *    row already carries.
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

    // ── release-year backfill (asserts against the real extracted helper) ──

    @Test
    fun `a row whose source sent no year takes the resolved one`() {
        // The pinned "Top Today" previews hold an IMDB id, a name and artwork
        // and nothing else, which is how a row saved from one of those rails
        // ended up as the only title in the grid with no year under it.
        val saved = item("UNABOMBER", LibrarySource.LOCAL, imdb = "tt6933238")
        val years = mapOf("movie:tt6933238:-" to 2026)

        assertEquals(listOf(2026), applyYears(listOf(saved), years).map { it.year })
    }

    @Test
    fun `a year the row already carries is never overwritten`() {
        val dated = item("Dune", LibrarySource.LOCAL, imdb = "tt15239678", tmdb = 447365)
            .copy(year = 2021)
        val years = mapOf("movie:tt15239678:447365" to 1999)

        assertEquals(listOf(2021), applyYears(listOf(dated), years).map { it.year })
    }

    @Test
    fun `a row with no resolved year is left alone`() {
        val unknown = item("Mystery", LibrarySource.LOCAL, imdb = "tt1")
        val years = mapOf("movie:tt2:-" to 2020)

        assertEquals(listOf(unknown), applyYears(listOf(unknown), years))
    }

    @Test
    fun `an empty map is a no-op`() {
        val items = listOf(item("A", LibrarySource.LOCAL, imdb = "tt1"))

        assertEquals(items, applyYears(items, emptyMap()))
    }

    @Test
    fun `the DATE chip orders by a backfilled year`() {
        // The sort reads the row it is handed, so a year applied after the
        // sort would caption correctly and still order wrongly.
        val old = item("Old", LibrarySource.LOCAL, imdb = "tt1").copy(year = 1999)
        val backfilled = item("New", LibrarySource.LOCAL, imdb = "tt2")
        val years = mapOf("movie:tt2:-" to 2024)

        val sorted = sortLibraryItems(
            applyYears(listOf(old, backfilled), years),
            LibrarySort.RELEASE_DATE,
            emptyMap()
        )

        assertEquals(listOf("New", "Old"), sorted.map { it.title })
    }

    // ── cross-tracker flavor twins (asserts the real extracted merge) ──

    @Test
    fun `an imdb-only twin collapses with a both-ids twin`() {
        // The exact-triple dedupeKey gave these different keys ("movie:tt…:-"
        // against "movie:tt…:447365"), so the title drew twice. They share the
        // IMDB id, which is what the either-id rule matches on.
        val simkl = item("Dune", LibrarySource.SIMKL_WATCHLIST, imdb = "tt15239678")
        val mdb = item(
            "Dune",
            LibrarySource.MDBLIST_WATCHLIST,
            imdb = "tt15239678",
            tmdb = 447365
        )

        assertEquals(listOf(simkl), mergeLibraryItemsById(listOf(simkl, mdb)))
    }

    @Test
    fun `a tmdb-only twin collapses with a both-ids twin`() {
        val simkl = item("Dune", LibrarySource.SIMKL_WATCHLIST, tmdb = 447365)
        val mdb = item(
            "Dune",
            LibrarySource.MDBLIST_WATCHLIST,
            imdb = "tt15239678",
            tmdb = 447365
        )

        assertEquals(listOf(simkl), mergeLibraryItemsById(listOf(simkl, mdb)))
    }

    @Test
    fun `the earlier source wins when flavor twins meet`() {
        val simkl = item("Dune", LibrarySource.SIMKL_WATCHLIST, imdb = "tt15239678", tmdb = 447365)
        val mdb = item("Dune", LibrarySource.MDBLIST_WATCHLIST, imdb = "tt15239678", tmdb = 447365)

        assertEquals(listOf(simkl), mergeLibraryItemsById(listOf(simkl, mdb)))
    }

    @Test
    fun `a twin's extra id folds onto the kept row so a third flavor still matches`() {
        val simkl = item("Dune", LibrarySource.SIMKL_WATCHLIST, imdb = "tt15239678")
        val both = item("Dune", LibrarySource.MDBLIST_WATCHLIST, imdb = "tt15239678", tmdb = 447365)
        val tmdbOnly = item("Dune", LibrarySource.LOCAL_LIST, tmdb = 447365)

        val merged = mergeLibraryItemsById(listOf(simkl, both, tmdbOnly))

        assertEquals(1, merged.size)
        assertEquals(simkl, merged.first())
    }

    @Test
    fun `the same tmdb id under two trackers collapses`() {
        val a = item("Dune", LibrarySource.MDBLIST_WATCHLIST, tmdb = 447365)
        val b = item("Dune", LibrarySource.MDBLIST_LIST, tmdb = 447365)

        assertEquals(listOf(a), mergeLibraryItemsById(listOf(a, b)))
    }

    @Test
    fun `a movie and a series sharing an imdb id are never merged`() {
        val movie = item("Severance", LibrarySource.LOCAL, imdb = "tt11280740", mediaType = "movie")
        val series = item("Severance", LibrarySource.SIMKL_WATCHLIST, imdb = "tt11280740", mediaType = "series")

        assertEquals(2, mergeLibraryItemsById(listOf(movie, series)).size)
    }

    @Test
    fun `distinct titles keep their input order`() {
        val a = item("A", LibrarySource.LOCAL, imdb = "tt1")
        val b = item("B", LibrarySource.SIMKL_WATCHLIST, imdb = "tt2")
        val c = item("C", LibrarySource.MDBLIST_WATCHLIST, tmdb = 3)

        assertEquals(listOf(a, b, c), mergeLibraryItemsById(listOf(a, b, c)))
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
