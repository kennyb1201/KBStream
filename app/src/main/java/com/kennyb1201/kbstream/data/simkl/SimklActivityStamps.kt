package com.kennyb1201.kbstream.data.simkl

/**
 * What `/sync/activities` said, split by the part of the account it describes
 * (spec 2026-11-21, rule 5: delta where the API offers it).
 *
 * The activities response was read through ONE field, `all` - "the newest stamp
 * anywhere" - so a rating changed on a movie re-synced the show markers too, and
 * a show's episode ticked re-fetched the movie set. Both halves are separate
 * reads here, and each carries its own stamps, so the refresh can fetch the leg
 * that moved and leave the other one alone.
 *
 * Simkl's shape (documented, `GET /sync/activities`):
 * ```
 * { "all": "…",
 *   "tv_shows": { "all": "…", "completed": "…", "watching": "…", … },
 *   "anime":    { "all": "…", "completed": "…", "watching": "…", … },
 *   "movies":   { "all": "…", "completed": "…", … } }
 * ```
 * Every block is optional here: a payload that predates a block, or an account
 * with nothing in it, must fall back to `all` rather than to "nothing moved".
 */
internal data class SimklActivityStamps(
    val all: String? = null,
    val movies: String? = null,
    val moviesCompleted: String? = null,
    val shows: String? = null,
    val showsCompleted: String? = null,
    val showsWatching: String? = null,
    val anime: String? = null,
    val animeCompleted: String? = null,
    val animeWatching: String? = null
) {

    /** The account-wide stamps this record says nothing about. */
    private val hasMovieStamps: Boolean
        get() = movies != null || moviesCompleted != null

    private val hasShowStamps: Boolean
        get() = shows != null || showsCompleted != null || showsWatching != null ||
            anime != null || animeCompleted != null || animeWatching != null

    /**
     * The wire form, for the per-profile store.
     *
     * Nine nullable strings in fixed order, joined by the unit separator - the
     * same "no escaping needed, nothing can occur in a timestamp" reasoning the
     * pinned snapshot's tabs use, with a character that cannot appear in JSON
     * either.
     */
    fun wire(): String = listOf(
        all,
        movies,
        moviesCompleted,
        shows,
        showsCompleted,
        showsWatching,
        anime,
        animeCompleted,
        animeWatching
    ).joinToString(SEPARATOR.toString()) { it.orEmpty() }

    companion object {
        private const val SEPARATOR = '\u0001'

        /** The stamps in [response], or null when it carried no usable `all`. */
        fun of(response: SimklActivitiesResponse): SimklActivityStamps? {
            val all = response.all?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return SimklActivityStamps(
                all = all,
                movies = response.movies?.all?.trim()?.takeIf { it.isNotBlank() },
                moviesCompleted = response.movies?.completed?.trim()?.takeIf { it.isNotBlank() },
                shows = response.tvShows?.all?.trim()?.takeIf { it.isNotBlank() },
                showsCompleted = response.tvShows?.completed?.trim()?.takeIf { it.isNotBlank() },
                showsWatching = response.tvShows?.watching?.trim()?.takeIf { it.isNotBlank() },
                anime = response.anime?.all?.trim()?.takeIf { it.isNotBlank() },
                animeCompleted = response.anime?.completed?.trim()?.takeIf { it.isNotBlank() },
                animeWatching = response.anime?.watching?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        /** Reads [wire] back; null when the record is not this format. */
        fun parse(wire: String?): SimklActivityStamps? {
            val parts = wire?.split(SEPARATOR) ?: return null
            if (parts.size != 9) return null
            fun part(index: Int) = parts[index].takeIf { it.isNotBlank() }
            return SimklActivityStamps(
                all = part(0),
                movies = part(1),
                moviesCompleted = part(2),
                shows = part(3),
                showsCompleted = part(4),
                showsWatching = part(5),
                anime = part(6),
                animeCompleted = part(7),
                animeWatching = part(8)
            )
        }
    }

    /**
     * Which halves moved since [saved].
     *
     * The rule is deliberately conservative in ONE direction only: when this
     * build cannot tell - no saved record, or a payload without the section
     * blocks - both halves count as changed. Skipping a fetch that should have
     * happened leaves missing checkmarks with nothing to correct them, while a
     * fetch that need not have happened costs one read.
     *
     * The cheap gate comes first: when `all` is identical the account did not
     * change at all, and the answer is "neither" without looking further - the
     * one comparison the old code made.
     */
    fun changesSince(saved: SimklActivityStamps?): SimklSectionChanges {
        if (saved == null) return SimklSectionChanges.all
        if (all != null && saved.all == all) return SimklSectionChanges.none
        // A section whose stamps this build does not have on EITHER side falls
        // back to `all`, which has already moved by the line above.
        val moviesMoved = if (hasMovieStamps && saved.hasMovieStamps) {
            movies != saved.movies || moviesCompleted != saved.moviesCompleted
        } else {
            true
        }
        val showsMoved = if (hasShowStamps && saved.hasShowStamps) {
            shows != saved.shows ||
                showsCompleted != saved.showsCompleted ||
                showsWatching != saved.showsWatching ||
                anime != saved.anime ||
                animeCompleted != saved.animeCompleted ||
                animeWatching != saved.animeWatching
        } else {
            true
        }
        return SimklSectionChanges(movies = moviesMoved, shows = showsMoved)
    }
}

/** Which of the two marker sets a change touched. */
internal data class SimklSectionChanges(val movies: Boolean, val shows: Boolean) {

    val any: Boolean get() = movies || shows

    companion object {
        val none = SimklSectionChanges(movies = false, shows = false)

        val all = SimklSectionChanges(movies = true, shows = true)
    }
}
