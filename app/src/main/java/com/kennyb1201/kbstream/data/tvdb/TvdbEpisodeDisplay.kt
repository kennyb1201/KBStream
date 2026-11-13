package com.kennyb1201.kbstream.data.tvdb

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode

/**
 * The displayed number of an episode, and the one an add-on is asked for
 * (spec 2026-11-13, F3).
 *
 * Kept apart from [TvdbOrders] (which has no Android or TMDB types) so the join
 * itself stays testable, and apart from every write path on purpose: nothing in
 * this file is allowed to change [ResolvedEpisode.episodeNumber] or
 * [ResolvedEpisode.streamId]. Those are TMDB's, and they are what watched marks,
 * resume points, history rows and tracker scrobbles are keyed on. A renumber
 * must never orphan any of them.
 */

/**
 * [episodes] with the chosen order's numbers on them, for display.
 *
 * A row with no mapping - and every row when no order is chosen - has its
 * display fields cleared rather than left as they were, so a cache entry written
 * under one order cannot bleed into a screen drawn under another.
 */
internal fun List<ResolvedEpisode>.withOrderNumbers(
    catalogue: TvdbCatalogue?,
    season: Int
): List<ResolvedEpisode> {
    val mapping = catalogue?.forTmdbSeason(season)
    if (mapping.isNullOrEmpty()) return map { it.withoutOrderNumbers() }
    return map { episode ->
        val orderEpisode = mapping[episode.episodeNumber]
        if (orderEpisode == null) {
            episode.withoutOrderNumbers()
        } else {
            episode.copy(
                displaySeason = orderEpisode.orderSeason,
                displayEpisode = orderEpisode.orderEpisode
            )
        }
    }
}

/** The episode number a viewer reads: the chosen order's, else TMDB's. */
internal fun ResolvedEpisode.shownEpisode(listedSeason: Int): Int =
    displayEpisode ?: episodeNumber

/** The season number a viewer reads: the chosen order's, else the one the list was loaded for. */
internal fun ResolvedEpisode.shownSeason(listedSeason: Int): Int =
    displaySeason ?: listedSeason

/** This row with any order numbering cleared - the shape of "no order chosen". */
internal fun ResolvedEpisode.withoutOrderNumbers(): ResolvedEpisode =
    if (displaySeason == null && displayEpisode == null) {
        this
    } else {
        copy(displaySeason = null, displayEpisode = null)
    }
