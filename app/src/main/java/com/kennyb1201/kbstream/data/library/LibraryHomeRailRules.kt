package com.kennyb1201.kbstream.data.library

import com.squareup.moshi.JsonClass

/**
 * One Library list the viewer put on Home.
 *
 * Home could show catalogs, collections, browse chips and the app's own rows,
 * but nothing the viewer built or collected in the Library: a BingeCat list, a
 * Trakt list, an MDBList list, a local list - all of that was reachable only by
 * opening Library and picking it again. A pin is what a rail needs to be drawn
 * on Home, and it is deliberately the SAME identity the Library rail uses:
 *
 *  - [source] is [LibrarySource]'s name, so the loader knows which service to
 *    ask, and a stored pin naming a source this build does not know is simply
 *    skipped rather than misread as some other source's list.
 *  - [id] is the list's identity AT THE SERVICE that holds it (`remoteId`: a
 *    Trakt slug, a BingeCat ref), falling back to the numeric id the rail
 *    carries (`id:<n>`, for MDBList lists and local lists). It is NOT the
 *    list's position in the rail, which reindexes whenever a list is added or
 *    removed - a pin keyed on that would silently start pointing at a
 *    neighbour.
 *  - [name] is the title the rail last showed: the heading while the list is
 *    being fetched, and the fallback when the service is unreachable. A rename
 *    re-pins the row with the new name (see LibraryHomeRails.setPinned).
 *
 * The JSON encoding lives with the store (LibraryHomeRails), and these rules
 * are plain Kotlin so they are pinned by a unit test rather than by two devices.
 *
 * Carries a generated Moshi adapter for the same reason as the blob that holds
 * it (see [LibraryHomeRailsBlob]): the blob's adapter asks Moshi for this type
 * by name at runtime, and reflection here is what fails in a release build.
 */
@JsonClass(generateAdapter = true)
data class LibraryHomeRailPin(
    val source: String,
    val id: String,
    val name: String
)

/** Stable identity key for a pinned list. */
internal fun libraryHomeRailKey(source: String, id: String): String =
    "list:" + source + "|" + id

/** A pin's own key (see [libraryHomeRailKey]). */
val LibraryHomeRailPin.key: String
    get() = libraryHomeRailKey(source, id)

/**
 * The stable identity of [list] as a pin.
 *
 * `remoteId` first, because that is the service's own name for the list and it
 * survives a rename; the numeric id second, because an MDBList list and a local
 * list are addressed by it.
 */
internal fun libraryHomeRailId(list: LibraryList): String =
    list.remoteId?.takeIf { it.isNotBlank() } ?: ("id:" + list.id)

/**
 * The pin [list] would add, or null when the row cannot be a rail.
 *
 * A row with no name cannot title a rail, so it is not pinnable at all rather
 * than pinnable into an untitled row.
 */
fun libraryHomeRailPinFor(list: LibraryList): LibraryHomeRailPin? {
    val name = list.name.trim().takeIf { it.isNotEmpty() } ?: return null
    return LibraryHomeRailPin(
        source = list.source.name,
        id = libraryHomeRailId(list),
        name = name
    )
}

/**
 * [pins] minus the ones whose local list was deleted.
 *
 * A list deleted on one device reaches the other as a TOMBSTONE
 * (`personal_lists_deleted`, see LocalLibraryStore) and not as a pin removal:
 * the sibling never edited its pin row, so its copy still names a list that no
 * longer exists anywhere. That pin is invisible only by accident - the rail
 * draws nothing because the list has no items - and it stays in the blob, gets
 * republished on the sibling's next pin edit, and comes back the moment the
 * account's copy is adopted again. A pin is a promise about a list, so it goes
 * when the list goes.
 *
 * Only LOCAL_LIST pins are touched. A Trakt, MDBList or BingeCat pin's id
 * belongs to that service, and this device's tombstone says nothing about it -
 * filtering those would let one device's local cleanup hide a list that still
 * exists on the account.
 */
fun withoutDeletedLocalPins(
    pins: List<LibraryHomeRailPin>,
    deletedListIds: Set<Int>
): List<LibraryHomeRailPin> {
    if (deletedListIds.isEmpty()) return pins
    return pins.filterNot { pin ->
        pin.source == LibrarySource.LOCAL_LIST.name &&
            pin.id.removePrefix("id:").toIntOrNull() in deletedListIds
    }
}

/**
 * [pins] minus the ones naming a list the service no longer has.
 *
 * A list deleted on Trakt's, MDBList's or BingeCat's own site leaves nothing on
 * this device to say so - no tombstone, no event - so the only signal is the
 * list's absence from the rail's next read. [liveIds] is that read: the ids each
 * source still answered with, per [LibraryHomeRailPin.source] name.
 *
 * A source MISSING from [liveIds] is not a source with no lists: it is a source
 * that did not answer (not configured, timed out, rate-limited), and it says
 * nothing about which lists exist. Those pins are kept. Pruning on a failed read
 * would delete a rail for a list that is still there, and publish that mistake
 * to the account - the trap every last-write-wins row in this app has.
 */
fun withoutVanishedPins(
    pins: List<LibraryHomeRailPin>,
    liveIds: Map<String, Set<String>>
): List<LibraryHomeRailPin> {
    if (liveIds.isEmpty()) return pins
    return pins.filter { pin ->
        val live = liveIds[pin.source] ?: return@filter true
        pin.id in live
    }
}
