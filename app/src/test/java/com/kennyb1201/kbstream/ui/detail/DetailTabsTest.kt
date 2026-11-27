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
        overview: Boolean = false,
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
        hasOverviewRow = overview,
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
            overview = true,
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
        assertTrue("the overview is the first row of Details", details.hasOverviewRow)
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
        assertFalse("the overview is Details content only", episodes.hasOverviewRow)
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
            overview = true,
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
            "Details enters at the overview toggle when it is there",
            DetailRow.OVERVIEW,
            DetailTabChain.firstRow(DetailTab.DETAILS, page)
        )
        assertEquals(
            "without an overview, Details enters at its first chip rail",
            DetailRow.KEYWORDS,
            DetailTabChain.firstRow(DetailTab.DETAILS, rows(keywords = true))
        )
        assertEquals(DetailRow.PEOPLE, DetailTabChain.firstRow(DetailTab.CAST, page))
        assertEquals(
            DetailRow.COLLECTION,
            DetailTabChain.firstRow(DetailTab.MORE_LIKE_THIS, page)
        )
        assertEquals(DetailRow.REVIEWS, DetailTabChain.firstRow(DetailTab.REVIEWS, page))
        assertEquals(DetailRow.MEDIA, DetailTabChain.firstRow(DetailTab.MEDIA, page))

        // Details with no overview toggle and no chip rows: nothing in the
        // chain below the strip, so the screen falls back to the geometric
        // search (the first fact or rating card) rather than pressing into
        // nothing.
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
    fun `the strip is decided by the title before any payload arrives`() {
        // Rule 3: a series has Episodes, everything else does not, and every
        // tab a title CAN have is painted from the first frame. Nothing here
        // reads the loaded rows, so no pill can pop in or out mid-load.
        assertEquals(DetailTab.entries, DetailTabChain.stableTabs(isSeries = true))
        assertEquals(
            "the strip paints Details first and Episodes second",
            listOf(DetailTab.DETAILS, DetailTab.EPISODES, DetailTab.CAST),
            DetailTabChain.stableTabs(isSeries = true).take(3)
        )
        assertEquals(
            listOf(
                DetailTab.DETAILS,
                DetailTab.CAST,
                DetailTab.MORE_LIKE_THIS,
                DetailTab.REVIEWS,
                DetailTab.MEDIA
            ),
            DetailTabChain.stableTabs(isSeries = false)
        )
        assertEquals(DetailTab.EPISODES, DetailTabChain.stableDefaultTab(isSeries = true))
        assertEquals(DetailTab.DETAILS, DetailTabChain.stableDefaultTab(isSeries = false))
    }

    @Test
    fun `an unstarted series opens on Details, a watched one on Episodes`() {
        // F6 (spec 2026-11-27): the default tab follows the watch history once
        // it is known. A movie is Details either way, which is what makes the
        // rule safe to apply after the first frame.
        assertEquals(
            "a series with no history opens on Details, not on an episode list " +
                "for a show the viewer has not started",
            DetailTab.DETAILS,
            DetailTabChain.historyDefaultTab(isSeries = true, hasHistory = false)
        )
        assertEquals(
            "a series with a watched episode or a resume point still opens on Episodes",
            DetailTab.EPISODES,
            DetailTabChain.historyDefaultTab(isSeries = true, hasHistory = true)
        )
        assertEquals(
            "a movie opens on Details",
            DetailTab.DETAILS,
            DetailTabChain.historyDefaultTab(isSeries = false, hasHistory = false)
        )
        assertEquals(
            "and stays on Details even with history - it has no Episodes pill",
            DetailTab.DETAILS,
            DetailTabChain.historyDefaultTab(isSeries = false, hasHistory = true)
        )
    }

    @Test
    fun `history presence is unknown until nothing watched can be claimed`() {
        // F6 (spec 2026-11-27): the tab default must not act on a half-read
        // state. One mark of history is enough to say SOME; NONE waits for the
        // local read AND every configured tracker leg to have answered.
        assertEquals(
            DetailHistoryPresence.SOME,
            detailHistoryPresence(
                hasHistory = true,
                localRead = false,
                trackersComplete = false
            )
        )
        assertEquals(
            "a tracker that has not answered is unknown, not empty",
            DetailHistoryPresence.UNKNOWN,
            detailHistoryPresence(
                hasHistory = false,
                localRead = true,
                trackersComplete = false
            )
        )
        assertEquals(
            "a local read still in flight is unknown too",
            DetailHistoryPresence.UNKNOWN,
            detailHistoryPresence(
                hasHistory = false,
                localRead = false,
                trackersComplete = true
            )
        )
        assertEquals(
            DetailHistoryPresence.NONE,
            detailHistoryPresence(
                hasHistory = false,
                localRead = true,
                trackersComplete = true
            )
        )
    }

    @Test
    fun `the history default still names only tabs the strip paints`() {
        // The strip is unchanged (rule 3): the Episodes pill exists for every
        // series, whether or not the page opens on it.
        listOf(true, false).forEach { hasHistory ->
            assertTrue(
                "series, hasHistory=$hasHistory",
                DetailTabChain.historyDefaultTab(isSeries = true, hasHistory = hasHistory) in
                    DetailTabChain.stableTabs(isSeries = true)
            )
            assertTrue(
                "movie, hasHistory=$hasHistory",
                DetailTabChain.historyDefaultTab(isSeries = false, hasHistory = hasHistory) in
                    DetailTabChain.stableTabs(isSeries = false)
            )
        }
        assertTrue(
            "and the Episodes pill is on every series strip",
            DetailTab.EPISODES in DetailTabChain.stableTabs(isSeries = true)
        )
    }

    @Test
    fun `stepping the stable strip never names a tab it does not paint`() {
        val movie = DetailTabChain.stableTabs(isSeries = false)
        assertEquals(
            DetailTab.MEDIA,
            DetailTabChain.step(DetailTab.DETAILS, 5, movie)
        )
        assertEquals(
            DetailTab.DETAILS,
            DetailTabChain.step(DetailTab.DETAILS, -1, movie)
        )
        assertEquals(
            DetailTab.MEDIA,
            DetailTabChain.step(DetailTab.CAST, 3, movie)
        )
    }

    @Test
    fun `a stable tab with nothing in it says so instead of painting blank`() {
        assertEquals(
            "a tab whose section is still loading names the wait",
            "Loading episodes…",
            DetailTabChain.placeholder(DetailTab.EPISODES, loading = true)
        )
        assertEquals(
            "a tab whose section is genuinely absent says so",
            "No reviews for this title.",
            DetailTabChain.placeholder(DetailTab.REVIEWS, loading = false)
        )
        assertNull(
            "Details has no sentence: its leaf rows and the content sink are its " +
                "empty state (acceptance 6)",
            DetailTabChain.placeholder(DetailTab.DETAILS, loading = false)
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
