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
 * Every spelling the PRUNE accepts for [list] - [libraryHomeRailIdSpellings],
 * plus the bare number for a row that is addressed as `id:<n>`.
 *
 * Deliberately not the same function the pin toggle reads. The toggle asks "is
 * this row already on Home", where the two spellings the rail itself writes
 * are the whole answer; the prune asks "does this pin still name a list", where
 * a bare number written before the `id:` form existed names the same list and
 * must not be read as a deletion. An MDBList own list has no remoteId, so a pin
 * from an older build carries `"<n>"` while the row says `"id:<n>"` - the
 * reported case.
 */
internal fun libraryHomeRailLiveSpellings(list: LibraryList): Set<String> =
    buildSet {
        addAll(libraryHomeRailIdSpellings(list))
        val canonical = libraryHomeRailId(list)
        if (canonical.startsWith("id:")) {
            canonical.removePrefix("id:").toIntOrNull()?.let { add(it.toString()) }
        }
    }

/**
 * One live list as the reconcile needs it: [id], the identity a pin should
 * carry today, and [spellings], every id a pin for the same list may still be
 * written under (see [libraryHomeRailLiveSpellings]).
 *
 * The pair is what makes a kept pin enough on its own. Keeping a pin that names
 * a list by an old spelling leaves the Home load reading an id the service does
 * not answer for, so the rail draws nothing; the reconcile has to re-point the
 * pin, and to re-point it it needs the row's current id beside the spellings.
 */
data class LibraryHomeRailLiveList(
    val id: String,
    val spellings: Set<String>
)

/** [list] as the reconcile sees it. */
internal fun libraryHomeRailLiveList(list: LibraryList): LibraryHomeRailLiveList =
    LibraryHomeRailLiveList(
        id = libraryHomeRailId(list),
        spellings = libraryHomeRailLiveSpellings(list)
    )

/**
 * [pins] with every pin an older spelling still names re-pointed at the id its
 * live list carries today.
 *
 * A re-point is not a prune: the pin keeps its slot and its name, and only the
 * id moves to the spelling this build writes (so the Home load finds the list
 * again and the next read needs no old spelling). A pin whose source did not
 * answer is untouched - the same rule [withoutVanishedPins] follows - and a pin
 * that already carries the current id is returned as it is.
 *
 * One rail per list afterwards, too: a pin under an old spelling beside one
 * under the current spelling is ONE list written twice, and re-pointing the
 * first at the second's id would otherwise leave two identical rails on Home.
 */
internal fun repointedToLiveIds(
    pins: List<LibraryHomeRailPin>,
    live: Map<String, List<LibraryHomeRailLiveList>>
): List<LibraryHomeRailPin> {
    if (live.isEmpty()) return pins
    return pins
        .map { pin ->
            val current = live[pin.source]
                ?.firstOrNull { pin.id in it.spellings }
                ?.id
            if (current != null && current != pin.id) pin.copy(id = current) else pin
        }
        .distinctBy { it.key }
}

/**
 * The reconcile's whole decision, without the store: [pins] minus the ones no
 * spelling names ([withoutVanishedPins]) and with the survivors re-pointed at
 * their live ids ([repointedToLiveIds]).
 *
 * Order is preserved, so a caller can compare the answer with the stored list
 * and skip the write when nothing moved - which is what keeps an ordinary
 * reconcile from publishing anything at all.
 */
internal fun reconciledPins(
    pins: List<LibraryHomeRailPin>,
    live: Map<String, List<LibraryHomeRailLiveList>>
): List<LibraryHomeRailPin> {
    if (live.isEmpty()) return pins
    val spellings = live.mapValues { (_, rows) ->
        rows.flatMapTo(mutableSetOf()) { row -> row.spellings }
    }
    return repointedToLiveIds(withoutVanishedPins(pins, spellings), live)
}

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
 * The words are SINGULAR ("Movie", "Series", "Movie & Series"), which is what
 * every other type suffix in the app says - the catalog rails' `RailTitle`, the
 * Browse sections, the catalog grid, Settings and the search suggestions all
 * answer "Movie" - so one app reads one way rather than the pinned rail being
 * the only plural (Kenny, on TV: "Movies" in the header, "Movie" everywhere
 * else). The separator is the Because You Watched headers' own ("That Show ·
 * Series").
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
        movies && series -> " · Movie & Series"
        movies -> " · Movie"
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
 * [local] pins with [remote]'s added on top, keyed by (source, id).
 *
 * The adoption of a pins blob that carries no stamp: such a payload proves
 * nothing about deletions (it was written by a build that predates the stamp,
 * which is also, by definition, a build that may simply not know a pin), so its
 * omissions are not read as unpins. A remote pin REPLACES the local one with the
 * same key - its name may be the newer title - a local pin the remote omits is
 * kept, and a pin only the remote has arrives.
 *
 * Deliberately NOT the rule for a stamped payload: a real stamp says the sibling
 * wrote its pins after this device's, and an unpin leaves no tombstone (the
 * tombstone record is about removed TITLES, not pin events), so a stamped
 * remote's omission is the only signal a deliberate unpin leaves. Unioning that
 * case would resurrect every list the viewer unpinned.
 */
internal fun unionLibraryHomeRailPins(
    local: List<LibraryHomeRailPin>,
    remote: List<LibraryHomeRailPin>
): List<LibraryHomeRailPin> {
    if (remote.isEmpty()) return local
    val remoteByKey = remote.associateBy { it.key }
    val merged = local.map { pin -> remoteByKey[pin.key] ?: pin }
    val localKeys = local.mapTo(mutableSetOf()) { it.key }
    return merged + remote.filterNot { it.key in localKeys }
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
