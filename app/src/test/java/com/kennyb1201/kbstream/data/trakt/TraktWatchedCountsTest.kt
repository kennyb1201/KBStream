package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The connect screen's WATCHED tiles are list sizes, and only when both lists
 * actually answered.
 *
 * Trakt's `/sync/watched/movies` and `/sync/watched/shows` are two reads: one
 * can fail while the other succeeds. A half answer must hide the whole row -
 * showing "0 SHOWS WATCHED" beside a real movie count would be a lie about an
 * account that has watched hundreds of shows - and the connected state must
 * survive it. The pure rule is tested here; [TraktRepository.watchedCounts]
 * feeds it `null` for any failed answer.
 */
class TraktWatchedCountsTest {

    @Test
    fun `counts are the sizes of the two watched lists`() {
        val counts = traktWatchedCounts(
            movies = List(37) { TraktWatchedMovie(plays = it) },
            shows = List(12) { TraktWatchedShow(plays = it) }
        )

        assertEquals(TraktWatchedCounts(movies = 37, shows = 12), counts)
    }

    @Test
    fun `empty lists are a real zero, not a failure`() {
        val counts = traktWatchedCounts(movies = emptyList(), shows = emptyList())

        assertEquals(TraktWatchedCounts(movies = 0, shows = 0), counts)
    }

    @Test
    fun `a failed movie read hides the whole row`() {
        assertNull(
            traktWatchedCounts(
                movies = null,
                shows = List(5) { TraktWatchedShow(plays = it) }
            )
        )
    }

    @Test
    fun `a failed show read hides the whole row`() {
        assertNull(
            traktWatchedCounts(
                movies = List(9) { TraktWatchedMovie(plays = it) },
                shows = null
            )
        )
    }
}
