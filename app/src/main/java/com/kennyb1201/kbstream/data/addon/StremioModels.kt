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
data class VideoEntry(
    val id: String,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null
)

@JsonClass(generateAdapter = true)
data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val language: String? = null,
    val country: String? = null,
    val awards: String? = null,
    val website: String? = null,
    val genres: List<String>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val videos: List<VideoEntry>? = null
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

@JsonClass(generateAdapter = true)
data class ManifestCatalog(
    val type: String,
    val id: String,
    val name: String
)

@JsonClass(generateAdapter = true)
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val resources: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<ManifestCatalog> = emptyList()
)

@JsonClass(generateAdapter = true)
data class InstalledAddon(
    val manifestUrl: String,
    val id: String,
    val name: String,
    val resources: List<String>,
    val catalogs: List<ManifestCatalog>
)
