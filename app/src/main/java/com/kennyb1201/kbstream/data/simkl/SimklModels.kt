package com.kennyb1201.kbstream.data.simkl

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SimklPinCodeResponse(
    @Json(name = "user_code") val userCode: String?,
    @Json(name = "device_code") val deviceCode: String?,
    @Json(name = "verification_url") val verificationUrl: String?,
    @Json(name = "expires_in") val expiresIn: Int?,
    @Json(name = "interval") val interval: Int?
)

@JsonClass(generateAdapter = true)
data class SimklTokenResponse(
    @Json(name = "access_token") val accessToken: String?,
    @Json(name = "token_type") val tokenType: String?,
    @Json(name = "refresh_token") val refreshToken: String?,
    @Json(name = "created_at") val createdAt: Long?
)

@JsonClass(generateAdapter = true)
data class SimklActivitiesResponse(
    @Json(name = "all") val all: String?
)

@JsonClass(generateAdapter = true)
data class SimklPlaybackIds(
    @Json(name = "simkl") val simkl: Int?,
    @Json(name = "slug") val slug: String?,
    @Json(name = "imdb") val imdb: String?,
    @Json(name = "tmdb") val tmdb: String?,
    @Json(name = "tvdb") val tvdb: String?,
    @Json(name = "tvdbslug") val tvdbSlug: String?,
    @Json(name = "traktslug") val traktSlug: String?
)

@JsonClass(generateAdapter = true)
data class SimklPlaybackMovie(
    @Json(name = "title") val title: String?,
    @Json(name = "year") val year: Int?,
    @Json(name = "poster") val poster: String?,
    @Json(name = "ids") val ids: SimklPlaybackIds?
)

@JsonClass(generateAdapter = true)
data class SimklPlaybackShow(
    @Json(name = "title") val title: String?,
    @Json(name = "year") val year: Int?,
    @Json(name = "poster") val poster: String?,
    @Json(name = "ids") val ids: SimklPlaybackIds?
)

@JsonClass(generateAdapter = true)
data class SimklPlaybackEpisode(
    @Json(name = "title") val title: String?,
    @Json(name = "season") val season: Int?,
    @Json(name = "episode") val episode: Int?
)

@JsonClass(generateAdapter = true)
data class SimklPlaybackItem(
    @Json(name = "id") val id: Int?,
    @Json(name = "paused_at") val pausedAt: String?,
    @Json(name = "progress") val progress: Float?,
    @Json(name = "movie") val movie: SimklPlaybackMovie?,
    @Json(name = "show") val show: SimklPlaybackShow?,
    @Json(name = "episode") val episode: SimklPlaybackEpisode?
)

@JsonClass(generateAdapter = true)
data class SimklWatchingShowsResponse(
    @Json(name = "shows") val shows: List<SimklWatchingShowItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SimklWatchingShowItem(
    @Json(name = "added_to_watchlist_at") val addedToWatchlistAt: String?,
    @Json(name = "last_watched_at") val lastWatchedAt: String?,
    @Json(name = "user_rated_at") val userRatedAt: String?,
    @Json(name = "user_rating") val userRating: Int?,
    @Json(name = "status") val status: String?,
    @Json(name = "last_watched") val lastWatched: String?,
    @Json(name = "next_to_watch") val nextToWatch: String?,
    @Json(name = "watched_episodes_count") val watchedEpisodesCount: Int?,
    @Json(name = "total_episodes_count") val totalEpisodesCount: Int?,
    @Json(name = "not_aired_episodes_count") val notAiredEpisodesCount: Int?,
    @Json(name = "show") val show: SimklWatchingShow?
)

@JsonClass(generateAdapter = true)
data class SimklWatchingShow(
    @Json(name = "title") val title: String?,
    @Json(name = "poster") val poster: String?,
    @Json(name = "year") val year: Int?,
    @Json(name = "runtime") val runtime: Int?,
    @Json(name = "ids") val ids: SimklPlaybackIds?
)

data class SimklContinueWatchingItem(
    val id: String,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val lastWatchedAt: String?,
    val progress: Float?,
    val upNextText: String?,
    val mediaType: String,
    val source: String
)

@JsonClass(generateAdapter = true)
data class SimklWatchedBulkRequest(
    @Json(name = "movies") val movies: List<SimklWatchedLookupMovie> = emptyList(),
    @Json(name = "shows") val shows: List<SimklWatchedLookupShow> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SimklWatchedLookupMovie(
    @Json(name = "ids") val ids: SimklPlaybackIdsRef
)

@JsonClass(generateAdapter = true)
data class SimklWatchedLookupShow(
    @Json(name = "ids") val ids: SimklPlaybackIdsRef
)

@JsonClass(generateAdapter = true)
data class SimklPlaybackIdsRef(
    @Json(name = "simkl") val simkl: Int? = null,
    @Json(name = "imdb") val imdb: String? = null
)

@JsonClass(generateAdapter = true)
data class SimklWatchedBulkResponse(
    @Json(name = "movies") val movies: List<SimklWatchedMovieResult> = emptyList(),
    @Json(name = "shows") val shows: List<SimklWatchedShowResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SimklWatchedMovieResult(
    @Json(name = "watched") val watched: Boolean?,
    @Json(name = "movie") val movie: SimklWatchedMovie?
)

@JsonClass(generateAdapter = true)
data class SimklWatchedMovie(
    @Json(name = "title") val title: String?,
    @Json(name = "ids") val ids: SimklPlaybackIds?
)

@JsonClass(generateAdapter = true)
data class SimklWatchedShowResult(
    @Json(name = "watched") val watched: Boolean?,
    @Json(name = "show") val show: SimklWatchedShow?,
    @Json(name = "episodes") val episodes: List<SimklWatchedEpisode> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SimklWatchedShow(
    @Json(name = "title") val title: String?,
    @Json(name = "ids") val ids: SimklPlaybackIds?
)

@JsonClass(generateAdapter = true)
data class SimklWatchedEpisode(
    @Json(name = "season") val season: Int?,
    @Json(name = "episode") val episode: Int?,
    @Json(name = "watched") val watched: Boolean?
)

data class SimklWatchedImport(
    val watchedMovieImdbIds: Set<String>,
    val watchedMovieSimklIds: Set<Int>,
    val watchedShowImdbIds: Set<String>,
    val watchedShowSimklIds: Set<Int>,
    val watchedEpisodesByShowKey: Map<String, Set<Pair<Int, Int>>>
)
