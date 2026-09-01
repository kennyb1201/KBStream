package com.kennyb1201.kbstream.data.tmdb

import android.content.Context
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves transparent TMDB logos and clean title-free backdrops for the Home Hero.
 * Results are cached in memory (session) and on disk (shared tmdb_json_cache Room
 * table), so re-showing an item in the hero is instant and never re-hits the
 * network. The blocking OkHttp call is dispatched to Dispatchers.IO.
 */
class TmdbHeroArtworkRepository(
    context: Context? = null
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val imagesAdapter = moshi.adapter(TmdbImagesResponse::class.java)
    private val artworkAdapter = moshi.adapter(HeroArtwork::class.java)

    private val tmdbJsonCacheDao: TmdbJsonCacheDao? = context
        ?.applicationContext
        ?.let {
            WatchHistoryDatabase.getInstance(it).tmdbJsonCacheDao()
        }

    private val mutex = Mutex()

    // fetchedAt -> artwork, keyed by "<mediaType>:<tmdbId>"
    private val memoryCache = HashMap<String, Pair<Long, HeroArtwork>>()

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

        val key = "$mediaType:$resolvedTmdbId"
        val now = System.currentTimeMillis()

        return mutex.withLock {
            // In-memory cache (fast path for the current session).
            memoryCache[key]?.let { (fetchedAt, artwork) ->
                if (now - fetchedAt < MEMORY_CACHE_TTL_MS) {
                    return@withLock artwork
                }
                memoryCache.remove(key)
            }

            // Disk cache so resolved artwork survives restarts.
            val diskCached = runCatching {
                tmdbJsonCacheDao?.getByKey(DISK_KEY_PREFIX + key)
            }.getOrNull()

            if (diskCached != null && now - diskCached.updatedAt < DISK_CACHE_TTL_MS) {
                val parsed = runCatching {
                    artworkAdapter.fromJson(diskCached.json)
                }.getOrNull()

                if (parsed != null) {
                    memoryCache[key] = now to parsed
                    return@withLock parsed
                }
            }

            // Network fetch (blocking OkHttp, so off the main thread).
            val artwork = withContext(Dispatchers.IO) {
                runCatching {
                    fetchFromNetwork(mediaType, resolvedTmdbId)
                }.getOrNull()
            }

            if (artwork != null) {
                memoryCache[key] = now to artwork

                runCatching {
                    tmdbJsonCacheDao?.upsert(
                        TmdbJsonCacheEntity(
                            key = DISK_KEY_PREFIX + key,
                            json = artworkAdapter.toJson(artwork),
                            updatedAt = now
                        )
                    )
                }
            }

            artwork
        }
    }

    private fun fetchFromNetwork(
        mediaType: String,
        tmdbId: Int
    ): HeroArtwork? {

        // No include_image_language filter: some titles (esp. newer/international ones
        // in trending feeds like Top Today) have no English or null-language logo on
        // TMDB, which made this call return an empty `logos` array for them -- silently
        // dropping the clearlogo with no fallback. The sortedWith below already prefers
        // an English logo when one exists, then falls back to the next-best option
        // instead of nothing.
        val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId/images" +
            "?api_key=${BuildConfig.TMDB_API_KEY}"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return runCatching {
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

    private companion object {
        const val MEMORY_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
        const val DISK_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        const val DISK_KEY_PREFIX = "hero_artwork:"
    }
}

data class HeroArtwork(
    val backdropUrl: String?,
    val logoUrl: String?
)
