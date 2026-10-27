package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview

/**
 * The rule that keeps a rank list out of the Search screen's add-on rails.
 *
 * Search results are meant to be answers to the query: the add-on's own search
 * results and KBStream's TMDB waves. A ranking catalog ("Top 10 on Netflix",
 * "Trending", "Popular") answers every request with the same list whatever the
 * term, so its rail is not a search answer at all - it is the catalog's browse
 * page wearing the generic "Movies"/"Series" label a regular catalog gets on
 * the rail header, which is why the report read as built-in "Top Ten" rails
 * inside search results (2026-10-27: Kenny's AIOStreams Top Ten lists back in
 * search).
 *
 * The guard compares the catalog's answer to `search=<query>` with its own
 * unfiltered page, and everything rests on that comparison being strict enough
 * to mean "the query was ignored" and loose enough never to throw away a rail
 * that really did filter. The comparison used to demand the two lists be
 * identical *as ordered lists*, which failed for reasons that have nothing to
 * do with the query: a ranking rotates, the two calls are seconds apart, and a
 * single repositioned entry defeated the whole check - after which the verdict
 * ("this catalog honours the query") was cached for the rest of the session.
 *
 * So the comparison is by title identity, in any order, and tolerant of the one
 * legitimate way a search page is shorter than a browse page: a catalog whose
 * search endpoint returns a smaller page than its browse endpoint still echoes
 * the *head* of the same ranking. A catalog that filtered the query passes
 * neither test - it returns fewer titles than it holds, drawn from anywhere in
 * its list rather than the first N of it.
 *
 * Every doubt here resolves to `false`. A rank rail that slips through is a
 * visible wrong row; a rail wrongly dropped is a search result the viewer never
 * learns existed, which is the failure this screen has already been taught
 * costs more (the per-add-on rail cap's comment records the "AI Search rail
 * disappeared" bug for the same reason).
 */

/**
 * The smallest echo worth acting on.
 *
 * A one-title "search" that happens to be the head of the ranking is also
 * exactly what a real query for that title looks like - searching a chart's own
 * number one - so a single hit is never treated as an echo.
 */
internal const val CATALOG_ECHO_MIN_HITS = 2

/** The identity two titles are compared by: the pair the screen keys a row on. */
private fun identity(meta: MetaPreview): String = "${meta.type}:${meta.id}"

/**
 * True when [hits] (the answer to `search=<query>`) is just the head of
 * [browse] (the catalog's own unfiltered page): the same titles, in any order,
 * with the search page no longer than the browse page.
 *
 * An empty answer on either side is not an echo - a catalog that returned
 * nothing answered the query, and there is nothing to compare against when its
 * browse page is empty.
 */
internal fun catalogEchoesItsBrowseList(
    browse: List<MetaPreview>,
    hits: List<MetaPreview>
): Boolean {
    if (browse.isEmpty() || hits.isEmpty()) return false
    if (hits.size < CATALOG_ECHO_MIN_HITS) return false
    // A search page longer than the browse page is not a page of it.
    if (hits.size > browse.size) return false
    val head = browse.take(hits.size).map { identity(it) }.toSet()
    val answered = hits.map { identity(it) }.toSet()
    return head == answered
}
