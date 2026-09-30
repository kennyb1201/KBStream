package com.kennyb1201.kbstream.ui.search

/**
 * The pure rules behind the Search screen's browse browser.
 *
 * The browser is the deepest door into the catalog the app has: the curated
 * lists run to hundreds of entries (387 keywords, 279 collections after the
 * 2026-09 expansion), and the point of this file is that the parts a viewer
 * actually feels - what the filter keeps, what the "27 genres" line says, the
 * sentence explaining where a tab goes - are decidable without a screen.
 * DetailScreen and GuideScreen taught this twice over: a UI rule worth having
 * is a rule worth pinning, and a predicate buried in a composable cannot be
 * reached by a test at all.
 *
 * Nothing here touches Android or Compose, on purpose.
 */

/**
 * How many entries a submenu must hold before the browser offers a filter
 * field above its grid.
 *
 * Below this the field costs more than it saves: a dozen chips are one screen
 * of D-pad presses, whereas Keywords and Collections are hundreds of cells
 * that a two-letter filter collapses to one row. Genres (26), Services (91),
 * Studios (90), Decades (12) and Collections (279) all clear it; the kids
 * menus, which are deliberately short, mostly do not.
 */
internal const val BROWSE_FILTER_MIN_ENTRIES = 12

/**
 * The entries a filter query keeps: every chip whose name contains the query,
 * case-insensitively, in the order the category was curated.
 *
 * Order is deliberately preserved rather than re-ranked by relevance. The
 * lists are presented "household names first, rest alphabetical" (see
 * [popularFirst]) and a filter that reshuffled them would move a chip the
 * viewer was about to press. A blank or whitespace-only query keeps every
 * entry, which is what clears the filter.
 */
internal fun filterBrowseEntries(
    entries: List<BrowseEntry>,
    query: String
): List<BrowseEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { it.name.contains(needle, ignoreCase = true) }
}

/**
 * The noun one chip of a category is counted in - "genre", "service",
 * "collection". Used for both the tab badge's tooltip-ish count and the
 * "12 of 279 collections" line above the grid, so those two can never
 * disagree about what they are counting.
 *
 * "Services & Networks" counts in services: the strip's own label is the only
 * place the two are joined, and the chips under it are overwhelmingly the
 * streamers.
 */
internal fun browseCategoryNoun(key: String): String = when (key) {
    "genres" -> "genre"
    "keywords" -> "keyword"
    "services" -> "service"
    "studios" -> "studio"
    "decades" -> "decade"
    "collections" -> "collection"
    else -> "entry"
}

/**
 * The same noun in the plural - "genres", "collections", "entries".
 *
 * Spelled out rather than built with a trailing `s`, because the fallback noun
 * is "entry" and "entrys" is the kind of small wrongness that makes an app
 * look careless exactly where it is trying not to.
 */
internal fun browseCategoryNounPlural(key: String): String = when (key) {
    "genres" -> "genres"
    "keywords" -> "keywords"
    "services" -> "services"
    "studios" -> "studios"
    "decades" -> "decades"
    "collections" -> "collections"
    else -> "entries"
}

/**
 * "26 genres", or "3 of 279 collections" once a filter is narrowing the list.
 *
 * Spelling out the count ("1 genre", not "1 genres") is not fussiness: the
 * line sits directly over the grid at ten feet, and a lone "1 genres" reads
 * as a bug in a way a correct singular never draws attention to. When nothing
 * is filtered out the "of" half is dropped, so the common case stays short.
 */
internal fun browseCountLabel(
    shown: Int,
    total: Int,
    key: String
): String {
    val totalLabel = if (total == 1) {
        "1 ${browseCategoryNoun(key)}"
    } else {
        "$total ${browseCategoryNounPlural(key)}"
    }
    return if (shown == total) totalLabel else "$shown of $totalLabel"
}

/**
 * The one-sentence answer to "what is behind this tab?", shown under the
 * category name rather than left to the viewer to discover.
 *
 * The six tabs open five different screens with five different rail sets
 * (Genres and Keywords open the same tag screen with different id spaces,
 * Services and Studios both open the studio screen with different id spaces,
 * Decades and Collections have their own). That is not guessable from a
 * category name, so the browser says it.
 */
internal fun browseCategoryBlurb(key: String): String = when (key) {
    "genres" ->
        "Every genre, each opening its own movie and series rails."

    "keywords" ->
        "Specific themes and subjects - the deepest way into the catalog."

    "services" ->
        "Streaming services and broadcast networks, plus their originals."

    "studios" ->
        "The production houses behind the titles."

    "decades" ->
        "A decade at a time, movies and series kept separate."

    "collections" ->
        "Franchises and curated sets, kept together."

    else ->
        "Browse the catalog."
}
