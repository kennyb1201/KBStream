package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult

/**
 * Kids Mode for the two search surfaces that run OUTSIDE the app's own screens.
 *
 * Every in-app search path vets its TMDB wave against the active profile's
 * rating ceiling (see SearchViewModel, and the Detail screen's own gate behind
 * it). Two do not go through those screens at all:
 *
 *  - the TV launcher's global search, whose rows come from
 *    [SearchSuggestionsProvider] and are drawn by the SYSTEM, on the launcher,
 *    where nothing of this app's can reach them; and
 *  - a voice / system `MEDIA_PLAY_FROM_SEARCH`, which goes straight from
 *    [VoiceSearchActivity] to a deep link.
 *
 * Both resolve a written or spoken query against TMDB, so both have to apply the
 * same ceiling themselves - a kids profile's launcher (or a spoken "play X") is
 * otherwise a way around every gate in the app. One implementation, so the two
 * cannot disagree about what the ceiling means.
 */
internal suspend fun kidsFilteredTmdbSearch(
    tmdb: TmdbRepository,
    movies: List<TmdbSearchTitleResult>,
    shows: List<TmdbSearchTitleResult>
): Pair<List<TmdbSearchTitleResult>, List<TmdbSearchTitleResult>> {
    // The ceiling is off for most profiles: never spend a lookup on one that has
    // none (this runs inside a provider's query call, under a deadline).
    if (tmdb.kidsMaxAge() == null) return movies to shows
    val allowed = tmdb.kidsFilterMetas(tmdbSearchMetas(movies, shows)).toSet()
    return movies.filter { metaFor("movie", it) in allowed } to
        shows.filter { metaFor("series", it) in allowed }
}

/**
 * The identities a TMDB search response is vetted under, in response order.
 *
 * `tmdb:<id>` is the spelling the in-app search gives the same results (see
 * SearchViewModel.tmdbItem), and it is what [TmdbRepository.kidsFilterMetas]
 * fetches directly instead of resolving through TMDB's /find. Pure, so the
 * mapping is testable: a wrong id or type here would check the ceiling against a
 * different title and let the wrong ones through.
 */
internal fun tmdbSearchMetas(
    movies: List<TmdbSearchTitleResult>,
    shows: List<TmdbSearchTitleResult>
): List<MetaPreview> = movies.map { metaFor("movie", it) } + shows.map { metaFor("series", it) }

/** One result's identity. Deterministic, so the same result maps to the same meta. */
private fun metaFor(type: String, item: TmdbSearchTitleResult): MetaPreview =
    MetaPreview(
        id = "tmdb:${item.id}",
        type = type,
        name = (item.title ?: item.name).orEmpty().trim()
    )
