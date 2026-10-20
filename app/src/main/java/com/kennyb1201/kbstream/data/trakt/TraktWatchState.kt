package com.kennyb1201.kbstream.data.trakt

import kotlin.math.roundToInt

/**
 * The id forms a tracker snapshot and the app's own ids have in common.
 *
 * The app addresses the same title as `tt123`, `imdb:tt123`, `tmdb:456`, or a
 * bare `456` depending on which rail it came from (addon metas are IMDb-based,
 * TMDB discover rows are numeric). Trakt answers with all of them at once, so
 * both sides are canonicalized through this one function before they are
 * compared - otherwise a show watched on Trakt would only ever light up the
 * rails that happened to use the same id flavor, which is exactly the twin-id
 * bug the local history helpers already exist to prevent.
 */
object TraktIdKeys {

    /**
     * One id in its canonical form, or null when there is no id at all.
     *
     * `imdb:` is a prefix on the same value as a bare `tt…` id, and a bare
     * number is TMDB's id (the app's numeric ids are TMDB ids everywhere except
     * the season/episode fields).
     */
    fun canonical(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lower = value.lowercase()
        return when {
            lower.startsWith("imdb:") ->
                canonical(value.substringAfter(':'))

            lower.startsWith("tt") ->
                value.substringBefore(':').trim().lowercase()

            lower.startsWith("tmdb:") -> {
                val rest = value.substringAfter(':').trim()
                rest.toIntOrNull()?.let { "tmdb:$it" }
                    ?: canonical(rest)
            }

            lower.startsWith("trakt:") -> {
                val rest = value.substringAfter(':').trim()
                rest.takeIf { it.isNotEmpty() }?.let { "trakt:$it" }
            }

            value.all(Char::isDigit) -> "tmdb:$value"

            lower.startsWith("slug:") ->
                "slug:" + value.substringAfter(':').trim().lowercase()

            // Trakt's own slugs are titles ("breaking-bad"), so an unrecognised
            // value is one of those rather than an id we can match on.
            else -> "slug:" + lower
        }
    }

    /** Every form Trakt's `ids` object gives one title. */
    fun from(ids: TraktIds?): Set<String> {
        if (ids == null) return emptySet()
        val keys = linkedSetOf<String>()
        ids.imdb?.let(::canonical)?.let(keys::add)
        ids.tmdb?.let { keys += "tmdb:$it" }
        ids.trakt?.let { keys += "trakt:$it" }
        ids.slug?.let(::canonical)?.let(keys::add)
        return keys
    }
}

/**
 * One show's watched state, as Trakt reports it.
 *
 * [idKeys] is every canonical id form the show answers to; [episodes] is the
 * set of (season, episode) pairs Trakt has a play for. Completed-episode state
 * is the only thing the watched union needs, because the tracker knows which
 * episodes were watched rather than just that the show was started.
 */
data class TraktShowWatch(
    val idKeys: Set<String> = emptySet(),
    val episodes: Set<Pair<Int, Int>> = emptySet()
)

/**
 * The account's watched history, in the shape this app reads it.
 *
 * Deliberately not a wire model: [movieKeys] and [startedShowKeys] are what the
 * watched badges test membership against, and [watchedEpisodesFor] is what
 * Continue Watching unions with Simkl and MDBList.
 */
data class TraktWatchedSnapshot(
    val movieKeys: Set<String> = emptySet(),
    val shows: List<TraktShowWatch> = emptyList()
) {
    val startedShowKeys: Set<String>
        get() = shows.flatMapTo(linkedSetOf()) { it.idKeys }

    val isEmpty: Boolean
        get() = movieKeys.isEmpty() && shows.isEmpty()

    /**
     * The watched episodes of the show addressed by any of [showKeys].
     *
     * Returns an empty set both when the show is unknown and when none of its
     * episodes is watched; callers already distinguish "the tracker did not
     * answer" from "nothing watched" by catching the fetch, not by reading this
     * return value (see HomeViewModel's trackerReadFailed).
     */
    fun watchedEpisodesFor(showKeys: Collection<String>): Set<Pair<Int, Int>> {
        if (shows.isEmpty() || showKeys.isEmpty()) return emptySet()
        val wanted = showKeys.mapNotNull(TraktIdKeys::canonical).toSet()
        if (wanted.isEmpty()) return emptySet()
        return shows.asSequence()
            .filter { row -> row.idKeys.any { it in wanted } }
            .flatMap { it.episodes.asSequence() }
            .toSet()
    }
}

/**
 * Mapping from Trakt's `/sync/watched` rows to [TraktWatchedSnapshot].
 *
 * Pure so the id-twining and season flattening are pinned by a JVM test rather
 * than a device: the wire shape nests episodes under seasons, while every
 * consumer of the union wants flat (season, episode) pairs.
 */
object TraktWatchRules {

    fun showRow(
        ids: TraktIds?,
        seasons: List<TraktWatchedSeason>?
    ): TraktShowWatch {
        val episodes = buildSet {
            seasons.orEmpty().forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    add(season.number to episode.number)
                }
            }
        }
        return TraktShowWatch(
            idKeys = TraktIdKeys.from(ids),
            episodes = episodes
        )
    }

    fun snapshot(
        shows: List<TraktWatchedShow>?,
        movies: List<TraktWatchedMovie>?
    ): TraktWatchedSnapshot {
        val movieKeys = buildSet {
            movies.orEmpty().forEach { watched ->
                TraktIdKeys.from(watched.movie?.ids).forEach(::add)
            }
        }
        val rows = shows.orEmpty()
            .map { watched ->
                showRow(ids = watched.show?.ids, seasons = watched.seasons)
            }
            .filter { it.idKeys.isNotEmpty() || it.episodes.isNotEmpty() }
        return TraktWatchedSnapshot(movieKeys = movieKeys, shows = rows)
    }
}

/**
 * Scrobble progress, in the unit Trakt expects.
 *
 * Trakt's percentage is 0-100 like Simkl's and MDBList's, but it is sent as a
 * whole number: MDBList's fractional percentage was rejected as a 400, and
 * there is no gain here in carrying decimals the endpoint would have to round
 * anyway. A title whose runtime is still unknown reports 0 - the start event -
 * rather than a made-up position.
 */
object TraktScrobbleRules {

    fun progressPercent(raw: Double): Int =
        raw.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, 100) ?: 0

    fun isMovie(parentType: String): Boolean =
        parentType.trim().equals("movie", ignoreCase = true)

    fun isShow(parentType: String): Boolean =
        parentType.trim().lowercase() in setOf("series", "show", "tv")
}
