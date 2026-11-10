package com.kennyb1201.kbstream.data.library

import com.kennyb1201.kbstream.data.bingecat.bingecatShelfId
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin prune against every spelling a pin can still be carrying.
 *
 * The reported loss (Kenny, 2026-11-09): his Trakt and MDBList pins vanished
 * from Home AND stopped reading as pinned in Library. The lists were fine - the
 * reconcile read each pin's single current id, did not find the older spelling
 * the pin carried, and published the deletion to the account. Keeping the pin
 * would not have been enough either: the Home load reads by the pin's id, so an
 * old spelling draws nothing.
 *
 * [reconciledPins] is the decision the store writes: keep a pin when ANY
 * spelling names a live list, and re-point the kept ones at the id the row
 * carries today.
 */
class LibraryHomeRailPinSpellingsTest {

    private fun pin(source: LibrarySource, id: String) =
        LibraryHomeRailPin(source = source.name, id = id, name = "Rail $id")

    private fun liveOf(vararg lists: LibraryList) =
        lists.map { libraryHomeRailLiveList(it) }

    private fun live(source: LibrarySource, vararg lists: LibraryList) =
        mapOf(source.name to liveOf(*lists))

    private fun traktOwn(traktId: Int, slug: String) = LibraryList(
        id = traktListShelfId(traktId),
        name = "Trakt $slug",
        itemCount = 4,
        source = LibrarySource.TRAKT_LIST,
        remoteId = slug
    )

    private fun mdbOwn(id: Int) = LibraryList(
        id = id,
        name = "MDBList $id",
        itemCount = 7,
        source = LibrarySource.MDBLIST_LIST
    )

    private fun likedCard(ref: String) = PublicListCard(
        vendor = PublicListVendor.TRAKT,
        ref = ref,
        name = "Liked $ref",
        owner = "owner",
        description = null,
        itemCount = 9,
        likes = 3
    )

    // ── A pin kept under an older spelling is re-pointed ────────────────────

    @Test
    fun `a trakt own list pinned by its shelf id is kept and re-pointed to the slug`() {
        val list = traktOwn(traktId = 42, slug = "my-faves")
        val old = pin(LibrarySource.TRAKT_LIST, "id:" + list.id)

        val kept = reconciledPins(listOf(old), live(LibrarySource.TRAKT_LIST, list))

        assertEquals(
            "the Home load reads by the pin's id, so surviving is not enough - it must move",
            listOf(old.copy(id = "my-faves")),
            kept
        )
        assertEquals(
            "and the rail keeps the heading it had",
            old.name,
            kept.single().name
        )
    }

    @Test
    fun `an mdblist own list pinned by its bare number is kept and re-pointed`() {
        val old = pin(LibrarySource.MDBLIST_LIST, "123")

        val kept = reconciledPins(
            listOf(old),
            live(LibrarySource.MDBLIST_LIST, mdbOwn(123))
        )

        assertEquals(listOf(old.copy(id = "id:123")), kept)
    }

    @Test
    fun `a liked list pinned by its band id is kept and re-pointed to the ref`() {
        val row = likedListRow(LibrarySource.TRAKT_LIST, likedCard("800238"))
        val old = pin(LibrarySource.TRAKT_LIST, "id:" + row.id)

        val kept = reconciledPins(listOf(old), live(LibrarySource.TRAKT_LIST, row))

        assertEquals(
            "the band id names the same saved list as the ref the rail writes today",
            listOf(old.copy(id = "800238")),
            kept
        )
    }

    @Test
    fun `a bingecat list pinned by its shelf id is kept and re-pointed to the ref`() {
        val list = LibraryList(
            id = bingecatShelfId(3),
            name = "BingeCat shelf",
            itemCount = 11,
            source = LibrarySource.BINGECAT_LIST,
            remoteId = "abc123"
        )
        val old = pin(LibrarySource.BINGECAT_LIST, "id:" + list.id)

        val kept = reconciledPins(listOf(old), live(LibrarySource.BINGECAT_LIST, list))

        assertEquals(listOf(old.copy(id = "abc123")), kept)
    }

    @Test
    fun `a row with no ref keeps the shelf id as the current spelling`() {
        val list = LibraryList(
            id = bingecatShelfId(1),
            name = "No ref",
            itemCount = 2,
            source = LibrarySource.BINGECAT_LIST,
            remoteId = null
        )
        val current = "id:" + list.id

        assertEquals(
            listOf(pin(LibrarySource.BINGECAT_LIST, current)),
            reconciledPins(
                listOf(pin(LibrarySource.BINGECAT_LIST, current)),
                live(LibrarySource.BINGECAT_LIST, list)
            )
        )
    }

    // ── What must NOT change ────────────────────────────────────────────────

    @Test
    fun `a genuinely deleted list is still pruned under every spelling`() {
        val liveRows = live(LibrarySource.TRAKT_LIST, traktOwn(traktId = 7, slug = "kept"))

        val kept = reconciledPins(
            listOf(
                pin(LibrarySource.TRAKT_LIST, "kept"),
                pin(LibrarySource.TRAKT_LIST, "gone-slug"),
                pin(LibrarySource.TRAKT_LIST, "id:" + traktListShelfId(7)),
                pin(LibrarySource.TRAKT_LIST, "id:" + traktListShelfId(8))
            ),
            liveRows
        )

        assertEquals(
            "the deleted list goes; the live list's old-spelling pin re-points onto the pin already " +
                "there, so the rail is not drawn twice",
            listOf(pin(LibrarySource.TRAKT_LIST, "kept")),
            kept
        )
    }

    @Test
    fun `a source that did not answer prunes and re-points nothing`() {
        val pins = listOf(pin(LibrarySource.MDBLIST_LIST, "123"))

        assertEquals(
            "an absent source is not an empty one",
            pins,
            reconciledPins(pins, live(LibrarySource.TRAKT_LIST, traktOwn(traktId = 1, slug = "s")))
        )
        assertEquals("and no map at all says nothing", pins, reconciledPins(pins, emptyMap()))
    }

    @Test
    fun `a source that answered with no lists still prunes`() {
        assertEquals(
            emptyList<LibraryHomeRailPin>(),
            reconciledPins(
                listOf(pin(LibrarySource.MDBLIST_LIST, "id:1")),
                mapOf(LibrarySource.MDBLIST_LIST.name to emptyList())
            )
        )
    }

    @Test
    fun `a pin already carrying the current id is not rewritten`() {
        val pins = listOf(
            pin(LibrarySource.TRAKT_LIST, "my-faves"),
            pin(LibrarySource.MDBLIST_LIST, "id:123")
        )

        assertEquals(
            "the store compares the answer with what it holds, so this must be a no-op",
            pins,
            reconciledPins(
                pins,
                live(
                    LibrarySource.TRAKT_LIST,
                    traktOwn(traktId = 42, slug = "my-faves")
                ) + live(LibrarySource.MDBLIST_LIST, mdbOwn(123))
            )
        )
    }

    // ── The spelling helper itself ──────────────────────────────────────────

    @Test
    fun `the live spellings add the bare number only for id colon rows`() {
        assertTrue(
            "id:123 is also the list a pin may call 123",
            "123" in libraryHomeRailLiveSpellings(mdbOwn(123))
        )
        assertFalse(
            "a slug row's spellings are the slug and its shelf id",
            libraryHomeRailLiveSpellings(traktOwn(traktId = 42, slug = "my-faves"))
                .contains("42")
        )
    }
}
