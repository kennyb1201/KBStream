package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TmdbRepository {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val api: TmdbApiService = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApiService::class.java)

    private val apiKey = BuildConfig.TMDB_API_KEY

    suspend fun fetchEnrichedMeta(imdbId: String, type: String): TmdbDetail? {
        if (apiKey.isBlank()) return null
        val found = api.find(imdbId, apiKey)
        return if (type == "series") {
            val tmdbId = found.tvResults.firstOrNull()?.id ?: return null
            api.getTv(tmdbId, apiKey)
        } else {
            val tmdbId = found.movieResults.firstOrNull()?.id ?: return null
            api.getMovie(tmdbId, apiKey)
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? {
        if (apiKey.isBlank()) return null
        val ext = if (type == "series") api.getTvExternalIds(tmdbId, apiKey) else api.getMovieExternalIds(tmdbId, apiKey)
        return ext.imdbId
    }

    suspend fun getPerson(personId: Int): TmdbPersonDetail? {
        if (apiKey.isBlank()) return null
        return api.getPerson(personId, apiKey)
    }

    companion object {
        const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/original"
    }
}
