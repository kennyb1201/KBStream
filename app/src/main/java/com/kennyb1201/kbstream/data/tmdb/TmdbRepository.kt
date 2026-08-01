package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.LocalDate

data class StudioItem(val item: TmdbDiscoverItem, val mediaType: String)
data class StudioSection(val title: String, val items: List<StudioItem>)

data class TagRailPage(
    val items: List<StudioItem>,
    val hasMore: Boolean
)

data class ResolvedEpisode(
    val streamId: String,
    val episodeNumber: Int,
    val name: String?,
    val overview: String?,
    val thumbnail: String?,
    val runtimeMinutes: Int?
)

class TmdbRepository {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val api: TmdbApiService = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApiService::class.java)

    private val apiKey = BuildConfig.TMDB_API_KEY
    private val minVoteCount = 50
    private val today: String get() = LocalDate.now().toString()

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

    suspend fun getCollection(collectionId: Int): TmdbCollectionDetail? {
        if (apiKey.isBlank()) return null
        return runCatching { api.getCollection(collectionId, apiKey) }.getOrNull()
    }

    suspend fun getSeasonEpisodes(tvId: Int, season: Int, imdbId: String): List<ResolvedEpisode> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching {
            api.getSeasonDetail(tvId, season, apiKey).episodes.map { ep ->
                ResolvedEpisode(
                    streamId = "$imdbId:$season:${ep.episodeNumber}",
                    episodeNumber = ep.episodeNumber,
                    name = ep.name,
                    overview = ep.overview,
                    thumbnail = ep.stillPath?.let { "$PROFILE_BASE$it" },
                    runtimeMinutes = ep.runtime
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getByCompany(companyId: Int): List<StudioSection> {
        if (apiKey.isBlank()) return emptyList()

        suspend fun fetchMovies(sortBy: String, voteCountGte: Int?): List<StudioItem> =
            (1..3).flatMap { page ->
                runCatching {
                    api.discoverMovieByCompany(
                        companyId = companyId,
                        apiKey = apiKey,
                        sortBy = sortBy,
                        voteCountGte = voteCountGte ?: minVoteCount,
                        releaseDateLte = today,
                        page = page
                    ).results
                }.getOrDefault(emptyList())
            }.distinctBy { it.id }.map { StudioItem(it, "movie") }

        suspend fun fetchShows(sortBy: String, voteCountGte: Int?): List<StudioItem> =
            (1..3).flatMap { page ->
                runCatching {
                    api.discoverTvByCompany(
                        companyId = companyId,
                        apiKey = apiKey,
                        sortBy = sortBy,
                        voteCountGte = voteCountGte ?: minVoteCount,
                        firstAirDateLte = today,
                        page = page
                    ).results
                }.getOrDefault(emptyList())
            }.distinctBy { it.id }.map { StudioItem(it, "series") }

        return listOf(
            StudioSection("MOVIES · RECENT", fetchMovies("primary_release_date.desc", null)),
            StudioSection("MOVIES · POPULAR", fetchMovies("popularity.desc", null)),
            StudioSection("MOVIES · TOP RATED", fetchMovies("vote_average.desc", 100)),
            StudioSection("SERIES · RECENT", fetchShows("first_air_date.desc", null)),
            StudioSection("SERIES · POPULAR", fetchShows("popularity.desc", null)),
            StudioSection("SERIES · TOP RATED", fetchShows("vote_average.desc", 100))
        ).filter { it.items.isNotEmpty() }
    }

    suspend fun getHomeRails(): List<StudioSection> {
        if (apiKey.isBlank()) return emptyList()

        suspend fun toItems(fetch: suspend () -> TmdbDiscoverResponse, mediaType: String): List<StudioItem> =
            runCatching { fetch().results }.getOrDefault(emptyList()).map { StudioItem(it, mediaType) }

        return listOf(
            StudioSection("TRENDING NOW", toItems({ api.getTrending(apiKey) }, "movie")),
            StudioSection("POPULAR MOVIES", toItems({ api.getPopularMovies(apiKey) }, "movie")),
            StudioSection("POPULAR SERIES", toItems({ api.getPopularTv(apiKey) }, "series")),
            StudioSection("TOP RATED MOVIES", toItems({ api.getTopRatedMovies(apiKey) }, "movie")),
            StudioSection("TOP RATED SERIES", toItems({ api.getTopRatedTv(apiKey) }, "series"))
        ).filter { it.items.isNotEmpty() }
    }

    suspend fun getInitialGenreSections(genreId: Int): List<StudioSection> {
        return listOfNotNull(
            getGenreRailPage(genreId, "MOVIES · RECENT", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · RECENT", it) },
            getGenreRailPage(genreId, "MOVIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · POPULAR", it) },
            getGenreRailPage(genreId, "MOVIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · TOP RATED", it) },
            getGenreRailPage(genreId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            getGenreRailPage(genreId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            getGenreRailPage(genreId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getInitialKeywordSections(keywordId: Int): List<StudioSection> {
        return listOfNotNull(
            getKeywordRailPage(keywordId, "MOVIES · RECENT", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · RECENT", it) },
            getKeywordRailPage(keywordId, "MOVIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · POPULAR", it) },
            getKeywordRailPage(keywordId, "MOVIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · TOP RATED", it) },
            getKeywordRailPage(keywordId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            getKeywordRailPage(keywordId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            getKeywordRailPage(keywordId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getInitialNetworkSections(networkId: Int): List<StudioSection> {
        return listOfNotNull(
            getNetworkRailPage(networkId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            getNetworkRailPage(networkId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            getNetworkRailPage(networkId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getGenreRailPage(genreId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                api.discoverMovieByGenre(genreId, apiKey, "primary_release_date.desc", minVoteCount, releaseDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                api.discoverMovieByGenre(genreId, apiKey, "popularity.desc", minVoteCount, releaseDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                api.discoverMovieByGenre(genreId, apiKey, "vote_average.desc", 100, releaseDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                api.discoverTvByGenre(genreId, apiKey, "first_air_date.desc", minVoteCount, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverTvByGenre(genreId, apiKey, "popularity.desc", minVoteCount, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverTvByGenre(genreId, apiKey, "vote_average.desc", 100, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(results.distinctBy { it.item.id }, results.isNotEmpty())
    }

    suspend fun getKeywordRailPage(keywordId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                api.discoverMovieByKeyword(keywordId, apiKey, "primary_release_date.desc", minVoteCount, releaseDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                api.discoverMovieByKeyword(keywordId, apiKey, "popularity.desc", minVoteCount, releaseDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                api.discoverMovieByKeyword(keywordId, apiKey, "vote_average.desc", 100, releaseDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                api.discoverTvByKeyword(keywordId, apiKey, "first_air_date.desc", minVoteCount, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverTvByKeyword(keywordId, apiKey, "popularity.desc", minVoteCount, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverTvByKeyword(keywordId, apiKey, "vote_average.desc", 100, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(results.distinctBy { it.item.id }, results.isNotEmpty())
    }

    suspend fun getNetworkRailPage(networkId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "SERIES · RECENT" -> runCatching {
                api.discoverByNetwork(networkId, apiKey, "first_air_date.desc", minVoteCount, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverByNetwork(networkId, apiKey, "popularity.desc", minVoteCount, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverByNetwork(networkId, apiKey, "vote_average.desc", 100, firstAirDateLte = today, page = page).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(results.distinctBy { it.item.id }, results.isNotEmpty())
    }

    suspend fun getByGenre(genreId: Int): List<StudioSection> =
        getInitialGenreSections(genreId)

    suspend fun getByKeyword(keywordId: Int): List<StudioSection> =
        getInitialKeywordSections(keywordId)

    suspend fun getByNetwork(networkId: Int): List<StudioSection> =
        getInitialNetworkSections(networkId)

    companion object {
        const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/original"
    }
}
