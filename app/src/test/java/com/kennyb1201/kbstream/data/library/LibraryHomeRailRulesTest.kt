package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity a pinned Library list keeps for the life of the pin.
 *
 * A rail on Home outlives the screen that made it, and on the other TV it
 * outlives the device that made it, so the pin's key has to name the LIST and
 * nothing else - not its position in the rail, which reindexes on every add or
 * remove, and not its title, which the viewer can change.
 */
class LibraryHomeRailRulesTest {

    @Test
    fun `a service-held list is keyed by the service's own id, not by its slot`() {
        val first = LibraryList(
            id = 7,
            name = "Top 10 Starz",
            itemCount = 9,
            source = LibrarySource.BINGECAT_LIST,
            remoteId = "ref-123"
        )
        // The same list after a neighbour was deleted: the rail's numeric id
        // moved, the service's own id did not.
        val reindexed = first.copy(id = 3, itemCount = 10)

        val pin = libraryHomeRailPinFor(first)!!
        assertEquals("ref-123", pin.id)
        assertEquals(pin.key, libraryHomeRailPinFor(reindexed)!!.key)
        assertEquals("BINGECAT_LIST", pin.source)
        assertEquals("Top 10 Starz", pin.name)
    }

    @Test
    fun `a local or MDBList list falls back to the id the rail carries`() {
        val local = libraryHomeRailPinFor(
            LibraryList(
                id = 4,
                name = "Halloween",
                itemCount = 12,
                source = LibrarySource.LOCAL_LIST
            )
        )!!
        assertEquals("id:4", local.id)
        assertEquals("list:LOCAL_LIST|id:4", local.key)

        val remote = libraryHomeRailPinFor(
            LibraryList(
                id = 88,
                name = "Best of 90s",
                itemCount = 20,
                source = LibrarySource.MDBLIST_LIST
            )
        )!!
        assertEquals("id:88", remote.id)
    }

    @Test
    fun `a rename changes the label, never the identity`() {
        val before = libraryHomeRailPinFor(
            LibraryList(
                id = 1,
                name = "Watchlist picks",
                itemCount = 5,
                source = LibrarySource.TRAKT_LIST,
                remoteId = "watchlist-picks"
            )
        )!!
        val after = libraryHomeRailPinFor(
            LibraryList(
                id = 1,
                name = "Friday night",
                itemCount = 5,
                source = LibrarySource.TRAKT_LIST,
                remoteId = "watchlist-picks"
            )
        )!!
        assertEquals(before.key, after.key)
        assertNotEquals(before.name, after.name)
    }

    @Test
    fun `two sources may carry a list with the same name and stay distinct`() {
        val trakt = libraryHomeRailPinFor(
            LibraryList(1, "Watchlist", 3, LibrarySource.TRAKT_LIST, remoteId = "me/watchlist")
        )!!
        val bingecat = libraryHomeRailPinFor(
            LibraryList(2, "Watchlist", 3, LibrarySource.BINGECAT_LIST, remoteId = "me/watchlist")
        )!!
        assertNotEquals(trakt.key, bingecat.key)
    }

    @Test
    fun `an untitled or blank-named row cannot become a rail`() {
        assertNull(
            libraryHomeRailPinFor(
                LibraryList(1, "   ", 0, LibrarySource.LOCAL_LIST)
            )
        )
    }

    @Test
    fun `each account's watchlist is its own pinnable list`() {
        val sources = listOf(
            LibrarySource.TRAKT_WATCHLIST,
            LibrarySource.MDBLIST_WATCHLIST,
            LibrarySource.SIMKL_WATCHLIST,
            LibrarySource.BINGECAT_WATCHLIST
        )
        val rows = sources.map { source ->
            LibraryList(
                id = watchlistShelfId(source),
                name = source.watchlistTitle,
                itemCount = 5,
                source = source,
                remoteId = WATCHLIST_REMOTE_ID
            )
        }
        val pins = rows.map { libraryHomeRailPinFor(it)!! }

        assertEquals("one rail per account, not one merged rail", sources.size, pins.size)
        assertEquals(
            "the four watchlists must not share a Home rail",
            sources.size,
            pins.map { it.key }.toSet().size
        )
        assertEquals(
            "nor a row id in the lists rail (which is keyed on it)",
            sources.size,
            rows.map { it.id }.toSet().size
        )
        assertEquals("a Trakt watchlist pin", "list:TRAKT_WATCHLIST|watchlist", pins[0].key)
        // Watchlists wear the account's name, so four rails are tellable apart
        // on Home without opening them.
        assertEquals(listOf("Trakt watchlist", "MDBList watchlist", "Simkl watchlist", "BingeCat watchlist"), pins.map { it.name })
        assertTrue(
            "and none of their shelf ids lands in a real list's range",
            rows.all { it.id > 0 && it.id != traktListShelfId(1) }
        )
    }

    @Test
    fun `a watchlist offers no rename or delete it cannot keep`() {
        assertFalse(LibrarySource.TRAKT_WATCHLIST.canManageList)
        assertFalse(LibrarySource.MDBLIST_WATCHLIST.canManageList)
        assertFalse(LibrarySource.BINGECAT_WATCHLIST.canManageList)
        assertFalse(LibrarySource.SIMKL_WATCHLIST.canManageList)
    }

    @Test
    fun `a pin key is stable across a round trip through its own fields`() {
        val pin = LibraryHomeRailPin(
            source = LibrarySource.BINGECAT_LIST.name,
            id = "ref-9",
            name = "Tract Recommendations"
        )
        val rebuilt = LibraryHomeRailPin(pin.source, pin.id, pin.name)
        assertEquals(pin.key, rebuilt.key)
        assertEquals("list:BINGECAT_LIST|ref-9", pin.key)
    }

    /**
     * The lazy column's key.
     *
     * Two rows on one key is a fatal crash (ANDROID-T), and one Int is shared by
     * every shelf: a BingeCat list and a saved community list both sit on
     * `Int.MIN_VALUE / 2 + n`, a local list carries a negative creation time and
     * a Trakt list `Int.MIN_VALUE + id`. So the key must name the LIST, and two
     * different lists may never answer with one key - and one list read twice
     * must answer with one key, so the rail can collapse the repeat.
     */
    @Test
    fun `the rail key tells two shelves on one id apart`() {
        // `bingecatShelfId(2)`'s number: the BingeCat band and the saved-list
        // band are both `Int.MIN_VALUE / 2 + n` (see bingecatShelfId /
        // likedListShelfId), so one number really can name two lists.
        val sharedId = Int.MIN_VALUE / 2 + 2
        val bingecat = LibraryList(
            id = sharedId,
            name = "Top 10 Starz",
            itemCount = 9,
            source = LibrarySource.BINGECAT_LIST,
            remoteId = "ref-123"
        )
        // A saved community list whose masked hash landed on the BingeCat row's
        // number - the case the bands do not rule out.
        val liked = LibraryList(
            id = sharedId,
            name = "Neo-noir",
            itemCount = 30,
            source = LibrarySource.TRAKT_LIST,
            remoteId = "neo-noir"
        )

        assertEquals(bingecat.id, liked.id)
        assertNotEquals(libraryListRowKey(bingecat), libraryListRowKey(liked))
        assertEquals("list:BINGECAT_LIST|ref-123", libraryListRowKey(bingecat))
        assertEquals("list:TRAKT_LIST|neo-noir", libraryListRowKey(liked))
    }

    @Test
    fun `the rail key survives a reindex and repeats for one list`() {
        val list = LibraryList(
            id = Int.MIN_VALUE / 2,
            name = "Top 10 Starz",
            itemCount = 9,
            source = LibrarySource.BINGECAT_LIST,
            remoteId = "ref-123"
        )
        // Reindexed after a neighbour went away: same key, or the rail drops and
        // rebuilds the row on every refresh.
        assertEquals(
            libraryListRowKey(list),
            libraryListRowKey(list.copy(id = Int.MIN_VALUE / 2 + 5))
        )
        // Read twice (an overlapping page): ONE key, so the rail's distinctBy
        // collapses the repeat instead of handing a lazy list a duplicate.
        assertEquals(libraryListRowKey(list), libraryListRowKey(list.copy(itemCount = 10)))
    }
}
