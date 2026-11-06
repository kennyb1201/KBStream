package com.kennyb1201.kbstream.data.library

/**
 * What one rename landed on: the id the service now addresses the list by.
 *
 * [remoteId] is null when the id cannot change (a local list, MDBList's numeric
 * ids) or when the source's answer did not say what it became - the caller keeps
 * the id it already had. A null WHOLE value is a failed rename: nothing was
 * renamed, and nothing about the list's Home rail may change either.
 *
 * This exists because a rename is the one edit that can change both things a pin
 * is made of: the name it draws (always) and the id it is addressed by (Trakt's
 * slug, BingeCat's ref). Reading the new id from the rename's own answer is what
 * keeps the re-pin from having to guess, or to wait for a lists read that may
 * not have happened yet (spec F1 step 2).
 */
data class LibraryListRename(val remoteId: String? = null)

/**
 * [pins] after a list was renamed to [renamed].
 *
 * [previousIds] is every id spelling the list could be pinned under BEFORE the
 * rename - the service id the rename call addressed it by, and the rail's own
 * `id:<n>` fallback - because the pin's key is the thing that may have just
 * changed. The name is deliberately not a matcher here: it is the other thing
 * that just changed (spec F1 step 1).
 *
 * The matched pin keeps its SLOT and takes the new identity, so the rail does
 * not jump to the end of Home on a rename. Every other spelling of the same list
 * is dropped in the same pass: a device that had already grown a second pin (the
 * renamed row re-added while the stale one sat at the old id) ends with one rail,
 * not two, and the caller publishes that once.
 *
 * No pin for the list is a no-op, and a rename must never create one - the
 * returned list is then identical to [pins] (spec F1 step 3).
 */
fun repinnedAfterRename(
    pins: List<LibraryHomeRailPin>,
    renamed: LibraryHomeRailPin,
    previousIds: Set<String>
): List<LibraryHomeRailPin> {
    val spellings = previousIds + renamed.id
    val matches = pins.indices.filter { index ->
        val pin = pins[index]
        pin.source == renamed.source && pin.id in spellings
    }
    if (matches.isEmpty()) return pins
    val head = matches.first()
    val matched = matches.toSet()
    return pins.mapIndexedNotNull { index, pin ->
        when {
            // The list's own slot: same position on Home, new name and id.
            index == head -> renamed
            // A second spelling of the same list: the ghost rail.
            index in matched -> null
            else -> pin
        }
    }
}

/**
 * Every pin in [pins] that names the list with [source], [ids] and [name].
 *
 * One list can be pinned under more than one spelling, and the Library menu used
 * to look for exactly the spelling this build would write today - so a list
 * pinned as `id:<n>` before the row carried a service id read as NOT on Home,
 * and taking the toggle created the second pin that draws the second rail
 * (spec §1, the leading fork). Reading them all is what makes the toggle answer
 * one row with one state.
 *
 * [ids] is every id spelling of the row (see `libraryHomeRailIdSpellings`); the
 * name is the last-resort spelling, because a pin carries the title the list had
 * when it was pinned, and that is the one thing left to go on when the ids a
 * pin was written with are ones this build cannot derive from the row. It is
 * scoped to the same source for the same reason: a name means nothing across
 * services.
 */
fun pinsNamingList(
    pins: List<LibraryHomeRailPin>,
    source: String,
    ids: Set<String>,
    name: String
): List<LibraryHomeRailPin> {
    val wanted = name.trim()
    return pins.filter { pin ->
        pin.source == source &&
            (pin.id in ids || (wanted.isNotEmpty() && pin.name.trim() == wanted))
    }
}
