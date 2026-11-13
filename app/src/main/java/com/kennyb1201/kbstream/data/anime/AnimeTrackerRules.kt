package com.kennyb1201.kbstream.data.anime

/** The legs' pure rules (spec 2026-11-13, F2), kept free of JSON and Android. */

/**
 * Whether a TMDB search result names the anime we are looking for.
 *
 * Neither AniList nor Kitsu carries a TMDB id, so a title is the only identity
 * the two ends share - accepted only on an exact normalized match, the same rule
 * the ID hub applies in the other direction. The cost of being wrong is a card
 * for the wrong show in Continue Watching, which is worse than a missing card.
 */
internal fun animeTmdbTitleMatch(candidateTitles: List<String>, tmdbName: String?): Boolean {
    val wanted = candidateTitles.map { normalizeAnimeTitle(it) }.filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return false
    val found = normalizeAnimeTitle(tmdbName)
    if (found.isEmpty()) return false
    return wanted.contains(found)
}

/**
 * Which (season, episode) pairs an absolute [progress] covers, given the show's
 * season sizes in TMDB order.
 *
 * AniList and Kitsu count an anime's episodes ABSOLUTELY - episode 13 is the
 * thirteenth episode, whether TMDB files it as S1E13 or S2E1. The rest of the
 * app speaks (season, episode), so the walk has to be done once, here, and only
 * over seasons that actually have episodes (TMDB lists season 0 as specials, and
 * those are not part of the count).
 */
internal fun animeWatchedEpisodes(
    progress: Int,
    seasonEpisodeCounts: List<Pair<Int, Int>>
): Set<Pair<Int, Int>> {
    var remaining = progress.coerceAtLeast(0)
    if (remaining == 0) return emptySet()

    val out = LinkedHashSet<Pair<Int, Int>>()
    for ((season, count) in seasonEpisodeCounts.filter { it.first > 0 && it.second > 0 }) {
        if (remaining <= 0) break
        val take = minOf(remaining, count)
        for (episode in 1..take) out += season to episode
        remaining -= take
    }
    return out
}

/**
 * AniList's entry status as the rail's three buckets.
 *
 * REPEATING counts as in progress: a rewatch still has unwatched episodes, and
 * the next one is exactly what Continue Watching should offer.
 */
internal fun aniListStatus(raw: String?): AnimeTrackerStatus =
    when (raw?.trim()?.uppercase()) {
        "CURRENT", "REPEATING" -> AnimeTrackerStatus.IN_PROGRESS
        "COMPLETED" -> AnimeTrackerStatus.COMPLETED
        else -> AnimeTrackerStatus.OTHER
    }

/** Kitsu's library-entry status as the same three buckets. */
internal fun kitsuStatus(raw: String?): AnimeTrackerStatus =
    when (raw?.trim()?.lowercase()) {
        "current" -> AnimeTrackerStatus.IN_PROGRESS
        "completed" -> AnimeTrackerStatus.COMPLETED
        else -> AnimeTrackerStatus.OTHER
    }
