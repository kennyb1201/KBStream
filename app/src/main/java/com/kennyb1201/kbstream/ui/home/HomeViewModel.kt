package com.kennyb1201.kbstream.data.addon

import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AddonRepository {

    private data class CachedCatalog(
        val metas: List<MetaPreview>,
        val cachedAtMs: Long
    )

    private data class CachedMeta(
        val meta: Meta?,
        val cachedAtMs: Long
    )

    private val moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    private val logging =
        HttpLoggingInterceptor().apply {
            // BASIC logs the full request URL, and self-hosted addons can
            // carry an auth token baked into the path - never log that in
            // a release build.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(
                10,
                TimeUnit.SECONDS
            )
            .readTimeout(
                20,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                20,
                TimeUnit.SECONDS
            )
            .callTimeout(
                25,
                TimeUnit.SECONDS
            )
            .addInterceptor(logging)
            .build()

    private val api: StremioApiService =
        Retrofit.Builder()
            .baseUrl("https://example.com/")
            .client(client)
            .addConverterFactory(
                MoshiConverterFactory.create(moshi)
            )
            .build()
            .create(StremioApiService::class.java)

    private val catalogCache =
        mutableMapOf<String, CachedCatalog>()

    private val pendingCatalogRequests =
        mutableMapOf<String, CompletableDeferred<List<MetaPreview>>>()

    private val catalogMutex =
        Mutex()

    private val metaCache =
        mutableMapOf<String, CachedMeta>()

    private val pendingMetaRequests =
        mutableMapOf<String, CompletableDeferred<Meta?>>()

    private val metaMutex =
        Mutex()

    suspend fun fetchManifest(
        manifestUrl: String
    ): AddonManifest {
        return api.getManifest(
            manifestUrl
        )
    }

    /**
     * Loads one catalog page.
     *
     * [skip] is the number of items already shown for this catalog. A value of
     * zero loads the first page; use the current item count to request the
     * following page. The request is cached and duplicate in-flight calls for
     * the same page share one HTTP request.
     */
    suspend fun getCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int = 0
    ): List<MetaPreview> {
        val base =
            normalizeBaseUrl(
                baseUrl
            )

        val normalizedSkip =
            skip.coerceAtLeast(0)

        val cacheKey =
            "$base|$type|$catalogId|skip=$normalizedSkip"

        val now =
            System.currentTimeMillis()

        var requestToAwait:
            CompletableDeferred<List<MetaPreview>>? = null

        var requestToPerform:
            CompletableDeferred<List<MetaPreview>>? = null

        catalogMutex.withLock {
            cleanupExpiredCatalogCacheLocked(
                now
            )

            val cached =
                catalogCache[cacheKey]

            if (
                cached != null &&
                now - cached.cachedAtMs <
                CATALOG_CACHE_TTL_MS
            ) {
                return cached.metas
            }

            val pending =
                pendingCatalogRequests[cacheKey]

            if (pending != null) {
                requestToAwait =
                    pending
            } else {
                requestToPerform =
                    CompletableDeferred()

                pendingCatalogRequests[cacheKey] =
                    requestToPerform!!
            }
        }

        requestToAwait?.let { pending ->
            return pending.await()
        }

        val request =
            requireNotNull(
                requestToPerform
            )

        try {
            val url =
                buildCatalogUrl(
                    base = base,
                    type = type,
                    catalogId = catalogId,
                    skip = normalizedSkip
                )

            val metas =
                api.getCatalog(
                    url
                ).metas

            catalogMutex.withLock {
                catalogCache[cacheKey] =
                    CachedCatalog(
                        metas = metas,
                        cachedAtMs =
                            System.currentTimeMillis()
                    )

                pendingCatalogRequests.remove(
                    cacheKey
                )

                cleanupExpiredCatalogCacheLocked(
                    System.currentTimeMillis()
                )
            }

            request.complete(
                metas
            )

            return metas
        } catch (throwable: Throwable) {
            catalogMutex.withLock {
                pendingCatalogRequests.remove(
                    cacheKey
                )
            }

            request.completeExceptionally(
                throwable
            )

            throw throwable
        }
    }

    suspend fun searchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        query: String
    ): List<MetaPreview> {
        val base =
            normalizeBaseUrl(
                baseUrl
            )

        val encoded =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        return api.getCatalog(
            "$base/catalog/$type/$catalogId/search=$encoded.json"
        ).metas
    }

    suspend fun getMeta(
        baseUrl: String,
        type: String,
        id: String
    ): Meta? {
        val base =
            normalizeBaseUrl(
                baseUrl
            )

        val cacheKey =
            "$base|$type|$id"

        val now =
            System.currentTimeMillis()

        var requestToAwait:
            CompletableDeferred<Meta?>? = null

        var requestToPerform:
            CompletableDeferred<Meta?>? = null

        metaMutex.withLock {
            cleanupExpiredMetaCacheLocked(
                now
            )

            val cached =
                metaCache[cacheKey]

            if (
                cached != null &&
                now - cached.cachedAtMs <
                META_CACHE_TTL_MS
            ) {
                return cached.meta
            }

            val pending =
                pendingMetaRequests[cacheKey]

            if (pending != null) {
                requestToAwait =
                    pending
            } else {
                requestToPerform =
                    CompletableDeferred()

                pendingMetaRequests[cacheKey] =
                    requestToPerform!!
            }
        }

        requestToAwait?.let { pending ->
            return pending.await()
        }

        val request =
            requireNotNull(
                requestToPerform
            )

        try {
            val url =
                "$base/meta/$type/$id.json"

            val meta =
                api.getMeta(
                    url
                ).meta

            metaMutex.withLock {
                metaCache[cacheKey] =
                    CachedMeta(
                        meta = meta,
                        cachedAtMs =
                            System.currentTimeMillis()
                    )

                pendingMetaRequests.remove(
                    cacheKey
                )

                cleanupExpiredMetaCacheLocked(
                    System.currentTimeMillis()
                )
            }

            request.complete(
                meta
            )

            return meta
        } catch (throwable: Throwable) {
            metaMutex.withLock {
                pendingMetaRequests.remove(
                    cacheKey
                )
            }

            request.completeExceptionally(
                throwable
            )

            throw throwable
        }
    }

    suspend fun getStreams(
        baseUrl: String,
        type: String,
        id: String
    ): List<Stream> {
        val base =
            normalizeBaseUrl(
                baseUrl
            )

        return api.getStreams(
            "$base/stream/$type/$id.json"
        ).streams
    }

    suspend fun clearCatalogCache() {
        catalogMutex.withLock {
            catalogCache.clear()
        }
    }

    suspend fun clearMetaCache() {
        metaMutex.withLock {
            metaCache.clear()
        }
    }

    private fun buildCatalogUrl(
        base: String,
        type: String,
        catalogId: String,
        skip: Int
    ): String {
        val extra =
            if (skip > 0) {
                "/skip=$skip"
            } else {
                ""
            }

        return "$base/catalog/$type/$catalogId$extra.json"
    }

    private fun cleanupExpiredCatalogCacheLocked(
        nowMs: Long
    ) {
        catalogCache.entries.removeAll { entry ->
            nowMs - entry.value.cachedAtMs >=
                CATALOG_CACHE_TTL_MS
        }

        if (
            catalogCache.size <=
            MAX_CATALOG_CACHE_ENTRIES
        ) {
            return
        }

        val keysToRemove =
            catalogCache.entries
                .sortedBy {
                    it.value.cachedAtMs
                }
                .take(
                    catalogCache.size -
                        MAX_CATALOG_CACHE_ENTRIES
                )
                .map {
                    it.key
                }

        keysToRemove.forEach { key ->
            catalogCache.remove(
                key
            )
        }
    }

    private fun cleanupExpiredMetaCacheLocked(
        nowMs: Long
    ) {
        metaCache.entries.removeAll { entry ->
            nowMs - entry.value.cachedAtMs >=
                META_CACHE_TTL_MS
        }

        if (
            metaCache.size <=
            MAX_META_CACHE_ENTRIES
        ) {
            return
        }

        val keysToRemove =
            metaCache.entries
                .sortedBy {
                    it.value.cachedAtMs
                }
                .take(
                    metaCache.size -
                        MAX_META_CACHE_ENTRIES
                )
                .map {
                    it.key
                }

        keysToRemove.forEach { key ->
            metaCache.remove(
                key
            )
        }
    }

    private fun normalizeBaseUrl(
        url: String
    ): String {
        val cleanUrl =
            url.trim()
                .removeSuffix("/")

        val manifestIndex =
            cleanUrl.indexOf(
                "/manifest.json"
            )

        return if (manifestIndex >= 0) {
            cleanUrl.substring(
                0,
                manifestIndex
            )
        } else {
            cleanUrl.substringBefore(
                "?"
            )
        }
    }

    private companion object {
        private const val CATALOG_CACHE_TTL_MS =
            10 * 60 * 1000L

        private const val MAX_CATALOG_CACHE_ENTRIES =
            500

        // Meta objects change far less often than a catalog page (cast,
        // synopsis, etc. are essentially static), so a longer TTL is safe.
        private const val META_CACHE_TTL_MS =
            30 * 60 * 1000L

        private const val MAX_META_CACHE_ENTRIES =
            300
    }
}
