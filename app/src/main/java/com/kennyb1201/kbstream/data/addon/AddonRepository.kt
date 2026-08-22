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
        mutableMapOf<String, List<MetaPreview>>()

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

    suspend fun getCatalog(
        baseUrl: String,
        type: String,
        catalogId: String
    ): List<MetaPreview> {
        val base = normalizeBaseUrl(baseUrl)
        val cacheKey = "$base|$type|$catalogId"

        var requestToAwait: CompletableDeferred<List<MetaPreview>>? = null
        var requestToPerform: CompletableDeferred<List<MetaPreview>>? = null

        catalogMutex.withLock {
            catalogCache[cacheKey]?.let { cached ->
                return cached
            }

            val pending = pendingCatalogRequests[cacheKey]

            if (pending != null) {
                requestToAwait = pending
            } else {
                requestToPerform = CompletableDeferred()
                pendingCatalogRequests[cacheKey] = requestToPerform!!
            }
        }

        requestToAwait?.let { pending ->
            return pending.await()
        }

        val request = requireNotNull(requestToPerform)

        try {
            val metas =
                api.getCatalog(
                    "$base/catalog/$type/$catalogId.json"
                ).metas

            catalogMutex.withLock {
                catalogCache[cacheKey] = metas
                pendingCatalogRequests.remove(cacheKey)
            }

            request.complete(metas)

            return metas
        } catch (throwable: Throwable) {
            catalogMutex.withLock {
                pendingCatalogRequests.remove(cacheKey)
            }

            request.completeExceptionally(throwable)

            throw throwable
        }
    }

    suspend fun searchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        query: String
    ): List<MetaPreview> {
        val base = normalizeBaseUrl(baseUrl)

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
        val base = normalizeBaseUrl(baseUrl)
        val url = "$base/meta/$type/$id.json"

        android.util.Log.e(
            "KBStream",
            "BingeCat meta URL: $url"
        )

        return api.getMeta(url).meta
    }

    suspend fun getStreams(
        baseUrl: String,
        type: String,
        id: String
    ): List<Stream> {
        val base = normalizeBaseUrl(baseUrl)

        return api.getStreams(
            "$base/stream/$type/$id.json"
        ).streams
    }

    fun clearCatalogCache() {
        catalogCache.clear()
    }

    private fun normalizeBaseUrl(url: String): String {
        val cleanUrl =
            url.trim().removeSuffix("/")

        val manifestIndex =
            cleanUrl.indexOf("/manifest.json")

        return if (manifestIndex >= 0) {
            cleanUrl.substring(
                0,
                manifestIndex
            )
        } else {
            cleanUrl.substringBefore("?")
        }
    }
}
