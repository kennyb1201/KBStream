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

// ── History writes (step 2) ───────────────────────────────────────────
//
// Taken from the live contract (`/api/v1/profiles/{profile_id}/sync/history`
// and `.../history/remove`, both guarded by `history:write`): one batch carries
// whichever of movies/shows/seasons/episodes the action produced, each entry
// identified by [BingeCatIds] and optionally timestamped. The response is
// COUNTS, not rows: `added`/`updated` (or `deleted`) plus the selectors the
// server could not resolve, which are logged rather than retried.

/**
 * One history mutation: the add and remove bodies share this shape.
 *
 * A null list is ABSENT on the wire (Moshi does not serialise nulls), which is
 * what the contract's `additionalProperties: false` wants: an empty batch is a
 * request that can only fail.
 */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryBatch(
    val movies: List<BingeCatHistoryMovie>? = null,
    val shows: List<BingeCatHistoryShow>? = null,
    val seasons: List<BingeCatHistorySeason>? = null,
    val episodes: List<BingeCatHistoryEpisode>? = null
) {
    /** True when nothing was put in the batch, so no request should be made. */
    val isEmpty: Boolean
        get() = movies.isNullOrEmpty() && shows.isNullOrEmpty() &&
            seasons.isNullOrEmpty() && episodes.isNullOrEmpty()
}

/** `movies[]`: a finished movie. */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryMovie(
    val ids: BingeCatIds,
    @Json(name = "watched_at") val watchedAt: String? = null
)

/** `shows[]`: a whole-show mark (no `seasons` = every episode, as Simkl's is). */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryShow(
    val ids: BingeCatIds,
    @Json(name = "watched_at") val watchedAt: String? = null
)

/** `seasons[]`: one whole season, which is what a season long-press means. */
@JsonClass(generateAdapter = true)
data class BingeCatHistorySeason(
    val ids: BingeCatIds,
    val season: Int,
    @Json(name = "watched_at") val watchedAt: String? = null
)

/** `episodes[]`: one play of one episode of a show. */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryEpisode(
    val ids: BingeCatIds,
    val season: Int,
    val episode: Int,
    @Json(name = "watched_at") val watchedAt: String? = null
)

/** `HistoryAddResponse`: what the write committed, and what it could not find. */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryAddResponse(
    val added: BingeCatHistoryCounts? = null,
    val updated: BingeCatHistoryCounts? = null,
    @Json(name = "not_found") val notFound: BingeCatNotFoundSelection? = null
)

/** `HistoryRemoveResponse`: what the removal deleted, and what it could not find. */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryRemoveResponse(
    val deleted: BingeCatDeleteCounts? = null,
    @Json(name = "not_found") val notFound: BingeCatNotFoundSelection? = null
)

/** `added`/`updated` counts. Movies and episodes are the only two kinds. */
@JsonClass(generateAdapter = true)
data class BingeCatHistoryCounts(
    val movies: Int = 0,
    val episodes: Int = 0
)

/** `deleted` counts, which name every selector kind. */
@JsonClass(generateAdapter = true)
data class BingeCatDeleteCounts(
    val movies: Int = 0,
    val shows: Int = 0,
    val seasons: Int = 0,
    val episodes: Int = 0
)

/**
 * The selectors a batch could not resolve.
 *
 * Logged, never a failure: a partially committed batch still counts as a write
 * for the entries it committed, and the unresolved ones are a diagnostics note
 * rather than a retry trigger. The entries themselves are free-form per the
 * contract, so they are counted, not modelled.
 */
@JsonClass(generateAdapter = true)
data class BingeCatNotFoundSelection(
    val movies: List<BingeCatUnresolvedRef>? = null,
    val shows: List<BingeCatUnresolvedRef>? = null,
    val seasons: List<BingeCatUnresolvedRef>? = null,
    val episodes: List<BingeCatUnresolvedRef>? = null
) {
    val total: Int
        get() = (movies?.size ?: 0) + (shows?.size ?: 0) +
            (seasons?.size ?: 0) + (episodes?.size ?: 0)
}

/** One unresolved selector. Its shape varies by kind; only the count matters here. */
@JsonClass(generateAdapter = true)
data class BingeCatUnresolvedRef(
    @Json(name = "id") val id: Long? = null
)

// ── List writes (step 3) ───────────────────────────────────
//
// Resolved against the live contract (2026-11-05), which answers the spec's
// two open questions:
//
//  - **Q2b: the watchlist IS writable per-item.** `POST
//    /api/v1/profiles/{profile_id}/sync/{kind}` ("Add or restore profile
//    Favorites or Watchlist items") and its `.../remove` sibling take the same
//    [BingeCatSavedBatch] shape the history writes do. The spec's candidate (a)
//    - the single-item PUT, whose body is rank/notes only - is the update of an
//    existing entry, and the spec's worry about it was right; the add is this
//    batch.
//  - **Q2a: a list item is a MEDIA REFERENCE STRING**, not a TMDB integer:
//    `/lists/{ref}/items` takes `items: string[]`, and the contract defines its
//    ref grammar (`imdb:tt…`, `tmdb:movie:N`, `tmdb:show:N`, `tvdb:N`). See
//    [BingeCatWriteRules.mediaRef] for which form this app sends.
//
// Both routes are guarded by `lists:write` (the same scope as the list-item
// writes), so one gate covers the whole step.

/**
 * The watchlist add body: titles to add or restore.
 *
 * A null list is absent on the wire, so an empty batch cannot be sent as two
 * empty arrays (see [isEmpty]).
 */
@JsonClass(generateAdapter = true)
data class BingeCatSavedBatch(
    val movies: List<BingeCatSavedMovie>? = null,
    val shows: List<BingeCatSavedShow>? = null
) {
    val isEmpty: Boolean get() = movies.isNullOrEmpty() && shows.isNullOrEmpty()
}

/** One movie for the watchlist, identified the same way a history write is. */
@JsonClass(generateAdapter = true)
data class BingeCatSavedMovie(val ids: BingeCatIds)

/** One show for the watchlist. */
@JsonClass(generateAdapter = true)
data class BingeCatSavedShow(val ids: BingeCatIds)

/** `SavedListAddResponse`: committed, already there, and unresolved counts. */
@JsonClass(generateAdapter = true)
data class BingeCatSavedAddResponse(
    val added: BingeCatSavedCounts? = null,
    val existing: BingeCatSavedCounts? = null,
    @Json(name = "not_found") val notFound: BingeCatNotFoundSelection? = null
)

/** `SavedListRemoveResponse`: what the removal deleted, and what it missed. */
@JsonClass(generateAdapter = true)
data class BingeCatSavedRemoveResponse(
    val deleted: BingeCatSavedCounts? = null,
    @Json(name = "not_found") val notFound: BingeCatNotFoundSelection? = null
)

/** The watchlist counts. A watchlist holds titles, so movies and shows only. */
@JsonClass(generateAdapter = true)
data class BingeCatSavedCounts(
    val movies: Int = 0,
    val shows: Int = 0
)

/** `ListItemsWriteRequest`: the media references to add or remove. */
@JsonClass(generateAdapter = true)
data class BingeCatListItemsWrite(val items: List<String>)

/**
 * `ListItemsAdded`: what the list took, already had, could not resolve, and
 * what it refused because the type disagreed (a show sent to a movie list).
 *
 * The three arrays are the write's own verdict - the picker's row reports the
 * boolean, and these are what the log and diagnostics name.
 */
@JsonClass(generateAdapter = true)
data class BingeCatListItemsAdded(
    val added: List<String> = emptyList(),
    val existing: List<String> = emptyList(),
    @Json(name = "not_found") val notFound: List<String> = emptyList(),
    @Json(name = "type_mismatch") val typeMismatch: List<String> = emptyList(),
    @Json(name = "item_count") val itemCount: Int = 0
) {
    /**
     * True when the title is NOT on the list after this call: every ref was
     * either unresolvable or refused for its type. An `existing` ref counts as
     * success - asking for a title the list already holds leaves the viewer's
     * intent satisfied, and a row that said "failed" there would be wrong.
     */
    val unchangedAndAbsent: Boolean
        get() = added.isEmpty() && existing.isEmpty()
}

/** `ListItemsRemoved`: what left the list, and what was not in it. */
@JsonClass(generateAdapter = true)
data class BingeCatListItemsRemoved(
    val removed: List<String> = emptyList(),
    @Json(name = "not_found") val notFound: List<String> = emptyList(),
    @Json(name = "item_count") val itemCount: Int = 0
)

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
