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
 * Body of `POST /sync/ratings` and `/sync/ratings/remove`.
 *
 * One entry per title, typed by the bucket it sits in - Trakt has no
 * all-types write here, so the caller puts the entry under `movies` or
 * `shows` and leaves the other null. `seasons`/`episodes` buckets exist on
 * Trakt's side and are deliberately NOT modelled: this app's signal is
 * show-level only, and per-episode ratings are a separate commission.
 */
@JsonClass(generateAdapter = true)
data class TraktRatingsRequest(
    @Json(name = "movies") val movies: List<TraktRatingEntry>? = null,
    @Json(name = "shows") val shows: List<TraktRatingEntry>? = null
)

/**
 * One rated title.
 *
 * [rating] is the same integer 1-10 scale the local store uses, which is why
 * one mapping serves both trackers (see the taste mapping). [ratedAt] is the
 * signal's own timestamp, ISO-8601 UTC; null is allowed and means "now" to
 * Trakt. On a REMOVE the entry still carries a rating because the request
 * shape is shared and Trakt matches on [ids] alone - the value is never read
 * as an opinion (see the ratings impl).
 */
@JsonClass(generateAdapter = true)
data class TraktRatingEntry(
    @Json(name = "rating") val rating: Int,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "ids") val ids: TraktIds
)

/**
 * The answer to a ratings write.
 *
 * Trakt answers these sync endpoints with per-type COUNTS rather than an echo
 * of the items, one bucket per outcome. `added` is a fresh rating, `updated` an
 * overwrite of one that already existed, `deleted` a successful remove, and
 * `not_found` the entries its resolver could not match. A remove answers
 * `deleted`; a first rating answers `added`; rating the same title twice
 * answers `updated`. All four are read, so the success rule does not depend on
 * which one this particular write produced (see [landed]) - the exact key
 * names are what the landing probe confirms.
 */
@JsonClass(generateAdapter = true)
data class TraktRatingsResponse(
    @Json(name = "added") val added: TraktRatingCounts? = null,
    @Json(name = "updated") val updated: TraktRatingCounts? = null,
    @Json(name = "deleted") val deleted: TraktRatingCounts? = null,
    @Json(name = "not_found") val notFound: TraktRatingCounts? = null
) {

    /** True when any landing bucket counted the title. */
    val anyLanded: Boolean
        get() = added?.hasAny == true || updated?.hasAny == true || deleted?.hasAny == true

    /** True when the server held the title in its not-found bucket. */
    val anyNotFound: Boolean get() = notFound?.hasAny == true

    /**
     * The tree's own rule for these 2xx-even-when-unmatched endpoints
     * (`SimklAddToListResponse.landed` is the same shape): true unless the
     * server POSITIVELY said it could not match the item. An empty or
     * unreadable body is taken at its word - the 2xx already said so.
     */
    val landed: Boolean get() = anyLanded || !anyNotFound
}

/** Per-type counts in one ratings-response bucket. */
@JsonClass(generateAdapter = true)
data class TraktRatingCounts(
    @Json(name = "movies") val movies: Int = 0,
    @Json(name = "shows") val shows: Int = 0,
    @Json(name = "seasons") val seasons: Int = 0,
    @Json(name = "episodes") val episodes: Int = 0
) {
    val hasAny: Boolean get() = movies > 0 || shows > 0 || seasons > 0 || episodes > 0
}

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

/**
 * The body of a list create (`POST /users/me/lists`) and of a list update
 * (`PUT /users/me/lists/{id}`).
 *
 * Trakt takes the list's metadata as one object on both verbs. This app only
 * ever sets the name: a rename from Library must not quietly flip a list's
 * privacy or drop its description, so every other field is left off the wire
 * on an update (absent means "keep"). A create sends the app's one default -
 * private - because a list the viewer makes on a TV is not something to
 * publish to their followers by accident.
 */
@JsonClass(generateAdapter = true)
data class TraktListWriteRequest(
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "privacy") val privacy: String? = null,
    @Json(name = "display_numbers") val displayNumbers: Boolean? = null,
    @Json(name = "allow_comments") val allowComments: Boolean? = null
)

/** One row of `GET /users/{user}/lists/{list}/items/{type}`. */
@JsonClass(generateAdapter = true)
data class TraktListItem(
    @Json(name = "rank") val rank: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMediaEntry? = null,
    @Json(name = "show") val show: TraktMediaEntry? = null
)

// ---------------------------------------------------------------------------
// Public list browsing (Settings → Trakt → Browse public lists)
// ---------------------------------------------------------------------------

/**
 * A list as Trakt's discovery, search and liked reads carry it.
 *
 * One model for all three because the list object itself is the same in each:
 * `name`, `description`, `item_count`, `likes`, an `ids` pair (numeric id and
 * slug) and the `user` that owns it. Only the ROW around it differs, which is
 * what [TraktListDiscoveryRow], [TraktListSearchRow] and [TraktLikedListRow]
 * are.
 */
@JsonClass(generateAdapter = true)
data class TraktPublicList(
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "privacy") val privacy: String? = null,
    @Json(name = "item_count") val itemCount: Int? = null,
    @Json(name = "likes") val likes: Int? = null,
    @Json(name = "comment_count") val commentCount: Int? = null,
    @Json(name = "ids") val ids: TraktListIds? = null,
    @Json(name = "user") val user: TraktPublicListUser? = null
)

/** The owner of a public list; the card names them (spec F5). */
@JsonClass(generateAdapter = true)
data class TraktPublicListUser(
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "ids") val ids: TraktUserIds? = null
)

/**
 * One row of `GET /lists/popular` and `GET /lists/trending`.
 *
 * The API's own schema wraps the list here (`{like_count, comment_count,
 * list}`) rather than returning bare list objects - confirmed against Trakt's
 * published definition for both endpoints, not assumed from the older
 * documentation that showed arrays of lists.
 */
@JsonClass(generateAdapter = true)
data class TraktListDiscoveryRow(
    @Json(name = "like_count") val likeCount: Int? = null,
    @Json(name = "comment_count") val commentCount: Int? = null,
    @Json(name = "list") val list: TraktPublicList? = null
)

/** One row of `GET /search/list` (a text search over public list names). */
@JsonClass(generateAdapter = true)
data class TraktListSearchRow(
    @Json(name = "type") val type: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "list") val list: TraktPublicList? = null
)

/**
 * One row of `GET /users/{id}/likes/lists` - the save leg's read.
 *
 * The path is `users/{id}/likes/{type}` with `type=lists` (the liked-lists read
 * the spec called `/users/likes/lists`); each row nests the list under `list`
 * and stamps when it was liked.
 */
@JsonClass(generateAdapter = true)
data class TraktLikedListRow(
    @Json(name = "liked_at") val likedAt: String? = null,
    @Json(name = "list") val list: TraktPublicList? = null
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
