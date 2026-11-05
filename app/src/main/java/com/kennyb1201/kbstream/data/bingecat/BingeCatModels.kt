package com.kennyb1201.kbstream.data.bingecat

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The wire shapes v1 reads, taken from the live contract rather than from the
 * spec's summary: the spec names `/watchlist`, `/saved-list` and
 * `/resolve/media/batch`, none of which exist. The real reads are the
 * profile-scoped `sync/{kind}/...` routes, `/lists`, `/lists/{ref}/items` and
 * `/history`, and each row arrives with its TMDB/IMDb ids inline - so nothing
 * in v1 needs the id-resolution endpoint the spec's F4 step was built around.
 */

/** `POST /oauth/device/code`. */
@JsonClass(generateAdapter = true)
data class BingeCatDeviceCodeResponse(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Int = 600,
    @Json(name = "interval") val intervalSeconds: Int = 5
)

/** `POST /oauth/token`, for both the device grant and the refresh grant. */
@JsonClass(generateAdapter = true)
data class BingeCatTokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Long = 0L,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "created_at") val createdAtEpochSeconds: Long? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)

/**
 * A token pair as this app stores it.
 *
 * BingeCat answers `expires_in` and `created_at` separately, so the expiry is
 * derived once here instead of at every read ([BingeCatDeviceFlowRules.expiresAtEpochMillis]).
 */
data class BingeCatToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
    /** What the grant was actually allowed, so diagnostics can name it. */
    val scope: String? = null
)

/** `GET /api/v1/profiles` - the mapping step's list. */
@JsonClass(generateAdapter = true)
data class BingeCatProfilesResponse(
    @Json(name = "profiles") val profiles: List<BingeCatProfile> = emptyList()
)

/**
 * One BingeCat profile.
 *
 * Only the identity and name are modelled: v1 maps a KBStream profile to one
 * of these and never reads the sharing/nuvio halves.
 */
@JsonClass(generateAdapter = true)
data class BingeCatProfile(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "is_main") val isMain: Boolean = false
)

/** The ids every media row carries; at least one is always present. */
@JsonClass(generateAdapter = true)
data class BingeCatIds(
    @Json(name = "bingecat") val bingecat: Long? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null
)

/** `title`/`year`/`ids` - the media half of every list and history row. */
@JsonClass(generateAdapter = true)
data class BingeCatMedia(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: BingeCatIds? = null
)

/**
 * One row of `sync/watchlist` or `sync/favorites`.
 *
 * The media object lives under `movie` or `show` according to `type`, exactly
 * like Trakt's list rows - the discriminator is the field, not the id shape.
 */
@JsonClass(generateAdapter = true)
data class BingeCatSavedItem(
    @Json(name = "rank") val rank: Int? = null,
    @Json(name = "listed_at") val listedAt: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: BingeCatMedia? = null,
    @Json(name = "show") val show: BingeCatMedia? = null
) {
    val isMovie: Boolean get() = type == "movie"

    val media: BingeCatMedia? get() = if (isMovie) movie else show
}

/** `GET /api/v1/profiles/{id}/lists`. */
@JsonClass(generateAdapter = true)
data class BingeCatListsResponse(
    @Json(name = "lists") val lists: List<BingeCatListSummary> = emptyList()
)

/** One user list. `ref` is what `/api/v1/lists/{ref}/items` is addressed by. */
@JsonClass(generateAdapter = true)
data class BingeCatListSummary(
    @Json(name = "ref") val ref: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "item_count") val itemCount: Int? = null,
    @Json(name = "deleted_at") val deletedAt: String? = null,
    @Json(name = "is_recommendation") val isRecommendation: Boolean = false
)

/** `GET /api/v1/lists/{ref}/items`. */
@JsonClass(generateAdapter = true)
data class BingeCatListItemsResponse(
    @Json(name = "items") val items: List<BingeCatListItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BingeCatListItem(
    @Json(name = "position") val position: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: BingeCatMedia? = null,
    @Json(name = "show") val show: BingeCatMedia? = null
) {
    val isMovie: Boolean get() = type == "movie"

    val media: BingeCatMedia? get() = if (isMovie) movie else show
}

/**
 * `GET /api/v1/profiles/{id}/history` - a cursor-paged feed.
 *
 * v1 reads it for one job only: knowing which BingeCat rows this profile has
 * watched, so the BingeCat section can badge its own rows.
 */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryFeed(
    @Json(name = "activated") val activated: Boolean = false,
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "items") val items: List<BingeCatHistoryItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BingeCatHistoryItem(
    @Json(name = "id") val id: String? = null,
    @Json(name = "kind") val kind: String? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: BingeCatMedia? = null,
    @Json(name = "show") val show: BingeCatMedia? = null
) {
    val media: BingeCatMedia? get() = if (type == "movie") movie else show
}

/** A device-code request's body. Public client: no `client_secret`. */
@JsonClass(generateAdapter = true)
data class BingeCatDeviceCodeRequest(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "scope") val scope: String
)

/** The device-code grant's poll body. */
@JsonClass(generateAdapter = true)
data class BingeCatDeviceTokenRequest(
    @Json(name = "grant_type") val grantType: String =
        "urn:ietf:params:oauth:grant-type:device_code",
    @Json(name = "code") val code: String,
    @Json(name = "client_id") val clientId: String
)

/**
 * The refresh body.
 *
 * `redirect_uri` is listed as required on the wire; the device flow never
 * redirects, so it carries OAuth's own "no redirect" marker, the same value
 * Trakt's refresh uses. The client secret is absent, because this is a public
 * client (see [BingeCatConfig]).
 */
@JsonClass(generateAdapter = true)
data class BingeCatRefreshTokenRequest(
    @Json(name = "grant_type") val grantType: String = "refresh_token",
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "redirect_uri") val redirectUri: String =
        "urn:ietf:wg:oauth:2.0:oob"
)

/** `POST /oauth/revoke`. */
@JsonClass(generateAdapter = true)
data class BingeCatRevokeRequest(
    @Json(name = "token") val token: String,
    @Json(name = "client_id") val clientId: String
)
