package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MDBList next-episode leg's own dedupe (Slice A extension, X3).
 *
 * MDBList's watched index keeps the tracker's two id forms apart, so one show can
 * be enumerated as `tt1234567` and again as `tmdb:456`. Both resolve to the same
 * TMDB show. The merge funnel would collapse them anyway (shared `tmdbId` key
 * plus title key), but the leg must not spend two rail slots on one show while it
 * can, and the surviving card's identity has to be a rule rather than whichever
 * twin the cluster winner happened to pick.
 *
 * Pure, and tested through the real function: the harness extracts it verbatim
 * with a minimal [UpNextItem] stub, so this is the shipped rule, not a copy.
 */
class MdbListNextUpCardsTest {

    private fun card(parentId: String, tmdbId: Int?, id: String = "mdblist:next:$parentId") =
        UpNextItem(
            id = id,
            title = "Landman",
            poster = null,
            badge = UpNextBadge.NEXT_UP,
            parentId = parentId,
            tmdbId = tmdbId
        )

    @Test
    fun `the two id forms of one show collapse to the imdb card`() {
        val imdb = card("tt1234567", tmdbId = 456)
        val tmdb = card("tmdb:456", tmdbId = 456)

        val kept = dedupeTrackerNextEpisodeCards(listOf(tmdb, imdb))

        assertEquals(1, kept.size)
        assertSame(
            "IMDb-first is the app's identity rule, and it must not depend on " +
                "which form the index happened to enumerate first",
            imdb,
            kept.single()
        )
    }

    @Test
    fun `the imdb card wins whichever order it arrives in`() {
        val imdb = card("tt1234567", tmdbId = 456)
        val tmdb = card("tmdb:456", tmdbId = 456)

        assertSame(imdb, dedupeTrackerNextEpisodeCards(listOf(imdb, tmdb)).single())
        assertSame(imdb, dedupeTrackerNextEpisodeCards(listOf(tmdb, imdb)).single())
    }

    @Test
    fun `a tmdb-only show keeps its card`() {
        val only = card("tmdb:456", tmdbId = 456)

        assertSame(only, dedupeTrackerNextEpisodeCards(listOf(only)).single())
    }

    @Test
    fun `two shows are two cards, in the order they were built`() {
        val first = card("tt1", tmdbId = 1)
        val second = card("tt2", tmdbId = 2)

        assertEquals(listOf(first, second), dedupeTrackerNextEpisodeCards(listOf(first, second)))
    }

    @Test
    fun `cards with no tmdb id cannot be paired with anything`() {
        val a = card("tt1", tmdbId = null)
        val b = card("tmdb:2", tmdbId = null)

        assertEquals(
            "nothing says these are the same show, so neither may be dropped",
            listOf(a, b),
            dedupeTrackerNextEpisodeCards(listOf(a, b))
        )
    }

    @Test
    fun `a mixed list keeps every show once`() {
        val imdbA = card("tt1", tmdbId = 1)
        val tmdbA = card("tmdb:1", tmdbId = 1)
        val b = card("tt2", tmdbId = 2)
        val c = card("tmdb:3", tmdbId = 3)

        val kept = dedupeTrackerNextEpisodeCards(listOf(tmdbA, b, imdbA, c))

        assertEquals(3, kept.size)
        assertTrue("A survives as its IMDb card", imdbA in kept)
        assertTrue("B and C are untouched", b in kept && c in kept)
        assertTrue("A's TMDB twin is gone", tmdbA !in kept)
    }

    @Test
    fun `a single card is returned as it is`() {
        val only = card("tt1", tmdbId = 1)

        assertSame(only, dedupeTrackerNextEpisodeCards(listOf(only)).single())
        assertEquals(emptyList<UpNextItem>(), dedupeTrackerNextEpisodeCards(emptyList()))
    }

    @Test
    fun `a tmdb id of zero is treated as no id`() {
        val a = card("tt1", tmdbId = 0)
        val b = card("tmdb:1", tmdbId = 0)

        assertEquals(
            "0 is the builder's \"the lookup resolved no id\", not a show identity",
            2,
            dedupeTrackerNextEpisodeCards(listOf(a, b)).size
        )
    }
}
