package com.kennyb1201.kbstream.data.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** `POST /oauth/device/code` - what the viewer is shown and what we poll with. */
@JsonClass(generateAdapter = true)
data class TraktDeviceCodeResponse(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Int = 600,
    @Json(name = "interval") val intervalSeconds: Int = 5
)

/** A granted token pair. Trakt's access tokens last 7 days. */
@JsonClass(generateAdapter = true)
data class TraktTokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Long = 0L,
    @Json(name = "created_at") val createdAtEpochSeconds: Long? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)

/**
 * A token pair as this app stores it.
 *
 * [expiresAtEpochMillis] is derived rather than stored on the wire: Trakt sends
 * `created_at` and `expires_in` separately, and both a clock that is a little off
 * and a missing `created_at` would otherwise turn into a refresh at the wrong
 * time. [expiresAt] is the one fact the refresh rule needs.
 */
data class TraktToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long
)

/** `GET /users/settings`, only for the account line on the connect screen. */
@JsonClass(generateAdapter = true)
data class TraktUserSettings(
    @Json(name = "user") val user: TraktUser? = null
)

@JsonClass(generateAdapter = true)
data class TraktUser(
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "ids") val ids: TraktUserIds? = null
)

@JsonClass(generateAdapter = true)
data class TraktUserIds(
    @Json(name = "slug") val slug: String? = null
)

/** Body of `POST /oauth/device/code`. */
@JsonClass(generateAdapter = true)
data class TraktDeviceCodeRequest(
    @Json(name = "client_id") val clientId: String
)

/**
 * Body of `POST /oauth/device/token`.
 *
 * No `client_secret`: the device flow is a public-client flow and this app
 * cannot ship a secret (see [TraktConfig]).
 */
@JsonClass(generateAdapter = true)
data class TraktDeviceTokenRequest(
    @Json(name = "code") val code: String,
    @Json(name = "client_id") val clientId: String
)

/** Body of `POST /oauth/token` (the refresh grant). */
@JsonClass(generateAdapter = true)
data class TraktRefreshRequest(
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "redirect_uri") val redirectUri: String = TraktConfig.REDIRECT_URI,
    @Json(name = "grant_type") val grantType: String = "refresh_token"
)

/** Body of `POST /oauth/revoke`. */
@JsonClass(generateAdapter = true)
data class TraktRevokeRequest(
    @Json(name = "token") val token: String,
    @Json(name = "client_id") val clientId: String
)

// ---------------------------------------------------------------------------
// Watch state and scrobbling
// ---------------------------------------------------------------------------

/**
 * Trakt's cross-reference object. Every write this app makes is keyed on it:
 * the app knows a title as an IMDb id (`tt…`) or a TMDB id, never as Trakt's
 * own numeric id, so all four forms travel together and Trakt resolves the one
 * it needs.
 */
@JsonClass(generateAdapter = true)
data class TraktIds(
    @Json(name = "trakt") val trakt: Int? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null
)

/** A movie (or show) as a write body or an extended list row. */
@JsonClass(generateAdapter = true)
data class TraktMediaEntry(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIds? = null,
    @Json(name = "images") val images: TraktImages? = null
)

/** Artwork, only present when a list read asks for `extended=images`. */
@JsonClass(generateAdapter = true)
data class TraktImages(
    @Json(name = "poster") val poster: List<String> = emptyList(),
    @Json(name = "fanart") val fanart: List<String> = emptyList()
)

/** `POST /scrobble/{start,pause,stop}`. */
@JsonClass(generateAdapter = true)
data class TraktScrobbleRequest(
    @Json(name = "movie") val movie: TraktMediaEntry? = null,
    @Json(name = "show") val show: TraktMediaEntry? = null,
    @Json(name = "episode") val episode: TraktEpisodeNumber? = null,
    @Json(name = "progress") val progress: Int
)

@JsonClass(generateAdapter = true)
data class TraktEpisodeNumber(
    @Json(name = "season") val season: Int,
    @Json(name = "number") val number: Int
)

@JsonClass(generateAdapter = true)
data class TraktScrobbleResponse(
    @Json(name = "action") val action: String? = null,
    @Json(name = "progress") val progress: Double? = null,
    @Json(name = "id") val id: Long? = null
)

/** `POST /sync/history` (and `/sync/history/remove`, which shares the shape). */
@JsonClass(generateAdapter = true)
data class TraktSyncHistoryRequest(
    @Json(name = "movies") val movies: List<TraktMediaEntry>? = null,
    @Json(name = "shows") val shows: List<TraktShowHistoryEntry>? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowHistoryEntry(
    @Json(name = "ids") val ids: TraktIds? = null,
    @Json(name = "seasons") val seasons: List<TraktSeasonHistoryEntry>? = null
)

@JsonClass(generateAdapter = true)
data class TraktSeasonHistoryEntry(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktEpisodeNumber>? = null
)

/** One row of `GET /sync/watched/shows`. */
@JsonClass(generateAdapter = true)
data class TraktWatchedShow(
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "show") val show: TraktMediaEntry? = null,
    @Json(name = "seasons") val seasons: List<TraktWatchedSeason> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TraktWatchedSeason(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktWatchedEpisode>? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedEpisode(
    @Json(name = "number") val number: Int,
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null
)

/** One row of `GET /sync/watched/movies`. */
@JsonClass(generateAdapter = true)
data class TraktWatchedMovie(
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "movie") val movie: TraktMediaEntry? = null
)

/**
 * One row of `GET /sync/playback/{movies,episodes}`.
 *
 * [progress] is a percentage of the runtime, not a millisecond position -
 * Trakt never reports where in the file the viewer stopped. A movie row carries
 * [movie]; an episode row carries [show] and [episode].
 */
@JsonClass(generateAdapter = true)
data class TraktPlaybackItem(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "progress") val progress: Double? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMediaEntry? = null,
    @Json(name = "show") val show: TraktMediaEntry? = null,
    @Json(name = "episode") val episode: TraktPlaybackEpisode? = null
)

@JsonClass(generateAdapter = true)
data class TraktPlaybackEpisode(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null
)

/** One row of `GET /users/me/lists` (a personal list's summary). */
@JsonClass(generateAdapter = true)
data class TraktUserList(
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "privacy") val privacy: String? = null,
    @Json(name = "item_count") val itemCount: Int? = null,
    @Json(name = "ids") val ids: TraktListIds? = null
)

@JsonClass(generateAdapter = true)
data class TraktListIds(
    @Json(name = "trakt") val trakt: Int? = null,
    @Json(name = "slug") val slug: String? = null
)

/** One row of `GET /users/{user}/lists/{list}/items/{type}`. */
@JsonClass(generateAdapter = true)
data class TraktListItem(
    @Json(name = "rank") val rank: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMediaEntry? = null,
    @Json(name = "show") val show: TraktMediaEntry? = null
)

/** One row of `GET /search/{id_type}/{id}` (TMDB id resolution). */
@JsonClass(generateAdapter = true)
data class TraktSearchResult(
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMediaEntry? = null,
    @Json(name = "show") val show: TraktMediaEntry? = null
)

/**
 * One row of `GET /movies/{id}/comments/{sort}` or the shows twin.
 *
 * `review` and `spoiler` are flags Trakt sets on the comment itself; [user] is
 * present under `extended=full`, which is what makes an author name available
 * to the review card.
 */
@JsonClass(generateAdapter = true)
data class TraktComment(
    @Json(name = "id") val id: Long = 0L,
    @Json(name = "comment") val comment: String? = null,
    @Json(name = "spoiler") val spoiler: Boolean = false,
    @Json(name = "review") val review: Boolean = false,
    @Json(name = "likes") val likes: Int = 0,
    @Json(name = "replies") val replies: Int = 0,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "user") val user: TraktCommentUser? = null
)

@JsonClass(generateAdapter = true)
data class TraktCommentUser(
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null
)
