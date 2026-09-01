package com.kennyb1201.kbstream.data.tmdb

import android.content.Context
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.cache.ImdbResolutionEntity
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult

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
    val runtimeMinutes: Int?,
    val airDate: String?,
    val voteAverage: Double?
)

class TmdbRepository(context: Context) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val api: TmdbApiService = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(OkHttpClient.Builder().build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApiService::class.java)

    private val apiKey = BuildConfig.TMDB_API_KEY
    private val minVoteCount = 50
    private val today: String
        get() = LocalDate.now().toString()

    private val database = WatchHistoryDatabase.getInstance(context)
    private val imdbResolutionDao = database.imdbResolutionDao()
    private val tmdbJsonCacheDao: TmdbJsonCacheDao = database.tmdbJsonCacheDao()

    private val detailCache = mutableMapOf<String, Pair<Long, TmdbDetail?>>()
    private val detailCacheTtlMs = 12L * 60L * 60L * 1000L
    private val detailCacheDiskTtlMs = 30L * 24L * 60L * 60L * 1000L

    private val seasonEpisodesCache = mutableMapOf<String, Pair<Long, List<ResolvedEpisode>>>()
    private val seasonEpisodesCacheTtlMs = 12L * 60L * 60L * 1000L
    private val seasonEpisodesDiskTtlMs = 7L * 24L * 60L * 60L * 1000L

    private val detailJsonAdapter: JsonAdapter<TmdbDetail> =
        moshi.adapter(TmdbDetail::class.java)

    private val seasonEpisodesJsonAdapter: JsonAdapter<List<ResolvedEpisode>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                ResolvedEpisode::class.java
            )
        )

    private val genresJsonAdapter: JsonAdapter<List<TmdbGenre>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                TmdbGenre::class.java
            )
        )

    private val imdbResolutionMemoryCache = mutableMapOf<String, Pair<Long, String?>>()
    private val imdbResolutionTtlMs = 30L * 24L * 60L * 60L * 1000L

    private var movieGenresCache: List<TmdbGenre>? = null
    private var tvGenresCache: List<TmdbGenre>? = null

    private val cachePruned = AtomicBoolean(false)
    private val jsonCachePruned = AtomicBoolean(false)

    init {
        pruneImdbCacheOnce()
        pruneJsonCacheOnce()
    }

    private fun pruneImdbCacheOnce() {
        if (cachePruned.compareAndSet(false, true)) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    imdbResolutionDao.deleteOlderThan(cutoff)
                }
            }
        }
    }

    private fun pruneJsonCacheOnce() {
        if (jsonCachePruned.compareAndSet(false, true)) {
            val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    tmdbJsonCacheDao.deleteOlderThan(cutoff)
                }
            }
        }
    }

    suspend fun fetchEnrichedMeta(imdbId: String, type: String): TmdbDetail? {
        if (apiKey.isBlank()) return null

        val found = api.find(imdbId, apiKey)
        return if (normalizeType(type) == "series") {
            val tmdbId = found.tvResults.firstOrNull()?.id ?: return null
            api.getTv(tmdbId, apiKey)
        } else {
            val tmdbId = found.movieResults.firstOrNull()?.id ?: return null
            api.getMovie(tmdbId, apiKey)
        }
    }

    suspend fun fetchEnrichedMetaCached(imdbId: String, type: String): TmdbDetail? {
        val key = "${normalizeType(type)}:$imdbId"
        val now = System.currentTimeMillis()

        // In-memory TTL cache (fast path for the current session).
        val cached = detailCache[key]
        if (cached != null && now - cached.first < detailCacheTtlMs) {
            return cached.second
        }

        // Disk cache so resolved metadata survives restarts.
        val diskKey = "detail:$key"
        val diskCached = runCatching {
            tmdbJsonCacheDao.getByKey(diskKey)
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            val parsed = runCatching {
                detailJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            if (parsed != null) {
                detailCache[key] = now to parsed
                return parsed
            }
        }

        val result = runCatching { fetchEnrichedMeta(imdbId, type) }.getOrNull()
        detailCache[key] = now to result
        if (result != null) {
            runCatching {
                tmdbJsonCacheDao.upsert(
                    TmdbJsonCacheEntity(
                        key = diskKey,
                        json = detailJsonAdapter.toJson(result),
                        updatedAt = now
                    )
                )
            }
        }
        return result
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? {
        if (apiKey.isBlank()) return null

        val normalizedType = normalizeType(type)
        val key = imdbResolutionKey(tmdbId, normalizedType)
        val now = System.currentTimeMillis()

        imdbResolutionMemoryCache[key]?.let { (cachedAt, imdbId) ->
            if (now - cachedAt < imdbResolutionTtlMs) {
                return imdbId
            }
        }

        val diskCached = runCatching { imdbResolutionDao.getByKey(key) }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < imdbResolutionTtlMs) {
            imdbResolutionMemoryCache[key] = diskCached.updatedAt to diskCached.imdbId
            return diskCached.imdbId
        }

        val imdbId = runCatching {
            val ext = if (normalizedType == "series") {
                api.getTvExternalIds(tmdbId, apiKey)
            } else {
                api.getMovieExternalIds(tmdbId, apiKey)
            }
            ext.imdbId
        }.getOrNull()

        if (!imdbId.isNullOrBlank()) {
            imdbResolutionMemoryCache[key] = now to imdbId

            runCatching {
                imdbResolutionDao.upsert(
                    ImdbResolutionEntity(
                        key = key,
                        tmdbId = tmdbId,
                        mediaType = normalizedType,
                        imdbId = imdbId,
                        updatedAt = now
                    )
                )
            }
        }

        return imdbId
    }

    suspend fun getPerson(personId: Int): TmdbPersonDetail? {
        if (apiKey.isBlank()) return null
        return api.getPerson(personId, apiKey)
    }

    suspend fun searchPerson(query: String): List<TmdbSearchPersonResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching { api.searchPerson(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    suspend fun searchCompany(query: String): List<TmdbSearchStudioResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching { api.searchCompany(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    suspend fun getMovieGenres(): List<TmdbGenre> {
        if (apiKey.isBlank()) return emptyList()
        movieGenresCache?.let { return it }
        readGenresFromDisk("movie")?.let {
            movieGenresCache = it
            return it
        }
        return runCatching { api.getMovieGenreList(apiKey).genres }
            .getOrDefault(emptyList())
            .also { movieGenresCache = it }
            .also { writeGenresToDisk("movie", it) }
    }

    suspend fun getTvGenres(): List<TmdbGenre> {
        if (apiKey.isBlank()) return emptyList()
        tvGenresCache?.let { return it }
        readGenresFromDisk("tv")?.let {
            tvGenresCache = it
            return it
        }
        return runCatching { api.getTvGenreList(apiKey).genres }
            .getOrDefault(emptyList())
            .also { tvGenresCache = it }
            .also { writeGenresToDisk("tv", it) }
    }

    private suspend fun readGenresFromDisk(key: String): List<TmdbGenre>? {
        val now = System.currentTimeMillis()
        val diskCached = runCatching {
            tmdbJsonCacheDao.getByKey("genres:$key")
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            return runCatching {
                genresJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
        }
        return null
    }

    private suspend fun writeGenresToDisk(key: String, genres: List<TmdbGenre>) {
        runCatching {
            tmdbJsonCacheDao.upsert(
                TmdbJsonCacheEntity(
                    key = "genres:$key",
                    json = genresJsonAdapter.toJson(genres),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun getCollection(collectionId: Int): TmdbCollectionDetail? {
        if (apiKey.isBlank()) return null
        return runCatching { api.getCollection(collectionId, apiKey) }.getOrNull()
    }

    suspend fun getSeasonEpisodes(
        tvId: Int,
        season: Int,
        imdbId: String
    ): List<ResolvedEpisode> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("TMDB API key is missing")
        }

        // Continue-watching resolution scans many seasons per show (and does so
        // once per history/Simkl row), so cache each (show, season) lookup in
        // memory with a TTL instead of hitting TMDB every time.
        val key = "$tvId:$season:$imdbId"
        val now = System.currentTimeMillis()
        val cached = seasonEpisodesCache[key]

        if (cached != null && now - cached.first < seasonEpisodesCacheTtlMs) {
            return cached.second
        }

        // Disk cache so the season scans also survive restarts.
        val diskKey = "season:$key"
        val diskCached = runCatching {
            tmdbJsonCacheDao.getByKey(diskKey)
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < seasonEpisodesDiskTtlMs) {
            val parsed = runCatching {
                seasonEpisodesJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            if (parsed != null) {
                seasonEpisodesCache[key] = now to parsed
                return parsed
            }
        }

        val seasonDetail = api.getSeasonDetail(tvId, season, apiKey)

        val episodes = seasonDetail.episodes.map { ep ->
            ResolvedEpisode(
                streamId = "$imdbId:$season:${ep.episodeNumber}",
                episodeNumber = ep.episodeNumber,
                name = ep.name,
                overview = ep.overview,
                thumbnail = ep.stillPath?.let {
                    "https://image.tmdb.org/t/p/w780$it"
                },
                runtimeMinutes = ep.runtime,
                airDate = ep.airDate,
                voteAverage = ep.voteAverage
            )
        }

        seasonEpisodesCache[key] = now to episodes
        runCatching {
            tmdbJsonCacheDao.upsert(
                TmdbJsonCacheEntity(
                    key = diskKey,
                    json = seasonEpisodesJsonAdapter.toJson(episodes),
                    updatedAt = now
                )
            )
        }
        return episodes
    }

    suspend fun getEpisodeRating(
    tmdbId: Int,
    season: Int,
    episode: Int
): Double? {
    if (apiKey.isBlank()) return null

    return runCatching {
        api.getSeasonDetail(
            id = tmdbId,
            seasonNumber = season,
            apiKey = apiKey
        )
            .episodes
            .firstOrNull { it.episodeNumber == episode }
            ?.voteAverage
            ?.takeIf { it > 0.0 }
    }.getOrNull()
    }

    suspend fun getDetailByTmdbId(tmdbId: Int, type: String): TmdbDetail? {
        if (apiKey.isBlank()) return null

        val key = "${normalizeType(type)}:tmdb:$tmdbId"
        val now = System.currentTimeMillis()

        // In-memory TTL cache (fast path for the current session).
        val cached = detailCache[key]
        if (cached != null && now - cached.first < detailCacheTtlMs) {
            return cached.second
        }

        // Disk cache so resolved metadata survives restarts.
        val diskKey = "detail:$key"
        val diskCached = runCatching {
            tmdbJsonCacheDao.getByKey(diskKey)
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            val parsed = runCatching {
                detailJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            if (parsed != null) {
                detailCache[key] = now to parsed
                return parsed
            }
        }

        val result = runCatching {
            if (normalizeType(type) == "series") {
                api.getTv(tmdbId, apiKey)
            } else {
                api.getMovie(tmdbId, apiKey)
            }
        }.getOrNull()

        detailCache[key] = now to result
        if (result != null) {
            runCatching {
                tmdbJsonCacheDao.upsert(
                    TmdbJsonCacheEntity(
                        key = diskKey,
                        json = detailJsonAdapter.toJson(result),
                        updatedAt = now
                    )
                )
            }
        }
        return result
    }

    suspend fun getByCompany(companyId: Int): List<StudioSection> =
        getInitialCompanySections(companyId)

    suspend fun getHomeRails(): List<StudioSection> {
        if (apiKey.isBlank()) return emptyList()

        suspend fun toItems(
            fetch: suspend () -> TmdbDiscoverResponse,
            mediaType: String
        ): List<StudioItem> =
            runCatching { fetch().results }
                .getOrDefault(emptyList())
                .map { StudioItem(it, mediaType) }

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
            getGenreRailPage(genreId, "MOVIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · RECENT", it) },
            getGenreRailPage(genreId, "MOVIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · POPULAR", it) },
            getGenreRailPage(genreId, "MOVIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · TOP RATED", it) },
            getGenreRailPage(genreId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · RECENT", it) },
            getGenreRailPage(genreId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · POPULAR", it) },
            getGenreRailPage(genreId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getInitialKeywordSections(keywordId: Int): List<StudioSection> {
        return listOfNotNull(
            getKeywordRailPage(keywordId, "MOVIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · RECENT", it) },
            getKeywordRailPage(keywordId, "MOVIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · POPULAR", it) },
            getKeywordRailPage(keywordId, "MOVIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · TOP RATED", it) },
            getKeywordRailPage(keywordId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · RECENT", it) },
            getKeywordRailPage(keywordId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · POPULAR", it) },
            getKeywordRailPage(keywordId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getInitialNetworkSections(networkId: Int): List<StudioSection> {
        return listOfNotNull(
            getNetworkRailPage(networkId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · RECENT", it) },
            getNetworkRailPage(networkId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · POPULAR", it) },
            getNetworkRailPage(networkId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getInitialCompanySections(companyId: Int): List<StudioSection> {
        return listOfNotNull(
            getCompanyRailPage(companyId, "MOVIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · RECENT", it) },
            getCompanyRailPage(companyId, "MOVIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · POPULAR", it) },
            getCompanyRailPage(companyId, "MOVIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("MOVIES · TOP RATED", it) },
            getCompanyRailPage(companyId, "SERIES · RECENT", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · RECENT", it) },
            getCompanyRailPage(companyId, "SERIES · POPULAR", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · POPULAR", it) },
            getCompanyRailPage(companyId, "SERIES · TOP RATED", 1).items.takeIf { it.isNotEmpty() }
                ?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    suspend fun getGenreRailPage(genreId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                api.discoverMovieByGenre(
                    genreId,
                    apiKey,
                    "primary_release_date.desc",
                    minVoteCount,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                api.discoverMovieByGenre(
                    genreId,
                    apiKey,
                    "popularity.desc",
                    minVoteCount,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                api.discoverMovieByGenre(
                    genreId,
                    apiKey,
                    "vote_average.desc",
                    100,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                api.discoverTvByGenre(
                    genreId,
                    apiKey,
                    "first_air_date.desc",
                    minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverTvByGenre(
                    genreId,
                    apiKey,
                    "popularity.desc",
                    minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverTvByGenre(
                    genreId,
                    apiKey,
                    "vote_average.desc",
                    100,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(
            items = results.distinctBy { it.item.id },
            hasMore = results.isNotEmpty()
        )
    }

    suspend fun getKeywordRailPage(keywordId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                api.discoverMovieByKeyword(
                    keywordId,
                    apiKey,
                    "primary_release_date.desc",
                    minVoteCount,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                api.discoverMovieByKeyword(
                    keywordId,
                    apiKey,
                    "popularity.desc",
                    minVoteCount,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                api.discoverMovieByKeyword(
                    keywordId,
                    apiKey,
                    "vote_average.desc",
                    100,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                api.discoverTvByKeyword(
                    keywordId,
                    apiKey,
                    "first_air_date.desc",
                    minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverTvByKeyword(
                    keywordId,
                    apiKey,
                    "popularity.desc",
                    minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverTvByKeyword(
                    keywordId,
                    apiKey,
                    "vote_average.desc",
                    100,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(
            items = results.distinctBy { it.item.id },
            hasMore = results.isNotEmpty()
        )
    }

    suspend fun getNetworkRailPage(networkId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "SERIES · RECENT" -> runCatching {
                api.discoverByNetwork(
                    networkId,
                    apiKey,
                    "first_air_date.desc",
                    minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverByNetwork(
                    networkId,
                    apiKey,
                    "popularity.desc",
                    minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverByNetwork(
                    networkId,
                    apiKey,
                    "vote_average.desc",
                    100,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(
            items = results.distinctBy { it.item.id },
            hasMore = results.isNotEmpty()
        )
    }

    suspend fun getCompanyRailPage(companyId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val results = when (title) {
            "MOVIES · RECENT" -> runCatching {
                api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = apiKey,
                    sortBy = "primary_release_date.desc",
                    voteCountGte = minVoteCount,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · POPULAR" -> runCatching {
                api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = apiKey,
                    sortBy = "popularity.desc",
                    voteCountGte = minVoteCount,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "MOVIES · TOP RATED" -> runCatching {
                api.discoverMovieByCompany(
                    companyId = companyId,
                    apiKey = apiKey,
                    sortBy = "vote_average.desc",
                    voteCountGte = 100,
                    releaseDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "movie") }

            "SERIES · RECENT" -> runCatching {
                api.discoverTvByCompany(
                    companyId = companyId,
                    apiKey = apiKey,
                    sortBy = "first_air_date.desc",
                    voteCountGte = minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · POPULAR" -> runCatching {
                api.discoverTvByCompany(
                    companyId = companyId,
                    apiKey = apiKey,
                    sortBy = "popularity.desc",
                    voteCountGte = minVoteCount,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            "SERIES · TOP RATED" -> runCatching {
                api.discoverTvByCompany(
                    companyId = companyId,
                    apiKey = apiKey,
                    sortBy = "vote_average.desc",
                    voteCountGte = 100,
                    firstAirDateLte = today,
                    page = page
                ).results
            }.getOrDefault(emptyList()).map { StudioItem(it, "series") }

            else -> emptyList()
        }

        return TagRailPage(
            items = results.distinctBy { it.item.id },
            hasMore = results.isNotEmpty()
        )
    }

    suspend fun searchCollection(query: String): List<TmdbSearchCollectionResult> {
    if (apiKey.isBlank()) return emptyList()
    return runCatching { api.searchCollection(query, apiKey).results }
        .getOrDefault(emptyList())
    }

    suspend fun getByGenre(genreId: Int): List<StudioSection> =
        getInitialGenreSections(genreId)

    suspend fun getByKeyword(keywordId: Int): List<StudioSection> =
        getInitialKeywordSections(keywordId)

    suspend fun getByNetwork(networkId: Int): List<StudioSection> =
        getInitialNetworkSections(networkId)

    /**
     * Best transparent clear-logo for a studio or network, or null when TMDB
     * has none. Networks live in a different TMDB ID space than companies, so
     * the correct endpoint is used per type (company first as a fallback).
     * Prefers an English logo, then the highest-voted, widest one.
     */
    suspend fun getEntityLogoUrl(entityId: Int, isNetwork: Boolean): String? {
        if (apiKey.isBlank()) return null

        suspend fun fetchLogos(company: Boolean): List<TmdbCompanyLogo> = runCatching {
            if (company) {
                api.getCompanyImages(entityId, apiKey).logos
            } else if (isNetwork) {
                api.getNetworkImages(entityId, apiKey).logos
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        suspend fun pickBestLogo(logos: List<TmdbCompanyLogo>): String? =
            logos.filter { !it.filePath.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<TmdbCompanyLogo> { it.iso6391 == "en" }
                        .thenByDescending { it.iso6391 == null }
                        .thenByDescending { it.voteAverage ?: 0.0 }
                        .thenByDescending { it.width ?: 0 }
                )
                .firstOrNull()
                ?.filePath
                ?.let { TmdbRepository.LOGO_BASE + it }

        if (isNetwork) {
            // Primary: the network's own logos. Fallback: same id as a company
            // (some entries exist in both spaces).
            return pickBestLogo(fetchLogos(company = false))
                ?: pickBestLogo(fetchLogos(company = true))
        }
        return pickBestLogo(fetchLogos(company = true))
    }

    /** Company/network metadata (description, headquarters, origin country). */
    suspend fun getEntityDetail(entityId: Int, isNetwork: Boolean): TmdbCompanyDetail? {
        if (apiKey.isBlank()) return null

        if (isNetwork) {
            // Networks have their own endpoint; fall back to the company shape.
            val networkDetail = runCatching {
                api.getNetworkDetail(entityId, apiKey)
            }.getOrNull()
            if (networkDetail != null && !networkDetail.name.isNullOrBlank()) {
                return networkDetail
            }
            return runCatching { api.getCompanyDetail(entityId, apiKey) }.getOrNull()
        }
        return runCatching { api.getCompanyDetail(entityId, apiKey) }.getOrNull()
    }

    private fun imdbResolutionKey(tmdbId: Int, type: String): String {
        return "${normalizeType(type)}::$tmdbId"
    }

    private fun normalizeType(type: String): String {
        return when (type.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> type.lowercase()
        }
    }

    companion object {
        const val PROFILE_BASE = "https://image.tmdb.org/t/p/original"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/original"
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        const val LOGO_BASE = "https://image.tmdb.org/t/p/original"
        private const val MAX_IMDB_DISK_AGE_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
