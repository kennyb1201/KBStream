package com.kennyb1201.kbstream.data.library

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
 */
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
