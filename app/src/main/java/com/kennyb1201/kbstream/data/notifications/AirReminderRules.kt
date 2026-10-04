package com.kennyb1201.kbstream.data.notifications

/**
 * The bookkeeping an air-date reminder needs that is not already the
 * new-episode check's job.
 *
 * Timing and once-only delivery are [NewEpisodeRules]'s (a flagged show is
 * checked exactly like a watched one, and announced once per episode), so the
 * only new decision here is *which id* identifies the flagged show. It has to
 * be a spelling [com.kennyb1201.kbstream.data.tmdb.TmdbRepository] can resolve
 * — the same ids the detail deep link understands — or the reminder would be
 * stored under a key that never matches a fetched show.
 *
 * Pure, so the choice is unit tested rather than discovered on the TV.
 */
internal object AirReminderRules {

    /**
     * The stable show id to flag and to check: the IMDb id when the title
     * carries one (it is the spelling shared across the app's catalogs and the
     * one the deep link resolves most widely), otherwise a `tmdb:` id. Null when
     * the title has neither, in which case no reminder can be offered.
     */
    fun showIdFor(imdbId: String?, tmdbId: Int?): String? {
        imdbId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        tmdbId?.takeIf { it > 0 }?.let { return "tmdb:$it" }
        return null
    }
}
