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
