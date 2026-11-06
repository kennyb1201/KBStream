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
 * The identity a lists-rail row is keyed on in the Library's lazy column.
 *
 * Not [LibraryList.id]: those ids share ONE Int with every other shelf, and
 * the bands are not airtight - a BingeCat list sits on `Int.MIN_VALUE / 2 +
 * index` and a saved community list on `Int.MIN_VALUE / 2 + hash`, a local
 * list's id is a negative creation time, and a Trakt list's is `Int.MIN_VALUE
 * + id`. Two rows that land on the same number are a hard crash in a lazy list
 * ("Key was already used", ANDROID-T), and it is reachable: the same saved list
 * read twice - an overlapping page - lands on one id twice.
 *
 * The source plus [libraryHomeRailId] is the pair the rail itself uses to name
 * a list, so the key is the same identity a Home pin carries: stable across
 * refreshes, and unique per row even when two services pick the same number.
 */
internal fun libraryListRowKey(list: LibraryList): String =
    libraryHomeRailKey(list.source.name, libraryHomeRailId(list))

/**
 * Every id a pin for [list] can be found under: the one this build would write
 * today, and the rail's numeric fallback.
 *
 * Both are live pin keys in the field. A row is pinned under `remoteId` once the
 * source's lists read has one, but an older pin - or one written from a row whose
 * `remoteId` was missing that day - carries `id:<n>` instead, and the two name
 * the same list. The toggle reads all of them (see `pinsNamingList`) rather than
 * deciding the row is not on Home and adding a second rail.
 */
internal fun libraryHomeRailIdSpellings(list: LibraryList): Set<String> =
    setOf(libraryHomeRailId(list), "id:" + list.id)

/**
 * What a pinned Library rail's heading says about its content, as a suffix.
 *
 * A list is not single-type the way the Because You Watched rails are: it can be
 * all films, all shows, or a curated mix. The rail header is the only place the
 * type is drawn (the cards carry no badge), so the heading has to say what the
 * list ACTUALLY holds - which is why this is computed from the items that
 * loaded, not from what the service could hold. A list whose rows all resolve to
 * one type reads as that type even if the source permits both.
 *
 * The words and the separator are the Because You Watched headers' own
 * ("That Show · Series"), so one Home reads one way.
 *
 * A DISPLAY suffix only: it is concatenated at render and never written back
 * into the pin, whose [LibraryHomeRailPin.name] stays the title the rename path
 * owns. A type this build does not recognise (an add-on's own spelling) adds
 * nothing rather than guessing a side, so "no suffix" is the honest answer for a
 * rail that cannot be typed.
 */
fun libraryRailTypeSuffix(mediaTypes: Collection<String>): String {
    var movies = false
    var series = false
    mediaTypes.forEach { raw ->
        when (raw.trim().lowercase()) {
            "movie", "anime.movie" -> movies = true
            "series", "show", "tv", "anime", "anime.series" -> series = true
        }
    }
    return when {
        movies && series -> " · Movies & Series"
        movies -> " · Movies"
        series -> " · Series"
        else -> ""
    }
}

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
 * [pins] with the one named [key] moved by [delta] places.
 *
 * The same move semantics the rail arrangement uses (see
 * `railMoveChangesOrder`): -1 / +1 are one step, [Int.MIN_VALUE] is the top of
 * the pinned group and [Int.MAX_VALUE] its bottom, and nothing is clamped into
 * a move that would not change the order. Pin order IS rail order on Home
 * (see `LibraryHomeRails.pins`), so this list is the whole arrangement of the
 * viewer's Library rails.
 *
 * A [key] no pin carries is not an error - the ghost pin a rename left behind
 * can be removed and moved by the same key - it simply moves nothing.
 */
fun movedPins(
    pins: List<LibraryHomeRailPin>,
    key: String,
    delta: Int
): List<LibraryHomeRailPin> {
    val from = pins.indexOfFirst { it.key == key }
    if (from < 0) return pins
    val to = when {
        delta == Int.MIN_VALUE -> 0
        delta == Int.MAX_VALUE -> pins.lastIndex
        else -> (from + delta).coerceIn(0, pins.lastIndex)
    }
    if (to == from) return pins
    val moved = pins.toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved
}

/**
 * Whether a move of [key] by [delta] would actually change where the rail sits.
 *
 * The manager draws an arrow only when pressing it does something (the same
 * exactness the arrangement rows already keep): the first pinned rail has no
 * "up", the last none of "down".
 */
fun pinsMoveChangesOrder(
    pins: List<LibraryHomeRailPin>,
    key: String,
    delta: Int
): Boolean = movedPins(pins, key, delta) != pins

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
