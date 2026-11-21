package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole F1 rule, in one class: a section's fetch is skipped only when its
 * stamp is known AND unmoved.
 *
 * `watchedSnapshot()` runs on every Home build and on every launch, so the cost
 * of getting this wrong is a badge that never updates (if a moved stamp reads as
 * unchanged) or a full history walk per launch (if an unchanged one reads as
 * moved). Both are pinned here, without a network or an Android context.
 */
class TraktWatchedStampsTest {

    private val stored = TraktWatchedStamps(
        movies = "2026-11-19T10:00:00.000Z",
        series = "2026-11-20T09:30:00.000Z"
    )

    @Test
    fun `identical stamps move nothing, so both sections are skipped`() {
        val now = stored.copy()

        assertFalse(now.moviesMovedSince(stored))
        assertFalse(now.seriesMovedSince(stored))
        assertTrue("the answer is provably unchanged", now.isComplete)
        assertEquals("a check, not a fetch", now, stored)
    }

    @Test
    fun `an episode watched elsewhere moves only the shows side`() {
        val watchedElsewhere = stored.copy(series = "2026-11-20T11:45:00.000Z")

        assertFalse("movies did not move", watchedElsewhere.moviesMovedSince(stored))
        assertTrue("an episode did", watchedElsewhere.seriesMovedSince(stored))
    }

    @Test
    fun `a movie watched elsewhere moves only the movies side`() {
        val watchedElsewhere = stored.copy(movies = "2026-11-20T11:45:00.000Z")

        assertTrue(watchedElsewhere.moviesMovedSince(stored))
        assertFalse("no episode moved", watchedElsewhere.seriesMovedSince(stored))
    }

    @Test
    fun `an unknown stamp is never read as unchanged`() {
        val noAnswer = TraktWatchedStamps(movies = null, series = null)
        val halfAnswer = TraktWatchedStamps(movies = null, series = stored.series)

        assertFalse(noAnswer.isComplete)
        assertFalse("a half answer cannot prove the other half unchanged", halfAnswer.isComplete)

        // Against a stored entry that has both stamps: the missing one must move.
        assertTrue(noAnswer.moviesMovedSince(stored))
        assertTrue(noAnswer.seriesMovedSince(stored))
        assertTrue(halfAnswer.moviesMovedSince(stored))
        assertFalse("but the section that DID answer was compared", halfAnswer.seriesMovedSince(stored))
    }

    @Test
    fun `no previous stamps means everything is fetched`() {
        assertTrue(stored.moviesMovedSince(null))
        assertTrue(stored.seriesMovedSince(null))
    }

    @Test
    fun `the disk form round-trips, and a partial one reads as half-unknown`() {
        assertEquals(stored, TraktWatchedStamps.parse(stored.wire()))

        val half = TraktWatchedStamps(movies = "2026-11-20T09:30:00.000Z", series = null)
        val parsed = TraktWatchedStamps.parse(half.wire())

        assertEquals(half, parsed)
        assertFalse("an empty side stays unknown, not an empty timestamp", parsed!!.isComplete)

        // Written by something else, or from a future format: no answer.
        assertEquals(null, TraktWatchedStamps.parse(null))
        assertEquals(null, TraktWatchedStamps.parse("nonsense"))
        assertEquals(null, TraktWatchedStamps.parse("a|b|c"))
    }
}
