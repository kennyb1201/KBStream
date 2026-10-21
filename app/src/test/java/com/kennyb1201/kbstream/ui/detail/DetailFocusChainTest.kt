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
    fun `the genre chips are a row of their own above the tag rail`() {
        val page = presence(genre = true, keywords = true)
        assertEquals(
            "UP out of the tag rail reaches the genre chips painted above it, " +
                "rather than jumping over a rail the viewer can see to the PLAY row",
            DetailRow.GENRE,
            DetailFocusChain.nextRow(DetailRow.KEYWORDS, DetailFocusDirection.UP, page)
        )
        assertEquals(
            "and DOWN from the chips drops into the tag rail",
            DetailRow.KEYWORDS,
            DetailFocusChain.nextRow(DetailRow.GENRE, DetailFocusDirection.DOWN, page)
        )
        assertEquals(
            "UP out of the chips is the header, which always exists",
            DetailRow.HEADER,
            DetailFocusChain.nextRow(DetailRow.GENRE, DetailFocusDirection.UP, page)
        )
        assertEquals(
            "with no tag rail the chips step DOWN to the first row the page does have",
            DetailRow.EPISODES,
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
                genre = row == DetailRow.GENRE,
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
}
