package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab-level rules of the 2026-11-21 Detail revamp.
 *
 * The page paints today's items behind six named tabs, and the whole point is
 * that the tab set, the default tab and the rows a tab owns are decided HERE,
 * once, rather than by six call sites agreeing with each other. These are the
 * pure answers the strip, the chain and the list all read; the screen's own
 * wiring is pinned by the source contract tests.
 */
class DetailTabsTest {

    private fun rows(
        genre: Boolean = false,
        keywords: Boolean = false,
        episodes: Boolean = false,
        people: Boolean = false,
        network: Boolean = false,
        production: Boolean = false,
        reviews: Boolean = false,
        collection: Boolean = false,
        media: Boolean = false,
        moreLikeThis: Boolean = false
    ) = DetailRowPresence(
        hasGenreRow = genre,
        hasKeywordsRow = keywords,
        hasEpisodesRow = episodes,
        hasPeopleRow = people,
        hasNetworkRow = network,
        hasProductionRow = production,
        hasReviewsRow = reviews,
        hasCollectionRow = collection,
        hasMediaRow = media,
        hasMoreLikeThisRow = moreLikeThis
    )

    private fun presence(
        episodes: Boolean = false,
        people: Boolean = false,
        reviews: Boolean = false,
        collection: Boolean = false,
        media: Boolean = false,
        moreLikeThis: Boolean = false
    ) = DetailTabPresence(
        hasEpisodesTab = episodes,
        hasCastTab = people,
        hasMoreLikeThisTab = collection || moreLikeThis,
        hasReviewsTab = reviews,
        hasMediaTab = media
    )

    @Test
    fun `a tab exists exactly when the rows inside it do`() {
        val everything = presence(
            episodes = true,
            people = true,
            reviews = true,
            collection = true,
            media = true
        )
        assertEquals(DetailTab.entries, DetailTabChain.tabs(everything))

        // A movie: no episodes. No reviews, no cast, no media: none of their
        // tabs. Details is the one tab that is always there - the overview,
        // the facts and the ratings are the page's description of the title.
        val movie = presence()
        assertEquals(listOf(DetailTab.DETAILS), DetailTabChain.tabs(movie))
        assertFalse(DetailTabChain.exists(DetailTab.EPISODES, movie))
        assertTrue(DetailTabChain.exists(DetailTab.DETAILS, movie))
    }

    @Test
    fun `collection and recommendations share one tab, and either one is enough`() {
        assertTrue(
            DetailTabChain.exists(
                DetailTab.MORE_LIKE_THIS,
                DetailTabPresence.of(rows(collection = true))
            )
        )
        assertTrue(
            DetailTabChain.exists(
                DetailTab.MORE_LIKE_THIS,
                DetailTabPresence.of(rows(moreLikeThis = true))
            )
        )
        assertFalse(
            DetailTabChain.exists(
                DetailTab.MORE_LIKE_THIS,
                DetailTabPresence.of(rows())
            )
        )
    }

    @Test
    fun `the presence is read from the rows the page already publishes`() {
        // The same fields that decide whether a section renders decide whether
        // its tab exists, so the two cannot drift.
        val withEpisodes = DetailTabPresence.of(rows(episodes = true))
        assertTrue(withEpisodes.hasEpisodesTab)
        assertFalse(withEpisodes.hasCastTab)
        assertFalse(withEpisodes.hasReviewsTab)

        val withEverything = DetailTabPresence.of(
            rows(
                episodes = true,
                people = true,
                reviews = true,
                collection = true,
                media = true
            )
        )
        assertTrue(withEverything.hasEpisodesTab)
        assertTrue(withEverything.hasCastTab)
        assertTrue(withEverything.hasReviewsTab)
        assertTrue(withEverything.hasMoreLikeThisTab)
        assertTrue(withEverything.hasMediaTab)
    }

    @Test
    fun `a series opens on Episodes and everything else on Details`() {
        val series = presence(episodes = true)
        assertEquals(DetailTab.EPISODES, DetailTabChain.defaultTab(true, series))
        assertEquals(DetailTab.DETAILS, DetailTabChain.defaultTab(false, series))
        // A series whose seasons have not resolved yet - the tab does not
        // exist, so the default cannot name it.
        assertEquals(DetailTab.DETAILS, DetailTabChain.defaultTab(true, presence()))
    }

    @Test
    fun `left and right clamp at the ends and skip tabs that are not there`() {
        val movie = presence(media = true)
        val tabs = DetailTabChain.tabs(movie)
        assertEquals(listOf(DetailTab.DETAILS, DetailTab.MEDIA), tabs)
        assertEquals(DetailTab.MEDIA, DetailTabChain.step(DetailTab.DETAILS, 1, movie))
        assertEquals(
            "the last tab does not wrap to the first",
            DetailTab.MEDIA,
            DetailTabChain.step(DetailTab.MEDIA, 1, movie)
        )
        assertEquals(
            "nor does the first wrap to the last",
            DetailTab.DETAILS,
            DetailTabChain.step(DetailTab.DETAILS, -1, movie)
        )
        assertEquals(
            "and an absent tab between two is stepped over, not landed on",
            DetailTab.MEDIA,
            DetailTabChain.step(DetailTab.DETAILS, 1, presence(media = true))
        )
    }

    @Test
    fun `a tab owns only its own rows, and the strip exists on every page`() {
        val page = rows(
            genre = true,
            keywords = true,
            episodes = true,
            people = true,
            network = true,
            production = true,
            reviews = true,
            collection = true,
            media = true,
            moreLikeThis = true
        )

        val details = DetailTabChain.rowPresence(DetailTab.DETAILS, page)
        assertTrue(details.hasKeywordsRow)
        assertTrue(details.hasNetworkRow)
        assertTrue(details.hasProductionRow)
        assertFalse("the episode row lives in its own tab", details.hasEpisodesRow)
        assertFalse(details.hasPeopleRow)
        assertFalse(details.hasReviewsRow)
        assertFalse(details.hasCollectionRow)
        assertFalse(details.hasMediaRow)
        // The chips are painted in the fixed header and the strip above every
        // tab, so both survive the narrowing.
        assertTrue(DetailFocusChain.exists(DetailRow.GENRE, details))
        assertTrue(DetailFocusChain.exists(DetailRow.TABS, details))

        val episodes = DetailTabChain.rowPresence(DetailTab.EPISODES, page)
        assertTrue(episodes.hasEpisodesRow)
        assertFalse(episodes.hasKeywordsRow)
        assertFalse(episodes.hasPeopleRow)

        val cast = DetailTabChain.rowPresence(DetailTab.CAST, page)
        assertTrue(cast.hasPeopleRow)
        assertFalse(cast.hasEpisodesRow)
        assertFalse(cast.hasReviewsRow)

        val more = DetailTabChain.rowPresence(DetailTab.MORE_LIKE_THIS, page)
        assertTrue(more.hasCollectionRow)
        assertTrue(more.hasMoreLikeThisRow)
        assertFalse(more.hasReviewsRow)

        val reviews = DetailTabChain.rowPresence(DetailTab.REVIEWS, page)
        assertTrue(reviews.hasReviewsRow)
        assertFalse(reviews.hasMediaRow)

        val media = DetailTabChain.rowPresence(DetailTab.MEDIA, page)
        assertTrue(media.hasMediaRow)
        assertFalse(media.hasReviewsRow)
    }

    @Test
    fun `each tab's first row is the row under the strip`() {
        val page = rows(
            keywords = true,
            episodes = true,
            people = true,
            production = true,
            collection = true,
            reviews = true,
            media = true
        )
        assertEquals(
            DetailRow.EPISODES,
            DetailTabChain.firstRow(DetailTab.EPISODES, page)
        )
        assertEquals(
            "Details enters at its first chip rail",
            DetailRow.KEYWORDS,
            DetailTabChain.firstRow(DetailTab.DETAILS, page)
        )
        assertEquals(DetailRow.PEOPLE, DetailTabChain.firstRow(DetailTab.CAST, page))
        assertEquals(
            DetailRow.COLLECTION,
            DetailTabChain.firstRow(DetailTab.MORE_LIKE_THIS, page)
        )
        assertEquals(DetailRow.REVIEWS, DetailTabChain.firstRow(DetailTab.REVIEWS, page))
        assertEquals(DetailRow.MEDIA, DetailTabChain.firstRow(DetailTab.MEDIA, page))

        // Details with no chip rows at all: nothing in the chain below the
        // strip, so the screen falls back to the geometric search (the overview
        // toggle or the first fact card) rather than pressing into nothing.
        assertNull(DetailTabChain.firstRow(DetailTab.DETAILS, rows()))
    }

    @Test
    fun `a tab narrowed to one row has that row as both ends`() {
        val media = DetailTabChain.rowPresence(DetailTab.MEDIA, rows(media = true))
        assertEquals(
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, media)
        )
        assertNull(DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, media))
        assertTrue(
            "the bottom of a tab is the bottom of the page: the press stays put",
            DetailFocusChain.isPageEdge(DetailRow.MEDIA, DetailFocusDirection.DOWN, media)
        )
    }

    @Test
    fun `the tab a viewer left a title on is remembered per title`() {
        DetailTabMemory.record("series:1", DetailTab.CAST)
        DetailTabMemory.record("movie:2", DetailTab.REVIEWS)
        assertEquals(DetailTab.CAST, DetailTabMemory.read("series:1"))
        assertEquals(DetailTab.REVIEWS, DetailTabMemory.read("movie:2"))
        assertNull(
            "a title nobody has visited has no remembered tab",
            DetailTabMemory.read("series:never-opened")
        )
        DetailTabMemory.record("series:1", DetailTab.MEDIA)
        assertEquals(
            "the latest visit wins",
            DetailTab.MEDIA,
            DetailTabMemory.read("series:1")
        )
    }
}
