package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.library.TasteSignals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The taste tiebreak on the merged TMDB search waves ([searchTitleOrder]).
 *
 * Search relevance is the consumer's primary job and taste does not outrank it.
 * The rule is the comparator's POSITION, and its whole content:
 *
 *  - match quality (exact / prefix / contains) is the first key,
 *  - the year is the second,
 *  - the viewer's own signal is the third,
 *  - the name is the last.
 *
 * So a liked hit only ever moves past an un-signalled one from the SAME match
 * band and the SAME year - never past a better match, never past a newer
 * release. Nothing here hides or drops a result: a disliked title is ordered
 * last inside its tie group and stays in the list.
 *
 * An empty snapshot places every hit at `TastePlacement.NONE`, which is what
 * makes the taste-free output identical to the pre-taste one.
 */
class SearchTasteOrderTest {

    private fun hit(
        id: Int,
        name: String,
        year: Int? = null,
        type: String = "movie"
    ) = SearchTitleResult(
        id = "tmdb:$id",
        type = type,
        name = name,
        poster = null,
        year = year,
        meta = MetaPreview(id = "tmdb:$id", type = type, name = name)
    )

    /** The merged order, as the ids that came out of it. */
    private fun orderIds(
        hits: List<SearchTitleResult>,
        query: String,
        taste: TasteSignals.Snapshot = TasteSignals.Snapshot()
    ) = hits.sortedWith(searchTitleOrder(query.trim().lowercase(), taste)).map { it.id }

    /** The STORE'S OWN spelling, so the test cannot pin a key the store never writes. */
    private fun signalled(tmdb: Int, type: String = "movie") =
        TasteSignals.keysFor(type, listOf("tmdb:$tmdb"), null, null)

    @Test
    fun `equal band and equal year break on taste, liked first and disliked last`() {
        val liked = hit(1, "Dune", 2021)
        val plain = hit(2, "Dune", 2021)
        val disliked = hit(3, "Dune", 2021)
        val taste = TasteSignals.Snapshot(
            liked = signalled(1),
            disliked = signalled(3)
        )
        assertEquals(
            listOf("tmdb:1", "tmdb:2", "tmdb:3"),
            orderIds(listOf(disliked, plain, liked), "Dune", taste)
        )
    }

    @Test
    fun `a disliked exact match still leads a liked weaker match`() {
        // Taste must never cross a match band. The exact-name match the viewer
        // disliked is still the first card; it only ever sinks within its band.
        val exact = hit(1, "Dune", 2021)
        val contains = hit(2, "Dune Part Two", 2021)
        val taste = TasteSignals.Snapshot(
            liked = signalled(2),
            disliked = signalled(1)
        )
        assertEquals(
            listOf("tmdb:1", "tmdb:2"),
            orderIds(listOf(contains, exact), "Dune", taste)
        )
    }

    @Test
    fun `a newer un-signalled hit still leads an older liked one`() {
        // Year outranks taste, which is the comparator position this pins.
        val olderLiked = hit(1, "Dune", 1984)
        val newerPlain = hit(2, "Dune", 2021)
        val taste = TasteSignals.Snapshot(liked = signalled(1))
        assertEquals(
            listOf("tmdb:2", "tmdb:1"),
            orderIds(listOf(olderLiked, newerPlain), "Dune", taste)
        )
    }

    @Test
    fun `a disliked hit is ordered last but never dropped`() {
        val disliked = hit(1, "Dune", 2021)
        val plain = hit(2, "Dune", 2021)
        val taste = TasteSignals.Snapshot(disliked = signalled(1))
        val sorted = listOf(disliked, plain).sortedWith(searchTitleOrder("dune", taste))
        assertEquals(2, sorted.size)
        assertEquals(listOf("tmdb:2", "tmdb:1"), sorted.map { it.id })
    }

    @Test
    fun `an empty snapshot is the pre-taste order`() {
        val hits = listOf(
            hit(1, "Dune Part Two", 2024),
            hit(2, "Dune", 2021),
            hit(3, "Dune", 1984),
            hit(4, "Dunerunner", 2021)
        )
        // The comparator exactly as it stood before taste existed.
        val legacy = hits.sortedWith(
            compareByDescending<SearchTitleResult> { searchMatchScore("dune", it.name) }
                .thenByDescending { it.year ?: 0 }
                .thenBy { it.name.lowercase() }
        )
        assertEquals("nothing may be dropped", hits.size, legacy.size)
        assertEquals(legacy.map { it.id }, orderIds(hits, "Dune"))
        assertEquals(
            "the default snapshot must be the empty one",
            legacy.map { it.id },
            orderIds(hits, "Dune", TasteSignals.Snapshot())
        )
    }

    @Test
    fun `a hit with no usable id reads as un-signalled`() {
        val noId = SearchTitleResult(
            id = "",
            type = "movie",
            name = "Dune",
            poster = null,
            year = 2021,
            meta = MetaPreview(id = "", type = "movie", name = "Dune")
        )
        val liked = hit(1, "Dune", 2021)
        val taste = TasteSignals.Snapshot(liked = signalled(1))
        // No crash, and the id-less hit sinks to un-signalled rather than
        // borrowing a signal off a shared name.
        assertEquals(
            listOf("tmdb:1", ""),
            orderIds(listOf(noId, liked), "Dune", taste)
        )
    }

    @Test
    fun `a signal recorded under a series id does not reach the same-numbered movie`() {
        // A TMDB id is a number movies and shows share, so the store keys it
        // with the media type - and the tiebreak has to ask with it.
        val movie = hit(1399, "Dune", 2021, type = "movie")
        val show = hit(1399, "Dune", 2021, type = "series")
        val taste = TasteSignals.Snapshot(liked = signalled(1399, type = "series"))
        assertEquals(
            listOf("series", "movie"),
            listOf(movie, show)
                .sortedWith(searchTitleOrder("dune", taste))
                .map { it.type }
        )
    }
}
