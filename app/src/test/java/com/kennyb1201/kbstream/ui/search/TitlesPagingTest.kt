package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Titles rail's paging rules ([TitlesPagingState]).
 *
 * The rail used to keep one page and stop: a broad query ("star" returned 24)
 * ended with no total and no way onward, and nothing on screen admitted it. The
 * four cases here are the fix's contract, and they are pure because the state
 * machine is: no ViewModel, no Android, no network.
 */
class TitlesPagingTest {

    private fun hit(
        id: Int,
        type: String = "movie",
        name: String = "Title $id"
    ) = SearchTitleResult(
        id = "tmdb:$id",
        type = type,
        name = name,
        poster = null,
        meta = MetaPreview(id = "tmdb:$id", type = type, name = name)
    )

    private fun page(
        number: Int,
        vararg hits: SearchTitleResult,
        totalPages: Int = 3,
        total: Int = 148
    ) = TitlesPage(
        number = number,
        merged = hits.toList(),
        totalPages = totalPages,
        total = total
    )

    @Test
    fun `page merge dedupes by TMDB id across pages`() {
        val state = TitlesPagingState()
            // Page 2 of a TMDB search routinely repeats a hit from page 1 when
            // the ranking shifts between requests.
            .absorb(page(1, hit(1), hit(2)))
            .absorb(page(2, hit(2), hit(3), hit(4)))

        assertEquals(
            "page 2's repeat of tmdb:2 must not be drawn twice",
            listOf("tmdb:1", "tmdb:2", "tmdb:3", "tmdb:4"),
            state.results.map { it.id }
        )
    }

    @Test
    fun `a movie and a series that share a TMDB id both stay`() {
        // TMDB numbers the two media independently: same number, different
        // title. Deduping on the bare number would silently drop one of them,
        // so the rail's key is the tile's identity, not the id alone.
        val state = TitlesPagingState()
            .absorb(page(1, hit(7, type = "movie"), hit(8, type = "movie")))
            .absorb(page(2, hit(7, type = "series")))

        assertEquals(3, state.results.size)
        assertEquals(
            listOf("movie:tmdb:7", "movie:tmdb:8", "series:tmdb:7"),
            state.results.map { "${it.type}:${it.id}" }
        )
    }

    @Test
    fun `total surfaces and loading stops at the last page`() {
        var state = TitlesPagingState()
        assertFalse("nothing is known - and nothing is askable - before page 1", state.hasMore)
        assertEquals(0, state.total)
        assertEquals(1, state.nextPage)

        state = state.absorb(page(1, hit(1), totalPages = 3, total = 148))
        assertEquals("the hero's 'of N' comes from the service's own count", 148, state.total)
        assertTrue(state.hasMore)
        assertEquals(2, state.nextPage)

        state = state.absorb(page(2, hit(2), totalPages = 3, total = 148))
        assertTrue(state.hasMore)
        assertEquals(3, state.nextPage)

        state = state.absorb(page(3, hit(3), totalPages = 3, total = 148))
        assertFalse("the last page ends the rail instead of asking again", state.hasMore)
        assertEquals(3, state.results.size)
    }

    @Test
    fun `a page-2 error keeps page 1 on screen and asks for the same page again`() {
        val firstPage = TitlesPagingState().absorb(page(1, hit(1), hit(2), totalPages = 3))

        val failed = firstPage.failed("Couldn't load more titles. Check the connection and try again.")

        assertEquals(
            "the failure must never clear what is already on the rail",
            listOf("tmdb:1", "tmdb:2"),
            failed.results.map { it.id }
        )
        assertEquals(
            "the message is the tail's, not the screen's 'no matches' card",
            "Couldn't load more titles. Check the connection and try again.",
            failed.error
        )
        assertEquals(
            "a failed page must not be counted as loaded, or the retry would " +
                "skip the results it never received",
            1,
            failed.loadedPage
        )
        assertTrue("there is still a page to retry", failed.hasMore)
        assertEquals(2, failed.nextPage)
    }

    @Test
    fun `a successful retry clears the error and appends`() {
        val state = TitlesPagingState()
            .absorb(page(1, hit(1), totalPages = 2))
            .failed("Couldn't load more titles.")
            .absorb(page(2, hit(2), totalPages = 2))

        assertNull(state.error)
        assertFalse(state.hasMore)
        assertEquals(listOf("tmdb:1", "tmdb:2"), state.results.map { it.id })
    }

    @Test
    fun `a new query resets to page 1`() {
        val finishedOldQuery = TitlesPagingState()
            .absorb(page(1, hit(1)))
            .absorb(page(2, hit(2)))
            .failed("Couldn't load more titles.")

        val fresh = TitlesPagingState()

        assertEquals(
            "the old query's cards must not survive into the new one",
            emptyList<String>(),
            fresh.results.map { it.id }
        )
        assertEquals(0, fresh.loadedPage)
        assertEquals(0, fresh.total)
        assertNull(fresh.error)
        assertFalse(fresh.hasMore)
        assertEquals("the new query starts at page 1", 1, fresh.nextPage)
        // And the abandoned state is still self-consistent, so nothing needs to
        // be mutated to start over.
        assertEquals(2, finishedOldQuery.results.size)
    }

    @Test
    fun `paging stops at the service's 500-page ceiling`() {
        // TMDB answers 400 past page 500, not an empty page - a rail that kept
        // asking would show the page error forever instead of ending.
        val state = TitlesPagingState().absorb(
            page(500, hit(1), totalPages = 10_000, total = 200_000)
        )
        assertFalse(state.hasMore)
    }

    @Test
    fun `a partial answer never shrinks the total it already knows`() {
        // Page 2 where only one of the two waves answered reports only that
        // wave's count; the rail keeps the larger number it had.
        val state = TitlesPagingState()
            .absorb(page(1, hit(1), totalPages = 4, total = 148))
            .absorb(page(2, hit(2), totalPages = 4, total = 20))

        assertEquals(148, state.total)
    }
}
