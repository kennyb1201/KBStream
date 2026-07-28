package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AddonRepository(
    // Cinemeta: catalog + meta only, no key needed
    private val metaAddonUrl: String = "https://v3-cinemeta.strem.io",
    // AIOStreams: your Oracle-hosted aggregator, user-scoped path with embedded auth token
    private val streamAddonUrl: String = "http://132.145.137.148:8080/stremio/67f82e67-ed57-4cef-bf0b-32b7386fae01/eyJpIjoiamlEcExKUGljdnpZUkRHUEcxWTRuUT09IiwiZSI6Ik9FeEFFY0QxYlpXMktzVkV3UGo4YUY4MUdtY2w4ZEVwT2hEWlo3enNBQ3M9IiwidCI6ImEifQ"
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val api: StremioApiService = Retrofit.Builder()
        .baseUrl("https://example.com/") // unused -- every call supplies a full @Url
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(StremioApiService::class.java)

    suspend fun getCatalog(type: String, catalogId: String): List<MetaPreview> {
        val url = "$metaAddonUrl/catalog/$type/$catalogId.json"
        return api.getCatalog(url).metas
    }

    suspend fun getMeta(type: String, id: String): Meta? {
        val url = "$metaAddonUrl/meta/$type/$id.json"
        return api.getMeta(url).meta
    }

    suspend fun getStreams(type: String, id: String): List<Stream> {
        val url = "$streamAddonUrl/stream/$type/$id.json"
        return api.getStreams(url).streams
    }
}
