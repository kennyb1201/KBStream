package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves transparent TMDB logos and clean title-free backdrops for the Home Hero.
 * This is deliberately separate from the main TMDB repository so the existing
 * TMDB API service does not need to change.
 */
class TmdbHeroArtworkRepository {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val imagesAdapter = moshi.adapter(TmdbImagesResponse::class.java)

    suspend fun resolve(
        id: String,
        type: String,
        tmdbId: Int?
    ): HeroArtwork? {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return null

        val resolvedTmdbId = tmdbId ?: when {
            id.startsWith("tmdb:", ignoreCase = true) ->
                id.substringAfter(":").toIntOrNull()
            else -> null
        } ?: return null

        val mediaType = when (type.lowercase()) {
            "series", "show", "tv" -> "tv"
            else -> "movie"
        }

        // No include_image_language filter: some titles (esp. newer/international ones
        // in trending feeds like Top Today) have no English or null-language logo on
        // TMDB, which made this call return an empty `logos` array for them -- silently
        // dropping the clearlogo with no fallback. The sortedWith below already prefers
        // an English logo when one exists, then falls back to the next-best option
        // instead of nothing.
        val url = "https://api.themoviedb.org/3/$mediaType/$resolvedTmdbId/images" +
            "?api_key=${BuildConfig.TMDB_API_KEY}"

        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val json = response.body?.string().orEmpty()
                val images = imagesAdapter.fromJson(json) ?: return@use null

                val backdrop = images.backdrops
                    .firstOrNull { !it.filePath.isNullOrBlank() }
                    ?.filePath
                    ?.let { TmdbRepository.BACKDROP_BASE + it }

                val logo = images.logos
                    .filter { !it.filePath.isNullOrBlank() }
                    .sortedWith(
                        compareByDescending<TmdbImage> { it.iso6391 == "en" }
                            .thenByDescending { it.voteAverage ?: 0.0 }
                            .thenByDescending { it.width ?: 0 }
                    )
                    .firstOrNull()
                    ?.filePath
                    ?.let { TmdbRepository.LOGO_BASE + it }

                HeroArtwork(
                    backdropUrl = backdrop,
                    logoUrl = logo
                )
            }
        }.getOrNull()
    }

    @JsonClass(generateAdapter = true)
    private data class TmdbImagesResponse(
        val backdrops: List<TmdbImage> = emptyList(),
        val logos: List<TmdbImage> = emptyList()
    )

    @JsonClass(generateAdapter = true)
    private data class TmdbImage(
        @Json(name = "file_path") val filePath: String? = null,
        @Json(name = "iso_639_1") val iso6391: String? = null,
        @Json(name = "vote_average") val voteAverage: Double? = null,
        val width: Int? = null
    )
}

data class HeroArtwork(
    val backdropUrl: String?,
    val logoUrl: String?
)
