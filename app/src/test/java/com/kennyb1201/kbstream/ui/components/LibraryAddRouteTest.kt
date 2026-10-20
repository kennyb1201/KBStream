package com.kennyb1201.kbstream.ui.components

import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.traktListShelfId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where each row of the Add-to-list dialog actually sends its add.
 *
 * The dialog is one shared component now, but the rows it offers come from four
 * different stores — the profile's My List, a local personal list, the MDBList
 * watchlist, an MDBList personal list — and picking the wrong one is silent: the
 * row ticks and the title lands somewhere the user never looks. The route is
 * spelled as a value ([libraryAddRoute]) precisely so the mapping is pinned
 * here instead of living inside a click handler.
 *
 * The last case is the one this replaced: a row that names no destination used
 * to fall through to `false`, which the old dialog rendered as no feedback at
 * all — the shape of "I picked my list and my watchlist and nothing showed up".
 */
class LibraryAddRouteTest {

    private fun row(
        isMyList: Boolean = false,
        isMdbListWatchlist: Boolean = false,
        isTraktWatchlist: Boolean = false,
        list: LibraryList? = null
    ) = LibraryPickerRow(
        isMyList = isMyList,
        isMdbListWatchlist = isMdbListWatchlist,
        isTraktWatchlist = isTraktWatchlist,
        list = list
    )

    private fun list(
        id: Int,
        name: String = "L",
        source: LibrarySource =
            if (id > 0) LibrarySource.MDBLIST_LIST else LibrarySource.LOCAL_LIST
    ) = LibraryList(
        id = id,
        name = name,
        itemCount = 0,
        source = source
    )

    private fun traktList(remoteId: String = "my-list") = LibraryList(
        id = traktListShelfId(77),
        name = "Trakt list",
        itemCount = 0,
        source = LibrarySource.TRAKT_LIST,
        remoteId = remoteId
    )

    @Test
    fun `the My List row goes through the full pipeline`() {
        assertEquals(LibraryAddRoute.MyList, libraryAddRoute(row(isMyList = true)))
    }

    @Test
    fun `the watchlist row goes to the MDBList watchlist`() {
        assertEquals(
            LibraryAddRoute.MdbListWatchlist,
            libraryAddRoute(row(isMdbListWatchlist = true))
        )
    }

    @Test
    fun `the two flags win over whatever list the row carries`() {
        // The built-in rows are constructed with a placeholder list for their
        // sublabel; the flag is what says where the add goes.
        assertEquals(
            LibraryAddRoute.MyList,
            libraryAddRoute(row(isMyList = true, list = list(-7)))
        )
        assertEquals(
            LibraryAddRoute.MdbListWatchlist,
            libraryAddRoute(row(isMdbListWatchlist = true, list = list(-1)))
        )
    }

    @Test
    fun `a positive id is an MDBList personal list`() {
        assertEquals(
            LibraryAddRoute.MdbListList(1234),
            libraryAddRoute(row(list = list(1234)))
        )
    }

    @Test
    fun `a negative id is a local personal list`() {
        assertEquals(
            LibraryAddRoute.LocalList(-987654321),
            libraryAddRoute(row(list = list(-987654321)))
        )
    }

    @Test
    fun `the Trakt watchlist row goes to the Trakt watchlist`() {
        assertEquals(
            LibraryAddRoute.TraktWatchlist,
            libraryAddRoute(row(isTraktWatchlist = true, list = list(-2)))
        )
    }

    @Test
    fun `a Trakt list routes by its own remote id, not its negative shelf id`() {
        assertEquals(
            LibraryAddRoute.TraktList("my-list"),
            libraryAddRoute(row(list = traktList()))
        )
    }

    @Test
    fun `a Trakt list with no remote id is unroutable`() {
        assertEquals(
            LibraryAddRoute.Unroutable,
            libraryAddRoute(row(list = traktList(remoteId = "")))
        )
    }

    @Test
    fun `a row naming no list is unroutable rather than a silent no-op`() {
        assertEquals(LibraryAddRoute.Unroutable, libraryAddRoute(row()))
    }

    @Test
    fun `an id of zero is unroutable, not a local list`() {
        // Local ids are derived from their creation time and negated, so zero
        // is not a list that can exist — it is a row built wrong.
        assertEquals(
            LibraryAddRoute.Unroutable,
            libraryAddRoute(row(list = list(0)))
        )
    }
}
