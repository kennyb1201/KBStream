package com.kennyb1201.kbstream.data.addon

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

    private val moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    private val logging =
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
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

        val url =
            "$base/meta/$type/$id.json"

        android.util.Log.e(
            "KBStream",
            "BingeCat meta URL: $url"
        )

        return api.getMeta(
            url
        ).meta
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
    }
}
