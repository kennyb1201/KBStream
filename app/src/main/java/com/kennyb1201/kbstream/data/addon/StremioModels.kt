package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type

@JsonClass(generateAdapter = true)
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val logo: String? = null
)

@JsonClass(generateAdapter = true)
data class CatalogResponse(
    val metas: List<MetaPreview> = emptyList()
)

@JsonClass(generateAdapter = true)
data class VideoEntry(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null
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
    val description: String? = null,
    val url: String? = null,

    @Json(name = "audioUrl")
    val audioUrl: String? = null,

    @Json(name = "infoHash")
    val infoHash: String? = null,

    val fileIdx: Int? = null,

    @Json(name = "drm")
    val drm: StreamDrm? = null
)

@JsonClass(generateAdapter = true)
data class StreamDrm(
    val type: String? = null,
    val licenseUrl: String? = null,
    val headers: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class StreamResponse(
    val streams: List<Stream> = emptyList()
)

/**
 * A catalog exactly as it appears in an addon manifest.
 *
 * showOnHome and order are KBStream-local settings.
 *
 * They are NOT sent back to the addon.
 */
@JsonClass(generateAdapter = true)
data class ManifestCatalog(
    val type: String,
    val id: String,
    val name: String,

    val showOnHome: Boolean = true,

    val order: Int = 0,

    /**
     * User-facing display-name override set from the catalog manager.
     * When null the manifest name is used.
     */
    val customName: String? = null
) {
    val displayName: String
        get() = customName ?: name
}

@JsonClass(generateAdapter = true)
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,

    /**
     * Stremio manifests may list each resource as either a plain string
     * ("catalog") or an object ({"name": "catalog", "idPrefixes": [...]};
     * AIOStreams and other addons use the object form). [ManifestResources]
     * normalizes both forms to plain names.
     */
    @ManifestResources
    val resources: List<String> = emptyList(),

    val types: List<String> = emptyList(),
    val catalogs: List<ManifestCatalog> = emptyList(),
    val logo: String? = null,
    val icon: String? = null
)

/**
 * Marks a list whose entries may be Stremio resource strings or resource
 * objects, so [ManifestResourcesAdapter] can normalize them.
 */
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class ManifestResources

/**
 * Parses a Stremio manifest "resources" array whose entries may be either
 * strings or objects, returning every entry's name string.
 */
class ManifestResourcesAdapter : JsonAdapter<List<String>>() {

    override fun fromJson(reader: JsonReader): List<String>? {
        if (reader.peek() == JsonReader.Token.NULL) {
            return reader.nextNull()
        }
        val raw = reader.readJsonValue() as? List<*> ?: return emptyList()
        return raw.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Map<*, *> -> entry["name"] as? String
                else -> null
            }
        }
    }

    override fun toJson(writer: JsonWriter, value: List<String>?) {
        writer.jsonValue(value)
    }
}

/**
 * Moshi factory that only intercepts fields annotated [ManifestResources], so
 * every other List field keeps its normal adapter.
 */
object ManifestResourcesAdapterFactory : JsonAdapter.Factory {

    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        return if (annotations.any { it is ManifestResources }) {
            ManifestResourcesAdapter()
        } else {
            null
        }
    }
}

/**
 * Installed addon configuration.
 */
@JsonClass(generateAdapter = true)
data class InstalledAddon(
    val manifestUrl: String,
    val id: String,
    val name: String,
    val resources: List<String>,
    val catalogs: List<ManifestCatalog>,

    /**
     * User-facing addon name override.
     */
    val customName: String? = null,

    /**
     * Cached manifest information.
     */
    val version: String? = null,
    val description: String? = null,
    val types: List<String> = emptyList(),

    /**
     * Addon logo/icon URL from the manifest, used for the tile artwork.
     */
    val logo: String? = null
) {
    val displayName: String
        get() = customName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: name
}
