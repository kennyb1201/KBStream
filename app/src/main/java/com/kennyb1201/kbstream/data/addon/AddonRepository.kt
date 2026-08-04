package com.kennyb1201.kbstream.data.addon

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AddonRepository {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .addInterceptor(logging)
        .build()

    private val api: StremioApiService = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(StremioApiService::class.java)

    suspend fun fetchManifest(manifestUrl: String): AddonManifest =
        api.getManifest(manifestUrl)

    suspend fun getCatalog(baseUrl: String, type: String, catalogId: String): List<MetaPreview> =
        api.getCatalog("$baseUrl/catalog/$type/$catalogId.json").metas

    suspend fun searchCatalog(baseUrl: String, type: String, catalogId: String, query: String): List<MetaPreview> =
        api.getCatalog("$baseUrl/catalog/$type/$catalogId/search=${java.net.URLEncoder.encode(query, "UTF-8")}.json").metas

    suspend fun getMeta(baseUrl: String, type: String, id: String): Meta? =
        api.getMeta("$baseUrl/meta/$type/$id.json").meta

    suspend fun getStreams(baseUrl: String, type: String, id: String): List<Stream> =
        api.getStreams("$baseUrl/stream/$type/$id.json").streams
}
