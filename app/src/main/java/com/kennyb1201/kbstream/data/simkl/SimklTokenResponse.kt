package com.kennyb1201.kbstream.data.simkl

import com.squareup.moshi.Json

data class SimklTokenResponse(
    @Json(name = "access_token")
    val accessToken: String? = null,

    @Json(name = "token_type")
    val tokenType: String? = null,

    @Json(name = "scope")
    val scope: String? = null,

    @Json(name = "created_at")
    val createdAt: Long? = null
)
