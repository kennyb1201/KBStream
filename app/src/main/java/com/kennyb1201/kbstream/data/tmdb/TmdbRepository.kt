package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

data class StudioItem(val item: TmdbDiscoverItem, val mediaType: String)
data class StudioSection(val title: String, val items: List<StudioItem>)

class TmdbRepository {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val api: TmdbApiService = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApiService::class.java)

    private val apiKey = BuildConfig.TMDB_API_KEY
    private val pagesPerRail = 3

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

    // Cinemeta's per-episode data has no runtime -- TMDB's season endpoint does
    suspend fun getSeasonRuntimes(tvId: Int, season: Int): Map<Int, Int> {
        if (apiKey.isBlank()) return emptyMap()
        return runCatching {
            api.getSeasonDetail(tvId, season, apiKey).episodes
                .mapNotNull { ep -> ep.runtime?.let { ep.episodeNumber to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    suspend fun getByCompany(companyId: Int): List<StudioSection> {
        if (apiKey.isBlank()) return emptyList()

        suspend fun fetchMovies(sortBy: String, voteCountGte: Int?): List<StudioItem> =
            (1..pagesPerRail).flatMap { page ->
                runCatching { api.discoverMovieByCompany(companyId, apiKey, sortBy, voteCountGte, page).results }.getOrDefault(emptyList())
            }.distinctBy { it.id }.map { StudioItem(it, "movie") }

        suspend fun fetchShows(sortBy: String, voteCountGte: Int?): List<StudioItem> =
            (1..pagesPerRail).flatMap { page ->
                runCatching { api.discoverTvByCompany(companyId, apiKey, sortBy, voteCountGte, page).results }.getOrDefault(emptyList())
            }.distinctBy { it.id }.map { StudioItem(it, "series") }

        return listOf(
            StudioSection("MOVIES · POPULAR", fetchMovies("popularity.desc", null)),
            StudioSection("MOVIES · TOP RATED", fetchMovies("vote_average.desc", 100)),
            StudioSection("MOVIES · RECENT", fetchMovies("primary_release_date.desc", null)),
            StudioSection("SERIES · POPULAR", fetchShows("popularity.desc", null)),
            StudioSection("SERIES · TOP RATED", fetchShows("vote_average.desc", 100)),
            StudioSection("SERIES · RECENT", fetchShows("first_air_date.desc", null))
        ).filter { it.items.isNotEmpty() }
    }

    suspend fun getByNetwork(networkId: Int): List<StudioSection> {
        if (apiKey.isBlank()) return emptyList()

        suspend fun fetch(sortBy: String, voteCountGte: Int?): List<StudioItem> =
            (1..pagesPerRail).flatMap { page ->
                runCatching { api.discoverByNetwork(networkId, apiKey, sortBy, voteCountGte, page).results }.getOrDefault(emptyList())
            }.distinctBy { it.id }.map { StudioItem(it, "series") }

        return listOf(
            StudioSection("SERIES · POPULAR", fetch("popularity.desc", null)),
            StudioSection("SERIES · TOP RATED", fetch("vote_average.desc", 100)),
            StudioSection("SERIES · RECENT", fetch("first_air_date.desc", null))
        ).filter { it.items.isNotEmpty() }
    }

    companion object {
        const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/original"
    }
}
