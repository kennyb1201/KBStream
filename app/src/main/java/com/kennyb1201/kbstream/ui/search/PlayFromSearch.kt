package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult

/**
 * A spoken query resolved to the one title the viewer meant. [type] is the
 * launcher/Detail convention (`movie` / `tv`), the same pair
 * [com.kennyb1201.kbstream.data.tv.TvLauncherPublisher] deep links carry.
 */
internal data class PlayFromSearchMatch(
    val type: String,
    val tmdbId: Int,
    val title: String
)

/**
 * Picks the title a "play <X>" is asking for, from what TMDB's two search
 * endpoints returned.
 *
 * Two rules, in this order, because they are the two things that decide whether
 * an assistant command does the obvious thing:
 *
 *  1. **Artwork first.** A result with no poster is, in practice, a stray record
 *     — an unaired pilot, a duplicate, a TV special filed under a feature title
 *     — and opening one of those instead of the show the viewer named is the
 *     failure mode that makes "play Severance" feel broken. The filter is
 *     dropped when nothing has art, so an obscure title is still playable.
 *  2. **Rank over popularity, within the artwork pool.** TMDB already ordered
 *     each list by relevance, so a first result at rank 0 beats a blockbuster at
 *     rank 3 — voting a famous title to the top would answer "play The Office"
 *     with the wrong one of its two dozen namesakes. Votes only break a tie
 *     between the two lists (a movie and a show both at rank 0), and the movie
 *     wins ties after that, since a bare title is more often a film than a
 *     series.
 *
 * Returns null when neither endpoint produced anything usable, which the caller
 * answers by opening the Search screen with the query instead — a wrong guess
 * is worse than a list.
 */
internal fun pickPlayFromSearchMatch(
    movies: List<TmdbSearchTitleResult>,
    shows: List<TmdbSearchTitleResult>
): PlayFromSearchMatch? {
    val candidates = buildList {
        movies.forEachIndexed { index, item -> add(Candidate("movie", index, item)) }
        shows.forEachIndexed { index, item -> add(Candidate("tv", index, item)) }
    }.filter { it.item.spokenTitle().isNotBlank() }
    if (candidates.isEmpty()) return null

    val withArt = candidates.filter { !it.item.posterPath.isNullOrBlank() }
    val pool = withArt.ifEmpty { candidates }

    val best = pool.minWithOrNull(
        compareBy(
            { it.position },
            { -(it.item.voteAverage ?: 0.0) },
            { if (it.type == "movie") 0 else 1 }
        )
    ) ?: return null

    return PlayFromSearchMatch(best.type, best.item.id, best.item.spokenTitle())
}

/** The result's name, whichever endpoint it came from. */
private fun TmdbSearchTitleResult.spokenTitle(): String =
    (title ?: name).orEmpty().trim()

private data class Candidate(
    val type: String,
    val position: Int,
    val item: TmdbSearchTitleResult
)
