package com.kennyb1201.kbstream.data.notifications

/**
 * When does an episode deserve a notification?
 *
 * The signal is broadcast-driven, not progress-driven: TMDB's
 * `last_episode_to_air` is the newest episode that has actually aired. A
 * show's *next-to-watch* pointer is deliberately NOT used — that one advances
 * the moment the user watches an episode, so notifying on it would fire
 * "new episode available" right after they finished the previous one.
 *
 * Pure and side-effect free so the decision is unit tested instead of
 * discovered on the TV.
 */
internal object NewEpisodeRules {

    /** "S3E4" for a valid season/episode pair, null when either is missing. */
    fun episodeKey(season: Int?, episode: Int?): String? {
        if (season == null || episode == null) return null
        if (season <= 0 || episode <= 0) return null
        return "S${season}E$episode"
    }

    /** Parsed (season, episode) from an [episodeKey], or null if malformed. */
    fun parseEpisodeKey(key: String): Pair<Int, Int>? {
        if (!key.startsWith("S")) return null
        val parts = key.substring(1).split("E", limit = 2)
        if (parts.size != 2) return null
        val season = parts[0].toIntOrNull() ?: return null
        val episode = parts[1].toIntOrNull() ?: return null
        if (season <= 0 || episode <= 0) return null
        return season to episode
    }

    /** True when [current] is a later episode than [previous]. */
    fun isLater(current: Pair<Int, Int>, previous: Pair<Int, Int>): Boolean =
        current.first > previous.first ||
            (current.first == previous.first && current.second > previous.second)

    /**
     * @param previousKey the episode this show aired at the last check, or
     *        null the first time we ever see the show
     * @param currentKey the newest episode that has aired now
     * @param airDateMs that episode's air date, null when unknown
     * @param nowMs current time
     *
     * First sightings never notify: on install (or after adding a show) every
     * followed show would otherwise fire at once. Unknown air dates never
     * notify either — "maybe it aired" is not worth a buzz.
     */
    fun shouldNotify(
        previousKey: String?,
        currentKey: String,
        airDateMs: Long?,
        nowMs: Long
    ): Boolean {
        if (previousKey == null || previousKey == currentKey) return false
        val previous = parseEpisodeKey(previousKey) ?: return false
        val current = parseEpisodeKey(currentKey) ?: return false
        if (!isLater(current, previous)) return false
        if (airDateMs == null) return false
        return airDateMs <= nowMs
    }

    /**
     * Stable notification id per show: a newer episode for a show the user
     * already has a notification for REPLACES it instead of stacking.
     */
    fun notificationId(showKey: String): Int = showKey.hashCode() and 0x7FFFFFFF

    /**
     * Series-shaped media type. The app speaks several dialects for the same
     * thing — history rows say "series" or "tv" (the TV launcher's spelling),
     * Simkl says "shows" — and "movie" must never match, or a film would be
     * asked for episodes.
     */
    fun isSeriesType(raw: String?): Boolean =
        raw?.trim()?.lowercase() in SERIES_TYPES

    private val SERIES_TYPES = setOf("series", "tv", "show", "shows", "anime")
}
