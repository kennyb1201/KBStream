package com.kennyb1201.kbstream.data.watched

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity

object WatchedEpisodeState {

    fun buildEpisodeKey(
        parentId: String,
        season: Int?,
        episode: Int?
    ): String? {
        if (parentId.isBlank() || season == null || episode == null) return null
        return "$parentId:$season:$episode"
    }

    fun buildMergedWatchedKeys(
        parentId: String,
        localCompletedEntries: List<WatchHistoryEntity>,
        simklCompletedEpisodes: Set<Pair<Int, Int>>
    ): Set<String> {
        val merged = linkedSetOf<String>()

        localCompletedEntries.forEach { entry ->
            merged += entry.id

            val key = buildEpisodeKey(
                parentId = entry.parentId.ifBlank { parentId },
                season = entry.season,
                episode = entry.episode
            )

            if (key != null) {
                merged += key
            }
        }

        simklCompletedEpisodes.forEach { pair ->
            val key = buildEpisodeKey(
                parentId = parentId,
                season = pair.first,
                episode = pair.second
            )

            if (key != null) {
                merged += key
            }
        }

        return merged
    }

    fun localWatchedEpisodesForSeason(
        parentId: String,
        season: Int,
        watchedEpisodeKeys: Set<String>
    ): Set<Int> {
        return watchedEpisodeKeys.mapNotNull { key ->
            val parts = key.split(":")
            if (parts.size < 3) return@mapNotNull null

            val keyId = parts.dropLast(2).joinToString(":")
            val keySeason = parts[parts.size - 2].toIntOrNull()
            val keyEpisode = parts[parts.size - 1].toIntOrNull()

            if (keyId == parentId && keySeason == season && keyEpisode != null) {
                keyEpisode
            } else {
                null
            }
        }.toSet()
    }

    fun simklWatchedEpisodesForSeason(
        season: Int,
        simklWatchedEpisodes: Set<Pair<Int, Int>>
    ): Set<Int> {
        return simklWatchedEpisodes
            .filter { pair -> pair.first == season }
            .map { pair -> pair.second }
            .toSet()
    }

    fun effectiveWatchedEpisodesForSeason(
    parentId: String,
    season: Int,
    simklWatchedEpisodes: Set<Pair<Int, Int>>,
    watchedEpisodeKeys: Set<String>
): Set<Int> {
    val simklSeasonEpisodes = simklWatchedEpisodesForSeason(
        season = season,
        simklWatchedEpisodes = simklWatchedEpisodes
    )

    val localSeasonEpisodes = localWatchedEpisodesForSeason(
        parentId = parentId,
        season = season,
        watchedEpisodeKeys = watchedEpisodeKeys
    )

    return simklSeasonEpisodes + localSeasonEpisodes
}
