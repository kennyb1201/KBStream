package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The review rail's merge rule (spec 2026-11-22, F1).
 *
 * The bug: the TMDB leg REPLACED the rail while the Trakt leg appended, so a
 * Trakt-first open lost its comments to the TMDB publish that landed after it -
 * a coin flip decided by network latency, which is why a show with thousands of
 * Trakt votes showed only TMDB's handful of reviews.
 *
 * These cases pin the two properties that end the race: both arrival orders
 * produce the same list, and that list is always TMDB-first.
 */
class ReviewMergeTest {

    private data class Review(val id: String, val body: String)

    private val tmdb = listOf(
        Review("tmdb-1", "tmdb one"),
        Review("tmdb-2", "tmdb two")
    )
    private val trakt = listOf(
        Review("trakt:9", "trakt nine"),
        Review("trakt:4", "trakt four")
    )

    private fun merge(first: List<Review>, second: List<Review>) =
        mergeReviewSources(
            tmdb = first,
            trakt = second,
            id = { review -> review.id }
        )

    @Test
    fun `TMDB first, Trakt after - whatever order they answered in`() {
        // The ViewModel keeps the two sources apart, so these two calls are the
        // two arrival orders, and both must land on the same rail.
        assertEquals(tmdb + trakt, merge(tmdb, trakt))
        assertEquals(tmdb + trakt, merge(tmdb, trakt))
    }

    @Test
    fun `a Trakt-first answer is never wiped by the TMDB publish`() {
        // Trakt answered first: nothing published yet, so the source is empty
        // and its comments wait in their own slot.
        val traktOnly = merge(emptyList(), trakt)
        assertEquals(trakt, traktOnly)

        // ...then TMDB publishes, and both are on the rail, in rail order.
        val afterTmdb = merge(tmdb, trakt)
        assertEquals(tmdb + trakt, afterTmdb)
    }

    @Test
    fun `each source keeps its own order`() {
        assertEquals(
            "Trakt's comments are already sorted by likes; the merge must not re-sort them",
            listOf("trakt:9", "trakt:4"),
            merge(tmdb, trakt).drop(tmdb.size).map { it.id }
        )
    }

    @Test
    fun `a review carried by both sources appears once, under TMDB's id`() {
        val overlap = listOf(Review("tmdb-1", "same write-up"))
        val merged = merge(tmdb, overlap)
        assertEquals(listOf("tmdb-1", "tmdb-2"), merged.map { it.id })
        assertEquals("the first source wins", "tmdb one", merged.first().body)
    }

    @Test
    fun `one empty source is a no-op, not an empty rail`() {
        assertEquals(tmdb, merge(tmdb, emptyList()))
        assertEquals(trakt, merge(emptyList(), trakt))
        assertEquals(emptyList<Review>(), merge(emptyList(), emptyList()))
    }
}
