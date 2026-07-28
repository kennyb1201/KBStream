package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AddonRepository(
    // Cinemeta needs no API key -- good default/test addon
    private val addonBaseUrl: String = "https://v3-cinemeta.strem.io"
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
        val url = "$addonBaseUrl/catalog/$type/$catalogId.json"
        return api.getCatalog(url).metas
    }

    suspend fun getMeta(type: String, id: String): Meta? {
        val url = "$addonBaseUrl/meta/$type/$id.json"
        return api.getMeta(url).meta
    }

    suspend fun getStreams(type: String, id: String): List<Stream> {
        val url = "$addonBaseUrl/stream/$type/$id.json"
        return api.getStreams(url).streams
    }
}
