package com.kennyb1201.kbstream.ui.browse

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where Back from a browse page's Detail page lands.
 *
 * Reported problem: opening a title from Genre/Tag, Studio, Collection or
 * Decade and pressing Back put the viewer back at the TOP of the list, with the
 * focus on the page's first card, instead of on the title they had just
 * opened. The browse page is rebuilt from scratch when its Detail page is
 * backed out of, so the card has to be recorded on the way out and handed back
 * to the page that reopens - the same trip the detail page's own
 * DetailReturnFocus makes for its drill-downs.
 */
class BrowseReturnFocusTest {

    /**
     * The record is process-wide by design - that is what survives the trip -
     * so no test may leave one behind for the next.
     */
    @After
    fun tearDown() {
        KEYS.forEach { BrowseReturnFocus.consume(it) }
    }

    private fun target(
        screenKey: String = GENRE_KEY,
        itemId: String = "movie:603",
        position: Int = 20,
        railKey: String? = "Popular",
        railIndex: Int = 2
    ): BrowseReturnTarget =
        BrowseReturnTarget(
            screenKey = screenKey,
            itemId = itemId,
            position = position,
            railKey = railKey,
            railIndex = railIndex
        )

    @Test
    fun `the card is handed back to the page it was pressed on`() {
        val recorded = target()
        BrowseReturnFocus.record(recorded)
        assertEquals(recorded, BrowseReturnFocus.consume(GENRE_KEY))
    }

    @Test
    fun `a record is only ever read once`() {
        BrowseReturnFocus.record(target())
        assertEquals(target(), BrowseReturnFocus.consume(GENRE_KEY))
        assertNull(BrowseReturnFocus.consume(GENRE_KEY))
    }

    @Test
    fun `another genre does not take the record`() {
        // Field step 7: opening a DIFFERENT genre starts at the top. The record
        // the first genre left behind is not its to read.
        BrowseReturnFocus.record(target())
        assertNull(BrowseReturnFocus.consume(comedyBrowseKey()))
        assertEquals(target(), BrowseReturnFocus.consume(GENRE_KEY))
    }

    @Test
    fun `a title opened from the first page does not swallow the record`() {
        BrowseReturnFocus.record(target())
        assertNull(BrowseReturnFocus.consume(COLLECTION_KEY))
        assertEquals(target(), BrowseReturnFocus.consume(GENRE_KEY))
    }

    @Test
    fun `an ordinary open has nothing to return to`() {
        assertNull(BrowseReturnFocus.consume(GENRE_KEY))
    }

    @Test
    fun `the rail and the card come back with the record`() {
        val recorded = target(
            itemId = "tv:1399",
            position = 31,
            railKey = "Top Rated",
            railIndex = 5
        )
        BrowseReturnFocus.record(recorded)
        val consumed = BrowseReturnFocus.consume(GENRE_KEY)
        assertEquals("tv:1399", consumed?.itemId)
        assertEquals(31, consumed?.position)
        assertEquals("Top Rated", consumed?.railKey)
        assertEquals(5, consumed?.railIndex)
    }

    @Test
    fun `a page with no rail list to scroll records none`() {
        // A collection is one rail that is already on screen: there is no outer
        // list to put back, only the rail's own scroll.
        val recorded = target(
            screenKey = COLLECTION_KEY,
            itemId = "collection_part:11",
            position = 1,
            railKey = null,
            railIndex = 0
        )
        BrowseReturnFocus.record(recorded)
        val consumed = BrowseReturnFocus.consume(COLLECTION_KEY)
        assertEquals("collection_part:11", consumed?.itemId)
        assertEquals(1, consumed?.position)
        assertNull(consumed?.railKey)
    }

    @Test
    fun `the newest press is the one that comes back`() {
        BrowseReturnFocus.record(target(position = 3))
        BrowseReturnFocus.record(target(itemId = "movie:99", position = 41))
        val consumed = BrowseReturnFocus.consume(GENRE_KEY)
        assertEquals("movie:99", consumed?.itemId)
        assertEquals(41, consumed?.position)
    }

    @Test
    fun `each browse page keys itself apart from the others`() {
        val keys = listOf(
            tagBrowseScreenKey(18, isKeyword = false, type = "movie"),
            tagBrowseScreenKey(18, isKeyword = true, type = "movie"),
            tagBrowseScreenKey(18, isKeyword = false, type = "tv"),
            studioBrowseScreenKey(213, isNetwork = true, providerId = null),
            studioBrowseScreenKey(213, isNetwork = false, providerId = null),
            studioBrowseScreenKey(213, isNetwork = true, providerId = 8),
            collectionBrowseScreenKey(456),
            decadeBrowseScreenKey(1990)
        )
        assertEquals(
            "a genre and a keyword share one id space, a movie list and a series " +
                "list of the same genre are two lists, a network and a company " +
                "share one id space, and a brand page and its service page are " +
                "two pages - every one of those is a different list and has to be " +
                "a different key, or Back restores a position from another page",
            keys.distinct().size,
            keys.size
        )
    }

    @Test
    fun `every key names its own page`() {
        assertEquals("tag:genre:18:movie", tagBrowseScreenKey(18, false, "movie"))
        assertEquals("tag:keyword:18:movie", tagBrowseScreenKey(18, true, "movie"))
        assertEquals("studio:network:213:service:-1", studioBrowseScreenKey(213, true, null))
        assertEquals("collection:456", collectionBrowseScreenKey(456))
        assertEquals("decade:1990", decadeBrowseScreenKey(1990))
        assertNotEquals(
            collectionBrowseScreenKey(456),
            collectionBrowseScreenKey(457)
        )
    }

    private fun comedyBrowseKey(): String = tagBrowseScreenKey(35, false, "movie")

    private companion object {
        const val GENRE_KEY = "tag:genre:99:movie"
        const val COLLECTION_KEY = "collection:456"

        val KEYS = listOf(
            GENRE_KEY,
            COLLECTION_KEY,
            "tag:genre:18:movie",
            "tag:keyword:18:movie",
            "tag:genre:18:tv",
            "tag:genre:35:movie",
            "studio:network:213:service:-1",
            "studio:company:213:service:-1",
            "studio:network:213:service:8",
            "collection:457",
            "decade:1990"
        )
    }
}
