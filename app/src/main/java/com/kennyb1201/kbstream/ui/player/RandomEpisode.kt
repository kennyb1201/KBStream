package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * A randomly chosen, already-aired episode of a show.
 *
 * [episodeCount] is the size of that season's FULL episode list (aired or not),
 * matching what the detail screen passes as `totalEpisodesInSeason`.
 */
data class RandomEpisodePick(
    val season: Int,
    val episode: Int,
    val name: String?,
    val runtimeMinutes: Int?,
    val streamId: String,
    val episodeCount: Int
)

/**
 * How many of a show's seasons a single random pick looks at. Each lookup is
 * cached (memory + disk) by [TmdbRepository.getSeasonEpisodes], so a second
 * hop through the same season is free — this only bounds the first one.
 */
private const val RANDOM_SEASON_SAMPLE = 3

/**
 * Picks a random aired episode, spread over the seasons it is handed.
 *
 * Pure so it can be unit-tested — [randomAiredEpisode] below is the half that
 * talks to TMDB. Seasons are visited in a shuffled order and their aired
 * episodes pooled, which makes every season reachable while keeping the pick
 * uniform over episodes: gathering the *whole* show would mean one TMDB request
 * per season on every hop.
 *
 * [excludeSeason]/[excludeEpisode] is the episode playing now — repeating it
 * would look like the player had stalled. When it is the only aired episode
 * there is, this returns null so the caller can fall back to its own default
 * rather than loop on the same episode.
 */
internal fun chooseRandomEpisode(
    seasons: List<Pair<Int, List<ResolvedEpisode>>>,
    excludeSeason: Int?,
    excludeEpisode: Int?,
    random: Random = Random.Default
): RandomEpisodePick? {
    val pool = seasons
        .shuffled(random)
        .flatMap { (season, episodes) ->
            episodes
                .filter { !isUnaired(it.airDate) }
                .filterNot { season == excludeSeason && it.episodeNumber == excludeEpisode }
                .map { season to it }
        }
    if (pool.isEmpty()) return null

    val (season, episode) = pool.random(random)
    return RandomEpisodePick(
        season = season,
        episode = episode.episodeNumber,
        name = episode.name?.takeIf { it.isNotBlank() },
        runtimeMinutes = episode.runtimeMinutes?.takeIf { it > 0 },
        streamId = episode.streamId,
        episodeCount = seasons.firstOrNull { it.first == season }?.second?.size ?: 0
    )
}

/**
 * The episode the player chains into: in random mode a random already-aired
 * episode of the show, otherwise [arithmeticTarget] — the arithmetic next one
 * the player would normally offer.
 *
 * A random miss (unresolved show, nothing aired) stays on that default, so the
 * chain keeps going instead of dead-ending. Null means "nothing to chain to",
 * which the end-of-playback panel reads as "show the because-you-watched row".
 */
suspend fun resolveRandomChainTarget(
    context: Context,
    randomMode: Boolean,
    arithmeticTarget: Pair<Int, Int>?,
    showId: String,
    showType: String,
    season: Int?,
    episode: Int?
): Pair<Int, Int>? {
    if (!randomMode) return arithmeticTarget
    val pick = randomAiredEpisode(context, showId, showType, season, episode)
    return pick?.let { it.season to it.episode } ?: arithmeticTarget
}

/**
 * A random aired episode of the show, or null when there is nothing aired to
 * pick from (or the show could not be resolved).
 *
 * Used in two places: the detail page's Random button, and the player's own
 * "keep playing randomly" chain when random mode is on.
 */
suspend fun randomAiredEpisode(
    context: Context,
    showId: String,
    showType: String,
    excludeSeason: Int? = null,
    excludeEpisode: Int? = null
): RandomEpisodePick? = withContext(Dispatchers.IO) {
    if (showId.isBlank()) return@withContext null

    val repository = TmdbRepository.getInstance(context.applicationContext)
    val show = runCatching {
        repository.fetchEnrichedMetaCached(showId, showType)
    }.getOrNull() ?: return@withContext null

    val tmdbId = show.id.takeIf { it > 0 } ?: return@withContext null

    // Season 0 is TMDB's specials bucket (trailers, recaps); a random episode
    // from it is not what "random episode of this show" means.
    val seasonNumbers = show.seasons
        .filter { it.seasonNumber > 0 }
        .sortedByDescending { it.episodeCount ?: 0 }
        .map { it.seasonNumber }
        .take(RANDOM_SEASON_SAMPLE)
        .shuffled()

    // Nothing listed to pick from: fall back to the season playing now, so a
    // sparse TMDB entry still gets a random episode.
    val candidates = seasonNumbers.ifEmpty { listOfNotNull(excludeSeason) }

    val gathered = candidates.mapNotNull { season ->
        runCatching {
            repository.getSeasonEpisodes(tmdbId, season, showId)
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { season to it }
    }

    val pick = chooseRandomEpisode(gathered, excludeSeason, excludeEpisode)
        ?: return@withContext null
    // The TMDB row carries the Stremio-style id when the app already knows it;
    // build one otherwise so every caller has a playable id.
    pick.copy(
        streamId = pick.streamId.takeIf { it.isNotBlank() }
            ?: "$showId:${pick.season}:${pick.episode}"
    )
}
