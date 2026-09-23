package com.kennyb1201.kbstream.ui.detail

/**
 * Which season the Detail screen opens on, given the season the viewer's
 * history points at.
 *
 * Split out of [DetailViewModel] so the rule can be read and tested on its own:
 * getting it wrong is the difference between landing on the episode you were
 * watching and on a season you have never seen, and it has been wrong twice —
 * once leaving season 1 shows with an empty episode row, then by opening
 * everything on season 2.
 */
internal object DetailSeasonTarget {

    /**
     * [startSeason] is the season the history points at: the newest season the
     * viewer has watched something in, and the show's first season when there
     * is no history at all.
     *
     * That season is kept unless it is FINISHED — every episode of it watched —
     * in which case the walk moves on to the first later season that is
     * released and still has something unwatched in it. Two guards make the
     * difference between those cases:
     *
     *  - the start season is checked before any walking happens, because a
     *    later season almost always has unwatched episodes (it is new) and
     *    simply asking "is there anything unwatched after this season?" sends
     *    every show forward by one — including shows with no history at all,
     *    whose start season is just the first one; and
     *  - a season with no reliable episode list counts as unfinished, so an
     *    unknown shape can never be skipped.
     *
     * @param episodeNumbers the episode numbers TMDB lists for a season, empty
     *   when it has no usable list.
     * @param isWatched whether one episode is already watched.
     * @param isReleased whether a season has started airing; announced-only
     *   seasons are never a target.
     */
    fun pick(
        startSeason: Int,
        seasons: List<Int>,
        episodeNumbers: (season: Int) -> List<Int>,
        isWatched: (season: Int, episode: Int) -> Boolean,
        isReleased: (season: Int) -> Boolean
    ): Int {
        val current = episodeNumbers(startSeason)
        if (current.isEmpty() || current.any { !isWatched(startSeason, it) }) {
            return startSeason
        }

        for (next in seasons.filter { it > startSeason }.sorted()) {
            if (!isReleased(next)) continue

            val episodes = episodeNumbers(next)
            if (episodes.isEmpty()) {
                // No reliable episode list: opening the season directly is
                // still better than replaying a finished one.
                return next
            }

            if (episodes.any { !isWatched(next, it) }) {
                return next
            }
        }

        // Everything after the watched season is either fully watched or
        // unreleased: stay on the latest watched season.
        return startSeason
    }
}
