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
 * The order is the PAINTED order (header, tags, episodes, people, networks,
 * production, collection, Media, More Like This), not the order the rails are
 * written in the source, because a chain that did not mirror the screen would
 * send UP into a row the viewer can see is below them.
 */
class DetailFocusChainTest {

    /** The rows a page has, by the name used in each case below. */
    private fun presence(
        keywords: Boolean = false,
        episodes: Boolean = false,
        people: Boolean = false,
        network: Boolean = false,
        production: Boolean = false,
        collection: Boolean = false,
        media: Boolean = false,
        moreLikeThis: Boolean = false
    ) = DetailRowPresence(
        hasKeywordsRow = keywords,
        hasEpisodesRow = episodes,
        hasPeopleRow = people,
        hasNetworkRow = network,
        hasProductionRow = production,
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
        DetailRow.entries
            .filter { it != DetailRow.HEADER }
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
            keywords = true,
            episodes = true,
            people = true,
            network = true,
            production = true,
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
                "This is painted - the review rail above it and the bottom " +
                "spacer below are not rows the chain names",
            DetailFocusChain.nextRow(
                DetailRow.COLLECTION,
                DetailFocusDirection.DOWN,
                collectionLast
            )
        )

        val recsLast = presence(production = true, collection = true, moreLikeThis = true)
        assertNull(
            "and More Like This is the last row the page ever names",
            DetailFocusChain.nextRow(
                DetailRow.MORE_LIKE_THIS,
                DetailFocusDirection.DOWN,
                recsLast
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
    fun `the Media row sits between the collection and More Like This`() {
        // The row is one entry of the chain however many rails it holds inside
        // itself, which is the whole point of naming it here: DOWN out of it is
        // the recommendations rail and UP out of it is the collection, and only
        // the row's own tab strip knows there are two pages in between.
        val movie = presence(production = true, collection = true, media = true, moreLikeThis = true)
        assertEquals(
            DetailRow.MEDIA,
            DetailFocusChain.nextRow(DetailRow.COLLECTION, DetailFocusDirection.DOWN, movie)
        )
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, movie)
        )
        assertEquals(
            DetailRow.MEDIA,
            DetailFocusChain.nextRow(DetailRow.MORE_LIKE_THIS, DetailFocusDirection.UP, movie)
        )
        assertEquals(
            DetailRow.COLLECTION,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, movie)
        )
    }

    @Test
    fun `a title with only a Media row is still reachable from both sides`() {
        // No collection and no cast: the row's neighbours are the production
        // chips above it and the recommendations below.
        val mediaOnly = presence(production = true, media = true, moreLikeThis = true)
        assertEquals(
            DetailRow.PRODUCTION,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.UP, mediaOnly)
        )
        assertEquals(
            DetailRow.MORE_LIKE_THIS,
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, mediaOnly)
        )
        // A title with nothing but videos and stills: the row is the last one
        // the chain names, and UP still lands on the header rather than
        // stranding the press.
        val only = presence(media = true)
        assertEquals(
            DetailRow.HEADER,
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
    fun `up out of a network chip with nothing above it ends on the header`() {
        // The trap this chain exists to make impossible: networks, and nothing
        // else on the page above them.
        val networksOnly = presence(network = true)
        assertEquals(
            "an item with networks and no cast must not be left with nowhere " +
                "to go on UP",
            DetailRow.HEADER,
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
            DetailRow.HEADER,
            DetailFocusChain.nextRow(DetailRow.EPISODES, DetailFocusDirection.UP, noTags)
        )
    }

    @Test
    fun `up from any row lands on a row that exists, and on the header at worst`() {
        DetailRow.entries.forEach { row ->
            // A page whose only row is this one.
            val only = presence(
                keywords = row == DetailRow.KEYWORDS,
                episodes = row == DetailRow.EPISODES,
                people = row == DetailRow.PEOPLE,
                network = row == DetailRow.NETWORK,
                production = row == DetailRow.PRODUCTION,
                collection = row == DetailRow.COLLECTION,
                media = row == DetailRow.MEDIA,
                moreLikeThis = row == DetailRow.MORE_LIKE_THIS
            )
            if (row == DetailRow.HEADER) {
                assertNull(DetailFocusChain.nextRow(row, DetailFocusDirection.UP, only))
            } else {
                assertEquals(
                    "UP out of $row can never be stranded: the header is always there",
                    DetailRow.HEADER,
                    DetailFocusChain.nextRow(row, DetailFocusDirection.UP, only)
                )
            }
        }
    }

    @Test
    fun `down from every row is a row that exists or the end of the chain`() {
        val page = presence(
            keywords = true,
            episodes = true,
            people = true,
            network = true,
            production = true,
            collection = true,
            media = true,
            moreLikeThis = true
        )
        DetailRow.entries.forEach { row ->
            val target = DetailFocusChain.nextRow(row, DetailFocusDirection.DOWN, page)
            if (row == DetailRow.MORE_LIKE_THIS) {
                assertNull("the bottom row has nothing below it in the chain", target)
            } else {
                assertTrue(
                    "DOWN out of $row must land on a row this page has",
                    target != null && DetailFocusChain.exists(target, page)
                )
            }
        }
    }
}
