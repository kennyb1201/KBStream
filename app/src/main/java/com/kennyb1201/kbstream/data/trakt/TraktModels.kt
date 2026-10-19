package com.kennyb1201.kbstream.data.trakt

import com.squareup.moshi.Json

/** `POST /oauth/device/code` - what the viewer is shown and what we poll with. */
data class TraktDeviceCodeResponse(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Int = 600,
    @Json(name = "interval") val intervalSeconds: Int = 5
)

/** A granted token pair. Trakt's access tokens last 7 days. */
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
data class TraktUserSettings(
    @Json(name = "user") val user: TraktUser? = null
)

data class TraktUser(
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "ids") val ids: TraktUserIds? = null
)

data class TraktUserIds(
    @Json(name = "slug") val slug: String? = null
)

/** Body of `POST /oauth/device/code`. */
data class TraktDeviceCodeRequest(
    @Json(name = "client_id") val clientId: String
)

/**
 * Body of `POST /oauth/device/token`.
 *
 * No `client_secret`: the device flow is a public-client flow and this app
 * cannot ship a secret (see [TraktConfig]).
 */
data class TraktDeviceTokenRequest(
    @Json(name = "code") val code: String,
    @Json(name = "client_id") val clientId: String
)

/** Body of `POST /oauth/token` (the refresh grant). */
data class TraktRefreshRequest(
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "redirect_uri") val redirectUri: String = TraktConfig.REDIRECT_URI,
    @Json(name = "grant_type") val grantType: String = "refresh_token"
)

/** Body of `POST /oauth/revoke`. */
data class TraktRevokeRequest(
    @Json(name = "token") val token: String,
    @Json(name = "client_id") val clientId: String
)
