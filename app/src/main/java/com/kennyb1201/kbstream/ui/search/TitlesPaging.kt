package com.kennyb1201.kbstream.ui.search

/**
 * How many pages TMDB will serve for one query. Asking past this answers 400,
 * not an empty page, so the rail has to stop at the service's own ceiling rather
 * than at the number `total_pages` claims.
 */
internal const val TMDB_SEARCH_PAGE_LIMIT = 500

/**
 * One page of the Titles rail, after the per-page rules have run.
 *
 * [merged] is this page's movie + TV hits, already ranked by the screen's own
 * `mergeTitles` and already through the profile filter, so the pager below
 * never has to know about ranking or certification - it only appends and
 * counts. That split is what makes the paging rules testable without a device.
 *
 * [totalPages] is the service's page count for the query (the larger of the
 * movie and TV waves' answers), and [total] its match count (their sum: the
 * rail merges both waves, so its total is theirs together).
 */
internal data class TitlesPage(
    val number: Int,
    val merged: List<SearchTitleResult>,
    val totalPages: Int,
    val total: Int
)

/**
 * Everything the Titles rail knows after the pages it has asked for.
 *
 * The rail used to hold one page and stop: a broad query silently ended at 24
 * cards with no total and no way onward, which is the field report this exists
 * for. Paging is modeled here rather than inside the ViewModel so the four
 * rules the fix turns on - dedupe across pages, where loading stops, what a
 * failed page does, and what a new query does - are pinned by JVM tests.
 *
 * [loadedPage] counts pages that actually ANSWERED. A page whose whole request
 * failed does not advance it, so the retry asks for the same page again instead
 * of stepping over the results it never received.
 */
internal data class TitlesPagingState(
    val results: List<SearchTitleResult> = emptyList(),
    val loadedPage: Int = 0,
    val totalPages: Int = 0,
    val total: Int = 0,
    val error: String? = null
) {
    /**
     * Whether there is another page worth asking for. False both when TMDB says
     * this was the last one and when the service's 500-page ceiling is reached;
     * the rail then simply ends, which is what "no more" should look like.
     */
    val hasMore: Boolean
        get() = loadedPage < minOf(totalPages, TMDB_SEARCH_PAGE_LIMIT)

    /** The page a load-more asks for: the next one that has not answered yet. */
    val nextPage: Int get() = loadedPage + 1

    /**
     * Fold [page] in: append its hits that are not already on the rail, then
     * move the counters forward and clear any previous page error.
     *
     * Dedupe is by the TILE's identity (`type` + TMDB id), not the bare id.
     * TMDB numbers movies and series independently, so a movie and a series
     * share an id routinely; collapsing on the number alone would drop a real
     * title from the rail. Within one type this is exactly "deduped by TMDB
     * id", which is what the spec asks for.
     *
     * [total] never shrinks: a page where only one of the two waves answered
     * still knows only that wave's count, and the previous total is the better
     * answer until the other wave reports again.
     */
    fun absorb(page: TitlesPage): TitlesPagingState {
        val seen = results.mapTo(HashSet(results.size)) { titleTileKey(it) }
        val fresh = page.merged.filter { seen.add(titleTileKey(it)) }
        return TitlesPagingState(
            results = results + fresh,
            loadedPage = page.number,
            totalPages = maxOf(totalPages, page.totalPages),
            total = maxOf(total, page.total),
            error = null
        )
    }

    /**
     * A page that could not be fetched at all.
     *
     * Nothing else changes: the cards already on the rail stay, [loadedPage]
     * stays, and [hasMore] stays true - so the rail keeps page 1 on screen,
     * says what happened, and offers the same page again. A failure is never
     * allowed to read as "that was everything".
     */
    fun failed(message: String): TitlesPagingState = copy(error = message)
}

/** The rail identity two hits are compared by: the pair the rail keys a tile on. */
internal fun titleTileKey(result: SearchTitleResult): String =
    "${result.type}\u0001${result.id}"
