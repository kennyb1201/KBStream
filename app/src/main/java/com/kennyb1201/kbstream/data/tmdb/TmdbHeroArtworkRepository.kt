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

    // The process-wide TMDB client (shared with TmdbRepository): reuses its
    // pooled connections so a hero-artwork fetch after ANY other TMDB call
    // (rails, detail, prefetch) rides an already-warm TLS session instead of
    // paying its own cold handshake on a private client.
    private val client get() = TmdbRepository.sharedOkHttpClient()

    private val imagesAdapter = moshi.adapter(TmdbImagesResponse::class.java)
    private val artworkAdapter = moshi.adapter(HeroArtwork::class.java)

    private val tmdbJsonCacheDao: TmdbJsonCacheDao? = context
        ?.applicationContext
        ?.let {
            WatchHistoryDatabase.getInstance(it).tmdbJsonCacheDao()
        }

    private val mutex = Mutex()

    // fetchedAt -> artwork, keyed by "<mediaType>:<tmdbId>". Capped: an
    // evening of browsing resolves hundreds of heroes, and an unbounded
    // map kept every one of them alive for the whole process. Over the cap
    // we drop expired entries first, then the oldest-fetched remainder.
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
                pruneMemoryCache(now)

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

        // English + textless images only (`null` is TMDB's textless marker).
        // The hero is the largest art on screen, so a non-English backdrop or
        // clearlogo cannot be missed: before this filter the backdrop was just
        // the first entry in TMDB's list -- often a foreign release's artwork
        // with its own title burned in -- and the logo fell through to
        // "highest vote of any language" whenever no English wordmark existed,
        // which is what put foreign clearlogos in the hero.
        //
        // Removing the filter entirely was an earlier attempt at a different
        // problem (a title with no English logo silently losing its clearlogo).
        // It is not needed: `null` keeps the textless logos, and a title with
        // neither an English nor a textless image is rare -- when it happens the
        // hero's own fallback chain (addon/item logo, then the plain title)
        // covers it. Verified against TMDB that international titles still
        // return their en/null art under this filter.
        val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId/images" +
            "?api_key=${BuildConfig.TMDB_API_KEY}" +
            "&include_image_language=en,null"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val json = response.body?.string().orEmpty()
                val images = imagesAdapter.fromJson(json) ?: return@use null

                // Textless first: a backdrop with words on it is the title's
                // own locale art, and the hero already draws the clearlogo and
                // metadata over the image. English is the next preference.
                val backdrop = images.backdrops
                    .filter { !it.filePath.isNullOrBlank() }
                    .sortedWith(
                        compareByDescending<TmdbImage> { it.iso6391 == null }
                            .thenByDescending { it.iso6391 == "en" }
                            .thenByDescending { it.voteAverage ?: 0.0 }
                            .thenByDescending { it.width ?: 0 }
                    )
                    .firstOrNull()
                    ?.filePath
                    ?.let { TmdbRepository.BACKDROP_BASE + it }

                // English wordmark first, then the textless one, then the best
                // of whatever the language filter returned.
                val logo = images.logos
                    .filter { !it.filePath.isNullOrBlank() }
                    .sortedWith(
                        compareByDescending<TmdbImage> { it.iso6391 == "en" }
                            .thenByDescending { it.iso6391 == null }
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
        // Bumped from "hero_artwork:" when the language filter landed: the
        // disk cache held 30 days of foreign-language backdrops/logos, and a
        // key bump drops them all at once instead of serving them until they
        // expire.
        const val DISK_KEY_PREFIX = "hero_artwork_en:"
        const val MEMORY_CACHE_MAX_ENTRIES = 128
    }

    /**
     * Keeps [memoryCache] bounded: expired entries go first, then the
     * oldest-fetched remainder. Called with the mutex held.
     */
    private fun pruneMemoryCache(now: Long) {
        if (memoryCache.size <= MEMORY_CACHE_MAX_ENTRIES) return
        memoryCache.entries.removeAll { now - it.value.first >= MEMORY_CACHE_TTL_MS }
        if (memoryCache.size <= MEMORY_CACHE_MAX_ENTRIES) return
        memoryCache.entries
            .sortedBy { it.value.first }
            .take(memoryCache.size - MEMORY_CACHE_MAX_ENTRIES)
            .forEach { memoryCache.remove(it.key) }
    }
}

data class HeroArtwork(
    val backdropUrl: String?,
    val logoUrl: String?
)
