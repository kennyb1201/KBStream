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
data class SimklPinTokenRequest(
    @Json(name = "code") val code: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
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
data class SimklNextToWatchInfo(
    @Json(name = "title") val title: String?,
    @Json(name = "season") val season: Int?,
    @Json(name = "episode") val episode: Int?,
    @Json(name = "date") val date: String?
)

@JsonClass(generateAdapter = true)
data class SimklPoster(
    @Json(name = "poster") val poster: String?
)

@JsonClass(generateAdapter = true)
data class SimklShowIds(
    @Json(name = "simkl") val simkl: Int?,
    @Json(name = "imdb") val imdb: String?,
    @Json(name = "tmdb") val tmdb: String?,
    @Json(name = "tvdb") val tvdb: String?
)

@JsonClass(generateAdapter = true)
data class SimklShow(
    @Json(name = "title") val title: String?,
    @Json(name = "year") val year: Int?,
    @Json(name = "ids") val ids: SimklShowIds?,
    @Json(name = "poster") val poster: String?
)

@JsonClass(generateAdapter = true)
data class SimklSyncItem(
    @Json(name = "status") val status: String?,
    @Json(name = "last_watched_at") val lastWatchedAt: String?,
    @Json(name = "next_to_watch_info") val nextToWatchInfo: SimklNextToWatchInfo?,
    @Json(name = "show") val show: SimklShow?
)
