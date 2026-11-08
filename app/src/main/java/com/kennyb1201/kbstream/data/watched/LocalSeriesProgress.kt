package com.kennyb1201.kbstream.data.watched

/**
 * Pure "is this show caught up?" rule behind the local eye badge.
 *
 * The eye badge marks a series the user has started but not finished. Locally
 * that used to be "has a resume row"; it now also covers "has completed
 * episodes", to match Continue Watching. Without this rule, though, a show the
 * user has fully watched would keep an eye forever, so the completed-episode
 * half has to be able to say "nothing left to watch".
 *
 * The answer is deliberately conservative: only a certain "caught up" hides
 * the eye. Anything unknown (no aired frontier, a missing per-season count)
 * reads as unfinished, so the worst case is the badge the show would have had
 * before.
 */
object LocalSeriesProgress {

    /**
     * Whether the LAST AIRED episode is in [completedEpisodes] - the Upcoming
     * rail's caught-up test.
     *
     * The full-run rule above ([isCaughtUp]) asks for every episode of every
     * season up to the airing frontier, which is the right question where it is
     * used (the eye badge and the detail page read this profile's own rows, and
     * a badge that says "caught up" on partial evidence would lie). It is the
     * wrong question for Upcoming: the evidence that rail has from a tracker or
     * from an old history row is a SET of (season, episode) pairs, and the sets
     * are not runs. A show finished months ago keeps a handful of rows at best -
     * MobLand held (2,8) and Tulsa King (4,4), while the other 17 and 33 aired
     * episodes were gone - so the full-run bar rejected both shows even though
     * the decisive evidence, the last aired episode watched, was right there.
     *
     * The rail only needs to know whether there is anything left to watch before
     * the next one airs, and the last AIRED episode being watched is exactly
     * that: the next episode is news, an earlier gap is Continue Watching's
     * business. The derivation that names the next episode tolerates a sparse
     * set by construction (see `firstUnairedEpisode`), so nothing else has to
     * change.
     *
     * Deliberately conservative in the same way as [isCaughtUp]: an empty set,
     * an unknown airing frontier, or a non-positive frontier all read as NOT
     * caught up, so the worst case is a card the rail already did not show.
     */
    fun isCaughtUpOnLastAired(
        completedEpisodes: Set<Pair<Int, Int>>,
        lastAiredSeason: Int?,
        lastAiredEpisode: Int?
    ): Boolean {
        if (completedEpisodes.isEmpty()) return false

        val season = lastAiredSeason ?: return false
        val episode = lastAiredEpisode ?: return false
        if (season < 1 || episode < 1) return false

        return season to episode in completedEpisodes
    }

    /**
     * Whether every episode of every season up to the last AIRED episode is in
     * [completedEpisodes].
     *
     * @param completedEpisodes (season, episode) pairs the user has watched.
     * @param seasonEpisodeCounts TMDB's declared episode count per season,
     *   keyed by season number. Season 0 / specials must be excluded by the
     *   caller.
     * @param lastAiredSeason the season of the last aired episode, or null
     *   when unknown.
     * @param lastAiredEpisode the episode number of the last aired episode, or
     *   null when unknown.
     */
    fun isCaughtUp(
        completedEpisodes: Set<Pair<Int, Int>>,
        seasonEpisodeCounts: Map<Int, Int>,
        lastAiredSeason: Int?,
        lastAiredEpisode: Int?
    ): Boolean {
        if (completedEpisodes.isEmpty()) return false

        val frontierSeason = lastAiredSeason ?: return false
        val frontierEpisode = lastAiredEpisode ?: return false
        if (frontierSeason < 1 || frontierEpisode < 1) return false

        for (season in 1..frontierSeason) {
            val declared = seasonEpisodeCounts[season]

            val episodesToCheck =
                if (season == frontierSeason) {
                    /*
                     * The declared count for the currently-airing season still
                     * includes scheduled episodes, so only require up to the
                     * last AIRED one. When TMDB gives no count for it, the
                     * aired frontier is the whole season so far.
                     */
                    minOf(declared ?: frontierEpisode, frontierEpisode)
                } else {
                    /*
                     * A finished season's declared count IS its aired count.
                     * Without it we cannot prove the season is complete, and
                     * a guess here could hide the eye on an unfinished show.
                     */
                    declared ?: return false
                }

            if (episodesToCheck < 1) return false

            for (episode in 1..episodesToCheck) {
                if (season to episode !in completedEpisodes) {
                    return false
                }
            }
        }

        return true
    }
}
