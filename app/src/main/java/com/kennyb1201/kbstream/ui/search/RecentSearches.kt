package com.kennyb1201.kbstream.ui.search

/**
 * [recents] without [query].
 *
 * Matched the way [SearchViewModel.commitSearch] de-dupes them - trimmed, and
 * case-insensitive - so the chip that is removed is the one the viewer pressed:
 * the same query typed in two casings is one chip, so deleting "Alien" must
 * delete "alien" too.
 *
 * Everything else keeps its order and its position: removing one search is not
 * a reason for the rest of the strip to reshuffle under the viewer's finger.
 */
internal fun withoutRecentSearch(recents: List<String>, query: String): List<String> {
    val target = query.trim()
    return recents.filterNot { it.equals(target, ignoreCase = true) }
}
