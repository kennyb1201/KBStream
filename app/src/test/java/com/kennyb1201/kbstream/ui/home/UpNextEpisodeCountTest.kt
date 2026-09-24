package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero's "X of Y aired episodes watched" line.
 *
 * Reported bug: a show the user had started - paused part-way through season 1
 * episode 1 - was the only card whose hero showed no episode count, while every
 * other show showed one. The line needs BOTH a watched count and a total, and
 * the local-history builder stores the watched count with `takeIf { it > 0 }`,
 * so a title with nothing completed yet arrived as "total, no count" and the
 * line was dropped for exactly the case a viewer asks the question about.
 */
class UpNextEpisodeCountTest {

    private fun card(
        episodesWatched: Int? = null,
        episodesTotal: Int? = null,
        episodesRemaining: Int? = null
    ) = UpNextItem(
        id = "history:tt0903747:s1:e1",
        title = "Breaking Bad",
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = "tt0903747",
        parentType = "series",
        season = 1,
        episode = 1,
        episodesWatched = episodesWatched,
        episodesTotal = episodesTotal,
        episodesRemaining = episodesRemaining
    )

    @Test
    fun `a show just started reads zero of its total, not nothing`() {
        // The reported case: mid-episode 1, nothing completed, total known.
        assertEquals(
            0,
            card(episodesWatched = null, episodesTotal = 12).episodesWatchedForDisplay
        )

        // And the same when the builder hands over a literal zero.
        assertEquals(
            0,
            card(episodesWatched = 0, episodesTotal = 12).episodesWatchedForDisplay
        )
    }

    @Test
    fun `a count is passed through unchanged`() {
        assertEquals(
            5,
            card(episodesWatched = 5, episodesTotal = 12).episodesWatchedForDisplay
        )
        assertEquals(
            12,
            card(episodesWatched = 12, episodesTotal = 12).episodesWatchedForDisplay
        )
    }

    @Test
    fun `a stale tally cannot read higher than the total`() {
        // Watched on another device against a longer episode list, say: the
        // line must not claim "13 of 12".
        assertEquals(
            12,
            card(episodesWatched = 13, episodesTotal = 12).episodesWatchedForDisplay
        )
    }

    @Test
    fun `a movie or an unresolved episode list still gets no line`() {
        // Nothing truthful to say without a total, so the line stays hidden -
        // which is what keeps a movie's hero from claiming "0 of 0".
        assertNull(card(episodesWatched = null, episodesTotal = null).episodesWatchedForDisplay)
        assertNull(card(episodesWatched = 5, episodesTotal = null).episodesWatchedForDisplay)
        assertNull(card(episodesWatched = null, episodesTotal = 0).episodesWatchedForDisplay)
    }

    @Test
    fun `the hero's own condition now holds for a started show`() {
        // The hero renders only when watched != null && total != null && total > 0.
        val item = card(episodesWatched = null, episodesTotal = 12)
        val watched = item.episodesWatchedForDisplay
        val total = item.episodesTotal

        assertTrue(watched != null && total != null && total > 0)
        assertEquals("0 of 12 aired episodes watched", "$watched of $total aired episodes watched")
    }
}
