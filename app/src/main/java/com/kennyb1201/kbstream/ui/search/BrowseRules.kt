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
 * Whether a chip the browser is handing focus back to has to be scrolled into
 * view first.
 *
 * A lazy grid composes only the cells it is showing, so a chip deep into a
 * submenu of hundreds is not in the composition at all when the submenu opens
 * again and cannot be focused: the rail has to be scrolled to it first. A chip
 * that IS on screen must not be scrolled to, and that is the reported bug -
 * "Add to Home" on a chip the viewer was looking at ran the scroll anyway,
 * which jumped the grid so that chip became the rail's first row, and the focus
 * system then brought the view back. The action does not move the chip, so
 * nothing about the view may move either: reveal only what is not already
 * showing.
 *
 * [showing] is the cell indices the grid has laid out right now, which is what
 * makes "already on screen" answerable without guessing at geometry.
 */
internal fun browseChipNeedsReveal(index: Int, showing: Iterable<Int>): Boolean =
    index >= 0 && index !in showing

/**
 * What the browse chip grid has to scroll to show one of its rows.
 *
 * The grid reveals its own chips because the framework's automatic request
 * could not be trusted here: nested in the screen's LazyColumn, the request a
 * focused chip made for itself travelled outward through BOTH scrollables, and
 * on the first row that ever needed a reveal (row 3 of the grid) it resolved
 * to a scroll to the very top - the focused chip ended up off-screen. The
 * chips now consume that request and hand the grid this decision instead, so
 * the arithmetic is stated once, in one testable place, instead of being
 * whatever the nested responder chain computed.
 */
internal sealed interface ChipReveal {

    /** The row is already inside the viewport, cue and all: scrolling would only jitter. */
    data object None : ChipReveal

    /**
     * The row is above the viewport (or too close to its top edge to show its
     * cue): align its top to the viewport's top edge plus the cue room.
     *
     * [scrollOffset] is what `animateScrollToItem` takes to do that: NEGATIVE,
     * because the row's top has to come to rest BELOW the viewport's top edge by
     * the room its own focus cue needs above it.
     */
    data class Top(val index: Int, val scrollOffset: Int = 0) : ChipReveal

    /**
     * The row is below the viewport (or too close to its bottom edge): align it
     * to the bottom edge, the nearest of the two, so the rows above it stay
     * where the viewer left them.
     *
     * [scrollOffset] is what `animateScrollToItem` takes, and it is NEGATIVE
     * for the ordinary case of a row shorter than the viewport: the item is
     * placed `viewport - item - room` pixels above the top edge, which puts its
     * bottom the cue room's worth above the viewport's bottom - the room the
     * chip's border and glow draw into.
     */
    data class Bottom(val index: Int, val scrollOffset: Int) : ChipReveal
}

/**
 * The reveal decision for a row, from numbers alone.
 *
 * [itemTop] / [itemSize] are the row's position and height as the grid
 * measured them (a two-line curated chip is taller than a one-line one, which
 * is why the height is passed in rather than assumed), and
 * [viewportStart] / [viewportEnd] are the grid's own visible bounds. A
 * degenerate layout - no index, no height, an inverted viewport - is None: the
 * grid must never scroll on a measurement it cannot trust.
 *
 * [edgeInset] is the room the focused chip's own cue needs past either edge, in
 * raw pixels - its 2dp accent border and its glow draw OUTSIDE its layout
 * rectangle, and the grid clips its content to its own bounds, so a row landed
 * flush with an edge loses both (the caller passes the theme's own chip inset). A row is therefore not "in view" until that room is there
 * too, and the landing keeps it. The default of 0 is the arithmetic without the
 * room, which is what the rule was before the grid's chips were reported
 * clipped along the bottom of the surface.
 */
internal fun chipRevealTarget(
    index: Int,
    itemTop: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
    edgeInset: Int = 0
): ChipReveal {
    if (index < 0 || itemSize <= 0 || viewportEnd <= viewportStart) return ChipReveal.None
    if (
        itemTop >= viewportStart + edgeInset &&
        itemTop + itemSize <= viewportEnd - edgeInset
    ) {
        return ChipReveal.None
    }
    if (itemTop < viewportStart + edgeInset) return ChipReveal.Top(index, -edgeInset)
    return ChipReveal.Bottom(index, itemSize - (viewportEnd - viewportStart) + edgeInset)
}

/**
 * The index that takes a removed item's place: the next one, or the one before
 * it when the removed item was the last.
 *
 * Clamped, never wrapped - removing the last entry must land on the entry that
 * is now last, not throw focus back to the first, which is the very jump this
 * exists to prevent. Null when the index is not in the list, or the list holds
 * nothing else: null is the caller's signal that there is no neighbour to name.
 *
 * The two rails that remove a focusable cell under the viewer's D-pad - the
 * browse submenu's Hide and the search results rail's Hide - both have to name
 * the replacement BEFORE the removed cell leaves the composition, because a
 * request against a node in its last frame is the only request that lands.
 * One rule so the two cannot disagree about which cell that is.
 */
internal fun successorIndexAfter(size: Int, index: Int): Int? {
    if (index !in 0 until size) return null
    if (index + 1 < size) return index + 1
    return (index - 1).takeIf { it >= 0 }
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
