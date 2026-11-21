package com.kennyb1201.kbstream.data.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One section of `GET /sync/last_activities` (F1, spec 2026-11-20).
 *
 * Trakt reports, per section, the time anything in it last changed. Only
 * `watched_at` is modelled: that is what the watched snapshot is built from,
 * and a rating or a comment moving does not change a single badge.
 */
@JsonClass(generateAdapter = true)
data class TraktActivitySection(
    @Json(name = "watched_at") val watchedAt: String? = null
)

/** `GET /sync/last_activities` - one cheap call that says what moved. */
@JsonClass(generateAdapter = true)
data class TraktLastActivities(
    @Json(name = "movies") val movies: TraktActivitySection? = null,
    @Json(name = "shows") val shows: TraktActivitySection? = null,
    @Json(name = "episodes") val episodes: TraktActivitySection? = null
)

/** The stamps in an activities answer, or null when there is no answer. */
internal fun TraktLastActivities?.stamps(): TraktWatchedStamps? = this?.let { activities ->
    TraktWatchedStamps(
        movies = activities.movies?.watchedAt?.takeIf { it.isNotBlank() },
        series = listOfNotNull(
            activities.episodes?.watchedAt?.takeIf { it.isNotBlank() },
            activities.shows?.watchedAt?.takeIf { it.isNotBlank() }
        ).maxOrNull()
    )
}
