package com.kennyb1201.kbstream.data.addon

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

interface StremioApiService {

    @GET
    suspend fun getCatalog(
        @Url url: String
    ): CatalogResponse

    @GET
    suspend fun getMeta(
        @Url url: String
    ): MetaResponse

    @GET
    suspend fun getStreams(
        @Url url: String
    ): StreamResponse

    @GET
    @Headers("Cache-Control: no-cache")
    suspend fun getManifest(
        @Url url: String
    ): AddonManifest
}
