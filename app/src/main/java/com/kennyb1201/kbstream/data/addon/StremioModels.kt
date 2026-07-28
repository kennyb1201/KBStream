package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null
)

@JsonClass(generateAdapter = true)
data class CatalogResponse(
    val metas: List<MetaPreview> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null
)

@JsonClass(generateAdapter = true)
data class MetaResponse(
    val meta: Meta? = null
)

@JsonClass(generateAdapter = true)
data class Stream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    @Json(name = "infoHash") val infoHash: String? = null,
    val fileIdx: Int? = null
)

@JsonClass(generateAdapter = true)
data class StreamResponse(
    val streams: List<Stream> = emptyList()
)
