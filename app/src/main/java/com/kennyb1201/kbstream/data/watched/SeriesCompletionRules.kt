package com.kennyb1201.kbstream.data.watched

/**
 * The CHECKMARK rule for a series, from this profile's own history.
 *
 * The tile has always drawn the check when `isWatched` is true and the eye when
 * `isPartiallyWatched` is true, check winning - and the resolution only ever
 * awarded a series' check from a manual mark or Simkl's completed set. Simkl's
 * own rule counts every episode including unaired ones, so a still-airing show
 * can never earn it (by design), and a show finished episode by episode in local
 * history could only ever lose its eye, never gain a check (spec 2026-11-17).
 *
 * This is the missing local half, and it is deliberately narrower than "nothing
 * left to watch right now":
 *
 *  - the show must be FINISHED in TMDB's sense (`Ended` / `Canceled`), and
 *  - every episode up to the last AIRED one must be in local completed rows
 *    ([LocalSeriesProgress.isCaughtUp], the same proof the eye has always used).
 *
 * So a show the viewer is merely caught up on, while it is still airing, keeps
 * its eye - that is the badge Kenny asked to keep - and if a finished show is
 * later revived with new episodes, the proof stops holding and the eye returns.
 *
 * Pure on purpose: no database, no network, no Compose, so the JVM harness can
 * pin every leg of the rule.
 */
object SeriesCompletionRules {

    /**
     * Whether TMDB says the show is over.
     *
     * Worded exactly as the Detail page words it: `Ended` and `Canceled` are the
     * two finished states, and everything else - `Returning Series`, `In
     * Production`, `Planned`, `Pilot`, an unknown string, null - is not over.
     * `Cancelled` is accepted as the other spelling because TMDB has used both
     * and a missed check is a badge that never appears.
     */
    fun isFinishedStatus(status: String?): Boolean {
        val normalized = status?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized.equals("Ended", ignoreCase = true) ||
            normalized.equals("Canceled", ignoreCase = true) ||
            normalized.equals("Cancelled", ignoreCase = true)
    }

    /**
     * Whether a locally-watched series is COMPLETED: finished, and every aired
     * episode watched.
     *
     * Every argument comes from one TMDB detail plus the completed rows already
     * read for this show, so the answer costs nothing extra once both are in
     * hand. Unknowns stay unfinished - a series whose seasons or airing frontier
     * are missing reads as NOT completed, which keeps the eye it already had
     * rather than painting a check on a guess.
     */
    fun isCompleted(
        status: String?,
        completedEpisodes: Set<Pair<Int, Int>>,
        seasonEpisodeCounts: Map<Int, Int>,
        lastAiredSeason: Int?,
        lastAiredEpisode: Int?
    ): Boolean {
        if (!isFinishedStatus(status)) return false
        return LocalSeriesProgress.isCaughtUp(
            completedEpisodes = completedEpisodes,
            seasonEpisodeCounts = seasonEpisodeCounts,
            lastAiredSeason = lastAiredSeason,
            lastAiredEpisode = lastAiredEpisode
        )
    }
}
