package com.kennyb1201.kbstream.data.mdblist

/**
 * Exactly which watched keys one accepted write moved.
 *
 * Every history write used to answer with the blanket
 * [MdbListClient.invalidateWatchedSnapshot]: the memory copy AND its disk row
 * were deleted, so the next read re-downloaded the ENTIRE paginated history -
 * up to a request per 1,000 items - to learn something the write itself already
 * knew, namely the ids it had just sent. That happened on every mark, every
 * unmark and every completed playback, on a key three devices share.
 *
 * The delta is the write's own ids in the snapshot's vocabulary (see
 * [MdbListWatchedSnapshot]): `tt123` / `tmdb:456` for movies and shows,
 * `tt123:S:E` / `tmdb:456:S:E` for episodes. Applying it is a set edit on the
 * copy already in hand - no request, and the keys that did not move keep the
 * age they had.
 *
 * A whole-show REMOVAL takes its episode keys with it: `/sync/watched/remove`
 * with ids-only clears every episode of the show server-side, so the local
 * snapshot must lose them or the show would keep painting eye badges from
 * episodes that are no longer on record.
 */
internal class MdbListSnapshotWrite(
    val addMovies: Set<String> = emptySet(),
    val addShows: Set<String> = emptySet(),
    val addEpisodes: Set<String> = emptySet(),
    val removeMovies: Set<String> = emptySet(),
    val removeShows: Set<String> = emptySet(),
    val removeEpisodes: Set<String> = emptySet()
) {

    /** True when no set moved, i.e. there is nothing to write anywhere. */
    val isEmpty: Boolean
        get() = addMovies.isEmpty() && addShows.isEmpty() && addEpisodes.isEmpty() &&
            removeMovies.isEmpty() && removeShows.isEmpty() && removeEpisodes.isEmpty()

    /**
     * The snapshot this write produces. The input is not mutated: the client
     * swaps the cached object so the watched-episode index (which is keyed on
     * the snapshot instance) rebuilds.
     */
    fun applyTo(snapshot: MdbListWatchedSnapshot): MdbListWatchedSnapshot {
        val movies = snapshot.movieKeys.toMutableSet()
        val shows = snapshot.startedShowKeys.toMutableSet()
        val episodes = snapshot.episodeKeys.toMutableSet()

        movies += addMovies
        movies -= removeMovies
        shows += addShows
        shows -= removeShows
        episodes += addEpisodes
        episodes -= removeEpisodes

        // Both id forms of a removed show, so an episode recorded under the
        // other one is cleared too.
        val removedShowPrefixes = removeShows.map { "$it:" }
        if (removedShowPrefixes.isNotEmpty()) {
            episodes.removeAll { key -> removedShowPrefixes.any { key.startsWith(it) } }
        }

        return MdbListWatchedSnapshot(
            movieKeys = movies,
            startedShowKeys = shows,
            episodeKeys = episodes
        )
    }
}

/**
 * The snapshot keys a write's ids name: `tt123` and/or `tmdb:456`, optionally
 * suffixed (`":$season:$number"`).
 *
 * The filters are the download walk's own ([MdbListClient.addKey]): an imdb id
 * is only a key when it is a `tt` id, and a tmdb id only when it is positive.
 * A write whose id would not appear in the history must not invent a key.
 */
internal fun mdbListIdKeys(
    imdbId: String?,
    tmdbId: Int?,
    suffix: String = ""
): Set<String> {
    val keys = linkedSetOf<String>()
    imdbId?.trim()?.takeIf { it.startsWith("tt") }?.let { keys += it + suffix }
    tmdbId?.takeIf { it > 0 }?.let { keys += "tmdb:$it" + suffix }
    return keys
}

/** The episode keys one season/episode list of a show moves. */
internal fun mdbListEpisodeKeys(
    imdbId: String?,
    tmdbId: Int?,
    season: Int?,
    episodes: List<Int>
): Set<String> {
    if (season == null || season < 0) return emptySet()
    val keys = linkedSetOf<String>()
    episodes.filter { it >= 0 }.forEach { number ->
        keys += mdbListIdKeys(imdbId, tmdbId, suffix = ":$season:$number")
    }
    return keys
}

/** A single episode's keys, for the stop/push paths that carry one number. */
internal fun mdbListEpisodeKeys(
    imdbId: String?,
    tmdbId: Int?,
    season: Int?,
    episode: Int?
): Set<String> = mdbListEpisodeKeys(
    imdbId,
    tmdbId,
    season,
    listOfNotNull(episode)
)

/** MDBList marks a scrobbled title watched at or above this percentage. */
internal const val MDBLIST_WATCHED_AT_COMPLETE = 80.0

/**
 * Whether a stop at [progress] (whole percent, as the player passes it)
 * completed the title.
 *
 * A partial stop changes NO watched key - the API keeps it as a paused
 * session - so it must not touch the snapshot or its disk row. Only a stop that
 * completed something is a history write.
 */
internal fun mdbListStopCompletes(progress: Double): Boolean =
    progress >= MDBLIST_WATCHED_AT_COMPLETE
