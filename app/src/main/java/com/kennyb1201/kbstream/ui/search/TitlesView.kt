package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.library.TasteSignals

/**
 * Which half of the merged Titles rail the viewer is looking at.
 *
 * The rail merges TMDB's movie and TV results into one list, so a search for
 * "star" shows films and series interleaved with no way to say "only films".
 * This is a VIEW of the merged list, not a second query: switching it re-reads
 * what is already loaded and never re-fetches.
 */
enum class TitleTypeFilter {
    ALL,
    MOVIES,
    SERIES
}

/**
 * How the Titles rail is ordered.
 *
 * [BEST_MATCH] is the order the rail has always used
 * ([searchTitleOrder]): match quality, then newest year, then the viewer's
 * taste, then the name. [NEWEST] and [AZ] are the viewer's own orderings and
 * deliberately ignore taste - see [sortTitles].
 */
enum class TitleSort {
    BEST_MATCH,
    NEWEST,
    AZ
}

/** Whether [type] belongs to [filter]. */
internal fun titleMatchesFilter(
    type: String,
    filter: TitleTypeFilter
): Boolean = when (filter) {
    TitleTypeFilter.ALL -> true
    // TMDB's movie wave carries "movie"; every other type this rail can hold
    // is television ("series", and an add-on's "tv" spelling). Asking "is it
    // NOT a movie" rather than "is it exactly series" keeps a future spelling
    // of the TV type from matching neither chip and vanishing from both.
    TitleTypeFilter.MOVIES -> type.trim().equals("movie", ignoreCase = true)
    TitleTypeFilter.SERIES -> !type.trim().equals("movie", ignoreCase = true)
}

/** The merged list, narrowed to [filter]. */
internal fun filterTitlesByType(
    items: List<SearchTitleResult>,
    filter: TitleTypeFilter
): List<SearchTitleResult> =
    if (filter == TitleTypeFilter.ALL) items else items.filter { titleMatchesFilter(it.type, filter) }

/**
 * [items] in the order [sort] asks for.
 *
 * Taste is passed only to [TitleSort.BEST_MATCH]. That is the whole rule of F5:
 * the viewer's taste is a TIEBREAK inside the relevance order, never an
 * ordering of its own - a "newest first" list that put a liked 1994 film above
 * a 2026 one would not be newest first.
 */
internal fun sortTitles(
    items: List<SearchTitleResult>,
    sort: TitleSort,
    lowerQuery: String,
    taste: TasteSignals.Snapshot = TasteSignals.Snapshot()
): List<SearchTitleResult> = when (sort) {
    TitleSort.BEST_MATCH -> items.sortedWith(searchTitleOrder(lowerQuery, taste))
    TitleSort.NEWEST -> items.sortedWith(newestTitleOrder(lowerQuery))
    TitleSort.AZ -> items.sortedWith(alphabeticalTitleOrder(lowerQuery))
}

/**
 * Newest first, with a title TMDB has no date for sorted LAST rather than
 * first: an unknown year is not the newest one, and letting it lead would put
 * the least informative cards at the head of the rail.
 *
 * Match quality breaks equal years, so two 2024 hits still read best-first;
 * the name is the last tiebreak, which keeps the order stable between
 * recompositions.
 */
internal fun newestTitleOrder(lowerQuery: String): Comparator<SearchTitleResult> =
    Comparator { a, b ->
        val yearA = a.year
        val yearB = b.year
        when {
            yearA != null && yearB == null -> -1
            yearA == null && yearB != null -> 1
            yearA != null && yearB != null && yearA != yearB -> yearB.compareTo(yearA)
            else -> {
                val score = searchMatchScore(lowerQuery, b.name)
                    .compareTo(searchMatchScore(lowerQuery, a.name))
                if (score != 0) score else a.name.lowercase().compareTo(b.name.lowercase())
            }
        }
    }

/**
 * A-Z by name, case-insensitively, with match quality as the tiebreak (the
 * spec's "name, then match score").
 */
internal fun alphabeticalTitleOrder(lowerQuery: String): Comparator<SearchTitleResult> =
    compareBy<SearchTitleResult> { it.name.trim().lowercase() }
        .thenByDescending { searchMatchScore(lowerQuery, it.name) }

/**
 * The Titles chip of the hero's count line, said in the terms of the current
 * filter: "Showing 24 of 148 titles" for the whole rail, "12 movies" when the
 * viewer narrowed to films.
 *
 * Only the ALL wording can quote the total: [total] is TMDB's count of BOTH
 * waves together, so "of 148" beside a movie count would be comparing a
 * filtered list with an unfiltered total.
 */
internal fun titleCountLabel(
    count: Int,
    filter: TitleTypeFilter,
    total: Int
): String = when (filter) {
    TitleTypeFilter.MOVIES -> if (count == 1) "1 movie" else "$count movies"
    TitleTypeFilter.SERIES -> "$count series"
    TitleTypeFilter.ALL -> when {
        total > count -> "Showing $count of $total titles"
        count == 1 -> "1 title"
        else -> "$count titles"
    }
}

/**
 * What the rail says when the type filter excludes everything that matched -
 * a real answer ("you searched, there are no series") and not the generic
 * "No matches found", which would be saying the search itself found nothing.
 */
internal fun titlesEmptyLabel(filter: TitleTypeFilter): String = when (filter) {
    TitleTypeFilter.MOVIES -> "No movies match."
    TitleTypeFilter.SERIES -> "No series match."
    TitleTypeFilter.ALL -> "No matches found."
}
