package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail page's vertical focus chain: which row a UP or DOWN press leaves
 * for.
 *
 * Reported problem: focus was stranded when moving between rows, and each fix
 * had patched one transition (network UP, then production UP) while the
 * stranding moved to the next, because there was no single answer to "what is
 * below this row". The chain is that single answer - one ordered list, read the
 * same way by every rail - so what is pinned here is the rule itself, which is
 * also what makes every rail's UP and DOWN agree by construction.
 *
 * The order is the PAINTED order (header, genre chips, tags, episodes, people,
 * networks, production, reviews, collection, More Like This, Media), not the
 * order the rails are written in the source, because a chain that did not
 * mirror the screen would send UP into a row the viewer can see is below them.
 */
class DetailFocusChainTest {

    /** The rows a page has, by the name used in each case below. */
    private fun presence(
        genre: Boolean = false,
        // The overview's "View more" toggle, painted between the chips and the
        // strip since 2026-11-21. Off by default, which is the page whose text
        // fits its four collapsed lines.
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

    @Test
    fun `the header is the one row that always exists`() {
        val empty = presence()
        assertTrue(
            "the header lives above the list, so it is composed whatever the " +
                "payload is - it is what makes the chain incapable of stranding " +
                "a press",
            DetailFocusChain.exists(DetailRow.HEADER, empty)
        )
        assertTrue(
            "the tab strip is painted outside the list as well, so it exists on a " +
                "page with nothing loaded: it is the row a press can always land on",
            DetailFocusChain.exists(DetailRow.TABS, empty)
        )
        DetailRow.entries
            .filter { it != DetailRow.HEADER && it != DetailRow.TABS }
            .forEach { row ->
                assertFalse(
                    "a page with nothing on it has no $row",
                    DetailFocusChain.exists(row, empty)
                )
            }
    }

    @Test
    fun `a row exists exactly when the page paints it`() {
        val all = presence(
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
        DetailRow.entries.forEach { row ->
            assertTrue("$row is on this page", DetailFocusChain.exists(row, all))
        }

        val seriesOnly = presence(network = true)
        assertTrue(DetailFocusChain.exists(DetailRow.NETWORK, seriesOnly))
        assertFalse(DetailFocusChain.exists(DetailRow.PRODUCTION, seriesOnly))
        assertFalse(DetailFocusChain.exists(DetailRow.PEOPLE, seriesOnly))
    }

    @Test
    fun `up out of the top row leaves the page`() {
        val all = presence(
            keywords = true,
            episodes = true,
            people = true,
            network = true,
            production = true
        )
        assertNull(
            "nothing is above the header, so the chain has no target and the " +
                "press belongs to the default search",
            DetailFocusChain.nextRow(DetailRow.HEADER, DetailFocusDirection.UP, all)
        )
    }

    @Test
    fun `down out of the bottom row leaves the chain`() {
        // A page with a collection but no recommendations: the collection is
        // then the last row the chain names.
        val collectionLast = presence(people = true, production = true, collection = true)
        assertNull(
            "the collection rail is the bottom of the chain when no More Like " +
                "This is painted - the review rail, the Media row and the " +
                "bottom spacer below it are not rows this page has",
            DetailFocusChain.nextRow(
                DetailRow.COLLECTION,
                DetailFocusDirection.DOWN,
                collectionLast
            )
        )

        val recsLast = presence(production = true, collection = true, moreLikeThis = true)
        assertNull(
            "and More Like This is the bottom when the title has no videos or " +
                "stills to show",
            DetailFocusChain.nextRow(
                DetailRow.MORE_LIKE_THIS,
                DetailFocusDirection.DOWN,
                recsLast
            )
        )

        val mediaLast = presence(
            production = true,
            collection = true,
            moreLikeThis = true,
            media = true
        )
        assertEquals(
            "the Media row is painted LAST of all, so More Like This hands DOWN " +
                "to it rather than off the page",
            DetailRow.MEDIA,
            DetailFocusChain.nextRow(
                DetailRow.MORE_LIKE_THIS,
                DetailFocusDirection.DOWN,
                mediaLast
            )
        )
        assertNull(
            "and DOWN out of IT is the end of the chain: the press belongs to " +
                "the header PLAY",
            DetailFocusChain.nextRow(
                DetailRow.MEDIA,
                DetailFocusDirection.DOWN,
                mediaLast
            )
        )
    }

    @Test
    fun `the collection and recommendations rails sit below the production companies`() {
        val movie = presence(
            people = true,
            production = true,
            collection = true,
            moreLikeThis = true
        )
        assertEquals(
            "DOWN out of the production chips reaches the collection rail",
            DetailRow.COLLECTION,
            DetailFocusChain.nextRow(DetailRow.PRODUCTION, DetailFocusDirection.DOWN, movie)
        )
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.COLLECTION, DetailFocusDirection.DOWN, movie)
        )
        assertEquals(
            "and UP walks back the same way",
            DetailRow.COLLECTION,
            DetailFocusChain.nextRow(DetailRow.MORE_LIKE_THIS, DetailFocusDirection.UP, movie)
        )
        assertEquals(
            DetailRow.PRODUCTION,
            DetailFocusChain.nextRow(DetailRow.COLLECTION, DetailFocusDirection.UP, movie)
        )
    }

    @Test
    fun `the Media row is the last row, under the reviews`() {
        // The row is one entry of the chain however many rails it holds inside
        // itself, which is the whole point of naming it here: DOWN out of it is
        // the bottom of the page, and only the row's own tab strip knows there
        // are two pages in between. This page has no reviews, so the row above
        // the Media row is the recommendations rail - the step onto the review
        // rail when there is one is the test above's.
        val movie = presence(production = true, collection = true, media = true, moreLikeThis = true)
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.COLLECTION, DetailFocusDirection.DOWN, movie)
        )
        assertEquals(
            DetailRow.MEDIA,
            DetailFocusChain.nextRow(DetailRow.MORE_LIKE_THIS, DetailFocusDirection.DOWN, movie)
        )
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, movie)
        )
        assertEquals(
            DetailRow.COLLECTION,
            DetailFocusChain.nextRow(DetailRow.MORE_LIKE_THIS, DetailFocusDirection.UP, movie)
        )
        assertNull(
            "nothing is below the Media row, so its DOWN is the header's",
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, movie)
        )
    }

    @Test
    fun `a title with only a Media row is still reachable from both sides`() {
        // No collection and no cast: the row's neighbour above is the
        // recommendations rail, and below it there is nothing at all.
        val mediaOnly = presence(production = true, media = true, moreLikeThis = true)
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, mediaOnly)
        )
        assertNull(
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, mediaOnly)
        )
        // A title with nothing but videos and stills: the row is the last one
        // the chain names, and UP still lands on the header rather than
        // stranding the press.
        val only = presence(media = true)
        assertEquals(
            "UP steps over every absent rail to the strip, which is painted above " +
                "them all",
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, only)
        )
        assertNull(DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, only))
    }

    @Test
    fun `a page with no collection or recommendations steps over both`() {
        val movie = presence(people = true, production = true)
        assertNull(
            "with neither rail painted, DOWN out of the production chips is the " +
                "end of the chain",
            DetailFocusChain.nextRow(DetailRow.PRODUCTION, DetailFocusDirection.DOWN, movie)
        )
    }

    @Test
    fun `the nearest existing row in the direction wins`() {
        // A series with all four rails present.
        val series = presence(
            keywords = true,
            episodes = true,
            people = true,
            network = true,
            production = true
        )
        assertEquals(
            DetailRow.PEOPLE,
            DetailFocusChain.nextRow(DetailRow.NETWORK, DetailFocusDirection.UP, series)
        )
        assertEquals(
            DetailRow.PRODUCTION,
            DetailFocusChain.nextRow(DetailRow.NETWORK, DetailFocusDirection.DOWN, series)
        )
        assertEquals(
            DetailRow.NETWORK,
            DetailFocusChain.nextRow(DetailRow.PRODUCTION, DetailFocusDirection.UP, series)
        )
        assertEquals(
            DetailRow.EPISODES,
            DetailFocusChain.nextRow(DetailRow.PEOPLE, DetailFocusDirection.UP, series)
        )
        assertEquals(
            DetailRow.PEOPLE,
            DetailFocusChain.nextRow(DetailRow.EPISODES, DetailFocusDirection.DOWN, series)
        )
    }

    @Test
    fun `a row the page does not have is stepped over, never landed on`() {
        // A series with no cast at all: the network rail's neighbours are the
        // episode row above and the production row below.
        val noCast = presence(episodes = true, network = true, production = true)
        assertEquals(
            DetailRow.EPISODES,
            DetailFocusChain.nextRow(DetailRow.NETWORK, DetailFocusDirection.UP, noCast)
        )
        assertEquals(
            DetailRow.NETWORK,
            DetailFocusChain.nextRow(DetailRow.EPISODES, DetailFocusDirection.DOWN, noCast)
        )

        // A movie: no network rail, so a people card steps DOWN to the
        // production companies.
        val movie = presence(people = true, production = true)
        assertEquals(
            DetailRow.PRODUCTION,
            DetailFocusChain.nextRow(DetailRow.PEOPLE, DetailFocusDirection.DOWN, movie)
        )
    }

    @Test
    fun `the genre chips are a row of their own above the tag rail`() {
        val page = presence(genre = true, keywords = true)
        assertEquals(
            "UP out of the tag rail reaches the strip painted between the chips " +
                "and the rows - the page's third stop",
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.KEYWORDS, DetailFocusDirection.UP, page)
        )
        assertEquals(
            "and DOWN from the chips drops into the strip, not into a rail",
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.GENRE, DetailFocusDirection.DOWN, page)
        )
        assertEquals(
            "UP out of the chips is the header, which always exists",
            DetailRow.HEADER,
            DetailFocusChain.nextRow(DetailRow.GENRE, DetailFocusDirection.UP, page)
        )
        assertEquals(
            "with no tag rail the chips step DOWN to the strip, which is painted " +
                "between them and every row a tab holds",
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(genre = true, episodes = true)
            )
        )
    }

    @Test
    fun `the review rail is a row of its own between More Like This and the Media row`() {
        val page = presence(moreLikeThis = true, reviews = true, media = true)
        assertEquals(
            "DOWN out of the recommendations reaches the review rail painted " +
                "under it",
            DetailRow.REVIEWS,
            DetailFocusChain.nextRow(DetailRow.MORE_LIKE_THIS, DetailFocusDirection.DOWN, page)
        )
        assertEquals(
            DetailRow.MEDIA,
            DetailFocusChain.nextRow(DetailRow.REVIEWS, DetailFocusDirection.DOWN, page)
        )
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.REVIEWS, DetailFocusDirection.UP, page)
        )
        assertEquals(
            DetailRow.REVIEWS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, page)
        )
        assertEquals(
            "and the collection stays ABOVE it - the reviews are an addendum " +
                "painted under the rails that lead to the next title",
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(
                DetailRow.COLLECTION,
                DetailFocusDirection.DOWN,
                presence(collection = true, moreLikeThis = true, reviews = true)
            )
        )
        assertEquals(
            "a title with no reviews steps straight from More Like This to the " +
                "Media row, exactly as before the rail moved",
            DetailRow.MEDIA,
            DetailFocusChain.nextRow(
                DetailRow.MORE_LIKE_THIS,
                DetailFocusDirection.DOWN,
                presence(moreLikeThis = true, media = true)
            )
        )
    }

    @Test
    fun `up out of a network chip with nothing above it ends on the header`() {
        // The trap this chain exists to make impossible: networks, and nothing
        // else on the page above them.
        val networksOnly = presence(network = true)
        assertEquals(
            "an item with networks and no cast must not be left with nowhere " +
                "to go on UP: the strip is above every list row",
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.NETWORK, DetailFocusDirection.UP, networksOnly)
        )
    }

    @Test
    fun `down out of the tag rail reaches the episodes on a series and the people on a movie`() {
        val series = presence(keywords = true, episodes = true, people = true)
        assertEquals(
            DetailRow.EPISODES,
            DetailFocusChain.nextRow(DetailRow.KEYWORDS, DetailFocusDirection.DOWN, series)
        )

        val movie = presence(keywords = true, people = true)
        assertEquals(
            DetailRow.PEOPLE,
            DetailFocusChain.nextRow(DetailRow.KEYWORDS, DetailFocusDirection.DOWN, movie)
        )
    }

    @Test
    fun `up out of the episode row reaches the tag rail above it`() {
        val withTags = presence(keywords = true, episodes = true)
        assertEquals(
            DetailRow.KEYWORDS,
            DetailFocusChain.nextRow(DetailRow.EPISODES, DetailFocusDirection.UP, withTags)
        )

        val noTags = presence(episodes = true)
        assertEquals(
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.EPISODES, DetailFocusDirection.UP, noTags)
        )
    }

    @Test
    fun `up from any row lands on a row that exists, and on the header at worst`() {
        DetailRow.entries.forEach { row ->
            // A page whose only row is this one.
            val only = presence(
                genre = row == DetailRow.GENRE,
                overview = row == DetailRow.OVERVIEW,
                keywords = row == DetailRow.KEYWORDS,
                episodes = row == DetailRow.EPISODES,
                people = row == DetailRow.PEOPLE,
                network = row == DetailRow.NETWORK,
                production = row == DetailRow.PRODUCTION,
                reviews = row == DetailRow.REVIEWS,
                collection = row == DetailRow.COLLECTION,
                media = row == DetailRow.MEDIA,
                moreLikeThis = row == DetailRow.MORE_LIKE_THIS
            )
            val expected = when (row) {
                DetailRow.HEADER -> null
                // The chips and the overview toggle are painted between the
                // header and the strip, and the strip itself has only the
                // header above it when the page has neither.
                DetailRow.GENRE, DetailRow.OVERVIEW, DetailRow.TABS -> DetailRow.HEADER
                // Every list row's UP is the strip above it.
                else -> DetailRow.TABS
            }
            assertEquals(
                "UP out of $row lands on the strip or the header above it - never " +
                    "on nothing",
                expected,
                DetailFocusChain.nextRow(row, DetailFocusDirection.UP, only)
            )
        }
    }

    @Test
    fun `down from every row is a row that exists or the end of the chain`() {
        val page = presence(
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
        DetailRow.entries.forEach { row ->
            val target = DetailFocusChain.nextRow(row, DetailFocusDirection.DOWN, page)
            if (row == DetailRow.MEDIA) {
                assertNull(
                    "the Media row is painted last, so it is the bottom row and " +
                        "has nothing below it in the chain",
                    target
                )
            } else {
                assertTrue(
                    "DOWN out of $row must land on a row this page has",
                    target != null && DetailFocusChain.exists(target, page)
                )
            }
        }
    }

    @Test
    fun `only a real edge of the page may swallow a press that cannot move`() {
        // The 2026-11 field report: a vertical press off a genre, season or
        // episode chip did nothing at all on titles whose rows in that direction
        // were still composing. The walk asked every row, ran out, and the page
        // consumed the key - so it is the chain's own job to say whether the
        // press was at an edge (where staying put is right) or mid-page (where
        // it must not be).
        val midPage = presence(
            genre = true,
            keywords = true,
            episodes = true,
            people = true,
            media = true
        )
        assertFalse(
            "DOWN off a chip that has rows below it is mid-page, however the " +
                "walk ended: the rows below are there, they just would not take " +
                "the focus yet",
            DetailFocusChain.isPageEdge(DetailRow.GENRE, DetailFocusDirection.DOWN, midPage)
        )
        assertFalse(
            "and the same press on a title with a single row below it",
            DetailFocusChain.isPageEdge(DetailRow.KEYWORDS, DetailFocusDirection.DOWN, midPage)
        )
        assertTrue(
            "DOWN out of the LAST row the page has is the bottom edge, and that " +
                "press stays where it is",
            DetailFocusChain.isPageEdge(DetailRow.MEDIA, DetailFocusDirection.DOWN, midPage)
        )
        assertTrue(
            "which is the last row THIS page has, not the last row the chain has: " +
                "a movie with no Media row ends on Whatever is painted last",
            DetailFocusChain.isPageEdge(
                DetailRow.PEOPLE,
                DetailFocusDirection.DOWN,
                presence(genre = true, people = true)
            )
        )
        assertTrue(
            "UP out of the fixed header row is the top edge: there is nothing " +
                "above the page, and that press has always stayed put",
            DetailFocusChain.isPageEdge(DetailRow.HEADER, DetailFocusDirection.UP, midPage)
        )
        assertTrue(
            "UP out of the genre chips is the top edge as well: they are painted " +
                "in the fixed header directly under the action row, so the only " +
                "thing above them is the row that just failed - and a search " +
                "from there leaves the page instead of moving in it",
            DetailFocusChain.isPageEdge(DetailRow.GENRE, DetailFocusDirection.UP, midPage)
        )
        assertFalse(
            "but UP out of any row inside the list is mid-page, even when the " +
                "header is all that is above it: the press still has somewhere to go",
            DetailFocusChain.isPageEdge(DetailRow.KEYWORDS, DetailFocusDirection.UP, midPage)
        )
        assertFalse(
            "and DOWN out of the header is never the bottom edge any more: the " +
                "strip is painted below it whatever the payload is",
            DetailFocusChain.isPageEdge(
                DetailRow.HEADER,
                DetailFocusDirection.DOWN,
                presence()
            )
        )
    }

    // ── The overview toggle's DOWN (the reported trap) ────────────────────

    @Test
    fun `down out of the header reaches the strip, and the toggle reaches its first row`() {
        // The header's DOWN is the chips when the page has them, and the strip
        // when it does not - the strip is painted under both, so the press can
        // never run out of page.
        assertEquals(
            DetailRow.GENRE,
            DetailFocusChain.nextRow(
                DetailRow.HEADER,
                DetailFocusDirection.DOWN,
                presence(genre = true, episodes = true)
            )
        )
        assertEquals(
            "with no genre chips the header looks straight at the strip",
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.HEADER,
                DetailFocusDirection.DOWN,
                presence(episodes = true)
            )
        )
        assertEquals(
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.HEADER,
                DetailFocusDirection.DOWN,
                presence()
            )
        )

        // The toggle is painted directly ABOVE the strip since 2026-11-21, so
        // its DOWN is the strip - on every page, whatever the tab holds. The
        // rows below are then one more press away, through the strip's own
        // DOWN (see the case below).
        assertEquals(
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(episodes = true))
        )
        assertEquals(
            "and on a title with chips and tags as well: the strip is the row " +
                "physically under the toggle, not the first rail",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(genre = true, keywords = true, episodes = true))
        )
        assertEquals(
            "a movie is the same - the toggle is not in the cast rail's neighbourhood",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(people = true))
        )
        assertEquals(
            "and a page with nothing loaded still moves, because the strip is " +
                "always painted under it",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence())
        )
    }

    @Test
    fun `the summary toggle is the chain stop between the chips and the strip`() {
        // 2026-11-21: DOWN out of the genre chips lands on the overview's
        // toggle when the text overflows, and the strip is one press below it.
        val withOverview = presence(genre = true, overview = true, episodes = true)
        assertEquals(
            "DOWN out of the chips is the toggle that is painted under them",
            DetailRow.OVERVIEW,
            DetailFocusChain.nextRow(DetailRow.GENRE, DetailFocusDirection.DOWN, withOverview)
        )
        assertEquals(
            "DOWN again is the strip",
            DetailRow.TABS,
            DetailFocusChain.nextRow(DetailRow.OVERVIEW, DetailFocusDirection.DOWN, withOverview)
        )
        assertEquals(
            "and UP from the strip is the toggle, step by step - not the chips in " +
                "one press",
            DetailRow.OVERVIEW,
            DetailFocusChain.nextRow(DetailRow.TABS, DetailFocusDirection.UP, withOverview)
        )
        assertEquals(
            "UP from the toggle is the chips",
            DetailRow.GENRE,
            DetailFocusChain.nextRow(DetailRow.OVERVIEW, DetailFocusDirection.UP, withOverview)
        )
        assertEquals(
            "an overview that fits has no toggle, so the chips and the strip stay " +
                "adjacent exactly as they were",
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(genre = true, episodes = true)
            )
        )
    }

    @Test
    fun `the toggle keeps a DOWN only when the page has somewhere to put it`() {
        assertTrue(
            "a page with a row below keeps the press",
            DetailFocusChain.toggleDownIsConsumed(
                presence(episodes = true),
                rowsStillArriving = false
            )
        )
        assertTrue(
            "a movie's cast counts too",
            DetailFocusChain.toggleDownIsConsumed(
                presence(people = true),
                rowsStillArriving = false
            )
        )
        assertTrue(
            "and absence while the rows are STILL ARRIVING is \"not yet\", never " +
                "an edge - the move waits for them rather than the press going " +
                "to a search that has nothing to find either",
            DetailFocusChain.toggleDownIsConsumed(
                presence(),
                rowsStillArriving = true
            )
        )
        assertTrue(
            "and even a page with nothing loaded has the strip below the toggle " +
                "now, so the press is always kept and always lands",
            DetailFocusChain.toggleDownIsConsumed(
                presence(),
                rowsStillArriving = false
            )
        )
    }

    // ── DOWN out of the toggle goes DOWN (spec 2026-11-14) ────────────────

    @Test
    fun `DOWN out of the toggle is the strip, never the genres above it`() {
        assertEquals(
            "the reported cycle (2026-11-14) was DOWN from the genres falling " +
                "through to the toggle and DOWN from the toggle climbing back to " +
                "the genres. The toggle is drawn UNDER the chips, so the row below " +
                "it can never be a row painted above it",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(genre = true, keywords = true))
        )
        assertEquals(
            "and on a title with no tags it is still the strip, not the episode " +
                "section: the toggle is above the strip now, and asking it for a " +
                "list row would skip the strip on the way in",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(genre = true, episodes = true))
        )
        assertEquals(
            "a page whose only rows are rails below the strip is the same answer",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(genre = true, people = true))
        )
    }

    @Test
    fun `a page with nothing below the genres still moves DOWN off the toggle`() {
        assertEquals(
            "the one page this would otherwise strand: genres and nothing else - " +
                "the strip is still painted under the toggle",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(genre = true))
        )
        assertEquals(
            "and on a page with no genre row either, the same answer: a real move, " +
                "not a key taken and nothing done with it",
            DetailRow.TABS,
            DetailFocusChain.toggleDownRow(presence(media = true))
        )
    }
}
