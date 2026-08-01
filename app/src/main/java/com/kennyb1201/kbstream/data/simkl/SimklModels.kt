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
    @Json(name = "imdb") val imdb: String?,
    @Json(name = "tmdb") val tmdb: String?,
    @Json(name = "tvdb") val tvdb: String?
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
    @Json(name = "show") val show: SimklPlaybackShow?,
    @Json(name = "episode") val episode: SimklPlaybackEpisode?
)

data class SimklContinueWatchingItem(
    val id: Int?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val lastWatchedAt: String?,
    val progress: Float?,
    val upNextText: String?
)
