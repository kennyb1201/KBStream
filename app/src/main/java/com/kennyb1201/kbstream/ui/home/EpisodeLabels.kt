package com.kennyb1201.kbstream.ui.home

/**
 * The small text and key rules an up-next card is built from: the
 * S·E line, the key it is regrouped by, and the progress bar's
 * position. Extracted from HomeViewModel so the shapes the rail and
 * the playback history have to agree on sit in one readable place.
 */

internal fun formatSeasonEpisode(
    season: Int?,
    episode: Int?
): String {

    return when {

        season != null &&
            episode != null ->
            "S${season}E${episode}"

        season != null ->
            "S$season"

        episode != null ->
            "E$episode"

        else ->
            ""
    }
}

internal fun parseEpisodeKey(
    key: String
): Triple<String, Int, Int>? {

    val match =
        Regex(
            """^(.+?)(?::[sS]?(\d+))(?::[eE]?(\d+))$"""
        ).find(
            key.trim()
        )
            ?: return null

    val showId =
        match.groupValues[1]
            .trim()

    val season =
        match.groupValues[2]
            .toIntOrNull()
            ?: return null

    val episode =
        match.groupValues[3]
            .toIntOrNull()
            ?: return null

    if (
        showId.isBlank() ||
        season < 0 ||
        episode < 0
    ) {
        return null
    }

    return Triple(
        showId,
        season,
        episode
    )
}

internal fun progressFromHistory(
    positionMs: Long,
    durationMs: Long
): Float? {

    if (
        positionMs <= 0L ||
        durationMs <= 0L
    ) {
        return null
    }

    return (
        positionMs.toFloat() /
            durationMs.toFloat()
        ).coerceIn(
            0.005f,
            0.99f
        )
}
