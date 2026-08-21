package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
            level =
                HttpLoggingInterceptor.Level.BASIC
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

        val base =
            normalizeBaseUrl(baseUrl)

        return api.getCatalog(
            "$base/catalog/$type/$catalogId.json"
        ).metas
    }

    suspend fun searchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        query: String
    ): List<MetaPreview> {

        val base =
            normalizeBaseUrl(baseUrl)

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
            normalizeBaseUrl(baseUrl)

        return api.getMeta(
            "$base/meta/$type/$id.json"
        ).meta
    }

    suspend fun getStreams(
        baseUrl: String,
        type: String,
        id: String
    ): List<Stream> {

        val base =
            normalizeBaseUrl(baseUrl)

        return api.getStreams(
            "$base/stream/$type/$id.json"
        ).streams
    }

    private fun normalizeBaseUrl(
        url: String
    ): String {

        return url
            .trim()
            .removeSuffix("/")
            .removeSuffix("/manifest.json")
            .removeSuffix("/")
    }
}
