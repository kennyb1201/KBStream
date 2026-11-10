package com.kennyb1201.kbstream.data.library

import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor

/**
 * The Library rail row for a saved (liked) community list.
 *
 * ONE builder for both callers - the Library's full refresh and the launch-time
 * pin reconcile - because the row's shelf id is half of every spelling the
 * reconcile has to recognise (see [libraryHomeRailLiveSpellings]). Two
 * derivations of that id would let the prune read a pin as gone while the rail
 * it draws is perfectly alive, which is the class of bug this fixes: the
 * reconcile used to contribute only the card's bare ref, so every pin carrying
 * the `id:<band>` form was deleted and the deletion published.
 *
 * [source] picks the id form, exactly as the rail has always built it: an
 * MDBList saved list is addressed by its numeric ref when it has one (its own
 * list ids are numbers), while a Trakt saved list has no numeric id of its own
 * and lives in the saved-list band.
 */
fun likedListRow(source: LibrarySource, card: PublicListCard): LibraryList {
    val vendor = when (source) {
        LibrarySource.TRAKT_LIST -> PublicListVendor.TRAKT
        LibrarySource.MDBLIST_LIST -> PublicListVendor.MDBLIST
        else -> error("not a liked-list source: $source")
    }
    val numericRef = card.ref.toIntOrNull()
    val id = if (source == LibrarySource.MDBLIST_LIST && numericRef != null) {
        numericRef
    } else {
        likedListShelfId(vendor.wire, card.ref)
    }
    return LibraryList(
        id = id,
        name = card.name,
        itemCount = card.itemCount,
        source = source,
        remoteId = card.ref,
        liked = true,
        owner = card.owner
    )
}
