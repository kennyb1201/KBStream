package com.kennyb1201.kbstream.data.tmdb

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.kennyb1201.kbstream.ui.settings.AppPreferences
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

    // One process-wide OkHttp client: TMDB traffic all goes to the same host,
    // so sharing it lets every screen reuse pooled TCP+TLS connections instead
    // of paying a fresh handshake per repository instance (Tag, Studio,
    // Collection, Detail, Home, Search each built their own before).
    private val api: TmdbApiService = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(sharedOkHttpClient())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApiService::class.java)

    private val apiKey = BuildConfig.TMDB_API_KEY
    private val appContext = context.applicationContext

    // Caps parallel TMDB availability lookups for the digital-release filter.
    private val availabilitySemaphore = Semaphore(permits = 6)

    private val minVoteCount = 50
    private val today: String
        get() = LocalDate.now().toString()

    private val database = WatchHistoryDatabase.getInstance(context)
    private val imdbResolutionDao = database.imdbResolutionDao()
    private val tmdbJsonCacheDao: TmdbJsonCacheDao = database.tmdbJsonCacheDao()

    // ConcurrentHashMap: continue-watching lookups run several rows in
    // parallel, so these caches are written from multiple coroutines.
    private val detailCache = ConcurrentHashMap<String, Pair<Long, TmdbDetail?>>()
    private val detailCacheTtlMs = 12L * 60L * 60L * 1000L
    private val detailCacheDiskTtlMs = 30L * 24L * 60L * 60L * 1000L

    private val seasonEpisodesCache =
        ConcurrentHashMap<String, Pair<Long, List<ResolvedEpisode>>>()
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

    private val imdbResolutionMemoryCache =
        ConcurrentHashMap<String, Pair<Long, String?>>()
    private val imdbResolutionTtlMs = 30L * 24L * 60L * 60L * 1000L

    private var movieGenresCache: List<TmdbGenre>? = null
    private var tvGenresCache: List<TmdbGenre>? = null

    private var trendingCache:
        Pair<Long, List<Pair<String, TmdbSearchTitleResult>>>? =
        null

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

    /**
     * Resolves raw Stremio-style ids to a full TMDB detail record.
     *
     * Catalogs don't just hand out Imdb ids: AIOStreams and search emit
     * "tmdb:12345", TVDB-sourced addons (AIOMetadata, BingeCat) emit
     * "tvdb:12345", some catalogs emit bare numeric ids, and history rows
     * can carry an episode suffix ("tt1234:1:2"). Only the show-level
     * id matters here, and each flavor is resolved accordingly:
     *  - tmdb:/bare-numeric -> fetch {tv|movie}/{id} directly
     *  - tvdb:             -> TMDB /find with external_source=tvdb_id
     *  - else (tt...)      -> TMDB /find with external_source=imdb_id
     */
    suspend fun fetchEnrichedMeta(rawId: String, type: String): TmdbDetail? {
        if (apiKey.isBlank()) return null

        val normalizedType = normalizeType(type)
        val trimmed = rawId.trim()
        val lower = trimmed.lowercase()

        // Episode-suffixed ids only arrive from history rows; keep the prefix.
        val tmdbId = when {
            lower.startsWith("tmdb:") ->
                trimmed.substringAfter(':').substringBefore(':').trim().toIntOrNull()
            trimmed.all(Char::isDigit) -> trimmed.toIntOrNull()
            else -> null
        }
        if (tmdbId != null) {
            return fetchDetailByTmdbId(tmdbId, normalizedType)
        }

        return if (lower.startsWith("tvdb:")) {
            val tvdbId = trimmed.substringAfter(':').substringBefore(':').trim()
            if (tvdbId.isBlank()) null else findAndFetch(tvdbId, "tvdb_id", normalizedType)
        } else {
            val imdbId = trimmed.substringBefore(':')
            if (imdbId.isBlank()) null else findAndFetch(imdbId, "imdb_id", normalizedType)
        }
    }

    private suspend fun findAndFetch(
        externalId: String,
        externalSource: String,
        normalizedType: String
    ): TmdbDetail? {
        val found =
            runCatching { api.find(externalId, apiKey, externalSource) }.getOrNull()
                ?: return null
        val tmdbId =
            if (normalizedType == "series") {
                found.tvResults.firstOrNull()?.id
            } else {
                found.movieResults.firstOrNull()?.id
            } ?: return null
        return fetchDetailByTmdbId(tmdbId, normalizedType)
    }

    private suspend fun fetchDetailByTmdbId(
        tmdbId: Int,
        normalizedType: String
    ): TmdbDetail? {
        if (normalizedType == "series") {
            return runCatching {
                api.getTv(tmdbId, apiKey)
            }.getOrNull()
        }
        if (normalizedType == "movie") {
            return runCatching {
                api.getMovie(tmdbId, apiKey)
            }.getOrNull()
        }
        // Unknown type (anime, trakt collection, ...): try series first,
        // fall back to movie. Both failures are silently swallowed.
        return runCatching {
            api.getTv(tmdbId, apiKey)
        }.getOrNull()
            ?: runCatching {
                api.getMovie(tmdbId, apiKey)
            }.getOrNull()
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

    /*
     * TMDB-native title search. This is the primary title source for the
     * search screen so it works without any add-on installed (e.g. Cinemeta);
     * add-on catalog search is only a supplement layered on top by the
     * ViewModel. Split into movies/TV (not multi-search) so every result
     * carries a reliable type.
     */
    suspend fun searchMovies(query: String): List<TmdbSearchTitleResult> {
        if (apiKey.isBlank()) return emptyList()
        val results =
            runCatching { api.searchMovie(query, apiKey).results }
                .getOrDefault(emptyList())

        if (!isDigitalFilterEnabled()) return results

        return filterByHomeAvailability(results) {
            it.id to "movie"
        }
    }

    suspend fun searchTv(query: String): List<TmdbSearchTitleResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching { api.searchTv(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    /*
     * Weekly trending titles for the search screen's idle state. Tagged with
     * the type ("movie"/"series") since trending/movie and trending/tv don't
     * reliably carry media_type on every result. Memory-cached briefly so the
     * idle state doesn't re-hit TMDB on every screen visit.
     */
    suspend fun getTrendingTitles(): List<Pair<String, TmdbSearchTitleResult>> {
        if (apiKey.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        trendingCache?.let { (fetchedAt, items) ->
            if (now - fetchedAt < TRENDING_CACHE_TTL_MS) {
                return items
            }
        }

        val movies = runCatching { api.getTrendingMovies(apiKey).results }
            .getOrDefault(emptyList())
        val tv = runCatching { api.getTrendingTv(apiKey).results }
            .getOrDefault(emptyList())

        val merged = movies.map { "movie" to it } + tv.map { "series" to it }

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(merged) {
                    it.second.id to it.first
                }
            } else {
                merged
            }

        // Cache the unfiltered list: trending is shared across screens, and
        // each consumer re-checks the toggle cheaply.
        trendingCache = now to merged
        return filtered
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

        val detail =
            runCatching { api.getCollection(collectionId, apiKey) }.getOrNull()
                ?: return null

        if (!isDigitalFilterEnabled()) {
            return detail
        }

        val filteredParts =
            filterByHomeAvailability(detail.parts) {
                it.id to "movie"
            }

        return detail.copy(parts = filteredParts)
    }

    // ------------------------------------------------------------------
    // Nuvio collections: raw TMDB source loaders (discover / list /
    // collection / company / network). A null return means the request
    // failed; an empty list means the query legitimately has no results.
    // ------------------------------------------------------------------

    /** Generic /discover with the full Nuvio filter-builder parameter set. */
    suspend fun discoverNuvio(
        mediaType: String,
        page: Int = 1,
        sortBy: String? = null,
        filters: com.kennyb1201.kbstream.data.nuvio.NuvioFilters? = null
    ): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        val isTv = mediaType.lowercase() == "tv"
        val yearRange = filters?.yearRange()
        return runCatching {
            if (isTv) {
                api.discoverTvGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = sortBy,
                    withGenres = filters?.withGenres,
                    withoutGenres = filters?.withoutGenres,
                    withKeywords = filters?.withKeywords,
                    withoutKeywords = filters?.withoutKeywords,
                    withCompanies = filters?.withCompanies,
                    withoutCompanies = filters?.withoutCompanies,
                    withNetworks = filters?.withNetworks,
                    withWatchProviders = filters?.withWatchProviders,
                    withoutWatchProviders = filters?.withoutWatchProviders,
                    watchRegion = filters?.watchRegion,
                    withOriginalLanguage = filters?.withOriginalLanguage,
                    withOriginCountry = filters?.withOriginCountry,
                    voteCountGte = filters?.voteCountGte,
                    voteAverageGte = filters?.voteAverageGte,
                    voteAverageLte = filters?.voteAverageLte,
                    firstAirDateGte = yearRange?.first,
                    firstAirDateLte = yearRange?.second
                )
            } else {
                api.discoverMovieGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = sortBy,
                    withGenres = filters?.withGenres,
                    withoutGenres = filters?.withoutGenres,
                    withKeywords = filters?.withKeywords,
                    withoutKeywords = filters?.withoutKeywords,
                    withCompanies = filters?.withCompanies,
                    withoutCompanies = filters?.withoutCompanies,
                    withNetworks = filters?.withNetworks,
                    withWatchProviders = filters?.withWatchProviders,
                    withoutWatchProviders = filters?.withoutWatchProviders,
                    watchRegion = filters?.watchRegion,
                    withOriginalLanguage = filters?.withOriginalLanguage,
                    withOriginCountry = filters?.withOriginCountry,
                    voteCountGte = filters?.voteCountGte,
                    voteAverageGte = filters?.voteAverageGte,
                    voteAverageLte = filters?.voteAverageLte,
                    primaryReleaseDateGte = yearRange?.first,
                    primaryReleaseDateLte = yearRange?.second
                )
            }.results
        }.getOrNull()
    }

    /** TMDB "LIST" source: items of a hosted TMDB list id. */
    suspend fun getNuvioListItems(listId: Int, page: Int = 1): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        return runCatching {
            api.getListItems(listId, apiKey, page).results
        }.getOrNull()
    }

    /** TMDB "COLLECTION" source: the collection's parts. */
    suspend fun getNuvioCollectionItems(collectionId: Int): List<TmdbCollectionPart>? {
        if (apiKey.isBlank()) return null
        return runCatching {
            getCollection(collectionId)?.parts
        }.getOrNull()
    }

    /**
     * One page of user reviews for a title via the standalone paginated
     * endpoint. The detail payload only bundles page 1; the Detail screen
     * uses this to pull in pages 2..N (bounded by totalPages from the
     * response) for review-heavy titles. Returns the full page object so
     * callers can read totalPages; fails soft (null) — reviews are
     * supplementary.
     */
    suspend fun getReviews(tmdbId: Int, type: String, page: Int): TmdbReviews? {
        if (apiKey.isBlank() || page < 1) return null
        return runCatching {
            if (normalizeType(type) == "series") {
                api.getTvReviews(tmdbId, apiKey, page)
            } else {
                api.getMovieReviews(tmdbId, apiKey, page)
            }
        }.getOrNull()
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

    // The six rail fetches used to run serially, making screen load time
    // the SUM of all round-trips. In parallel it is just the slowest one
    // (~6x faster wall clock). Sections keep the original render order and
    // a failed rail still drops out (listOfNotNull semantics kept).
    suspend fun getInitialGenreSections(genreId: Int): List<StudioSection> = coroutineScope {
        val pages = listOf(
            async { getGenreRailPage(genreId, "MOVIES · RECENT", 1) },
            async { getGenreRailPage(genreId, "MOVIES · POPULAR", 1) },
            async { getGenreRailPage(genreId, "MOVIES · TOP RATED", 1) },
            async { getGenreRailPage(genreId, "SERIES · RECENT", 1) },
            async { getGenreRailPage(genreId, "SERIES · POPULAR", 1) },
            async { getGenreRailPage(genreId, "SERIES · TOP RATED", 1) }
        ).awaitAll()
        listOfNotNull(
            pages[0].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · RECENT", it) },
            pages[1].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · POPULAR", it) },
            pages[2].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · TOP RATED", it) },
            pages[3].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            pages[4].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            pages[5].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    // Same parallelization as getInitialGenreSections.
    suspend fun getInitialKeywordSections(keywordId: Int): List<StudioSection> = coroutineScope {
        val pages = listOf(
            async { getKeywordRailPage(keywordId, "MOVIES · RECENT", 1) },
            async { getKeywordRailPage(keywordId, "MOVIES · POPULAR", 1) },
            async { getKeywordRailPage(keywordId, "MOVIES · TOP RATED", 1) },
            async { getKeywordRailPage(keywordId, "SERIES · RECENT", 1) },
            async { getKeywordRailPage(keywordId, "SERIES · POPULAR", 1) },
            async { getKeywordRailPage(keywordId, "SERIES · TOP RATED", 1) }
        ).awaitAll()
        listOfNotNull(
            pages[0].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · RECENT", it) },
            pages[1].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · POPULAR", it) },
            pages[2].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · TOP RATED", it) },
            pages[3].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            pages[4].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            pages[5].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    // Same parallelization as getInitialGenreSections.
    suspend fun getInitialNetworkSections(networkId: Int): List<StudioSection> = coroutineScope {
        val pages = listOf(
            async { getNetworkRailPage(networkId, "SERIES · RECENT", 1) },
            async { getNetworkRailPage(networkId, "SERIES · POPULAR", 1) },
            async { getNetworkRailPage(networkId, "SERIES · TOP RATED", 1) }
        ).awaitAll()
        listOfNotNull(
            pages[0].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            pages[1].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            pages[2].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    // Same parallelization as getInitialGenreSections.
    suspend fun getInitialCompanySections(companyId: Int): List<StudioSection> = coroutineScope {
        val pages = listOf(
            async { getCompanyRailPage(companyId, "MOVIES · RECENT", 1) },
            async { getCompanyRailPage(companyId, "MOVIES · POPULAR", 1) },
            async { getCompanyRailPage(companyId, "MOVIES · TOP RATED", 1) },
            async { getCompanyRailPage(companyId, "SERIES · RECENT", 1) },
            async { getCompanyRailPage(companyId, "SERIES · POPULAR", 1) },
            async { getCompanyRailPage(companyId, "SERIES · TOP RATED", 1) }
        ).awaitAll()
        listOfNotNull(
            pages[0].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · RECENT", it) },
            pages[1].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · POPULAR", it) },
            pages[2].items.takeIf { it.isNotEmpty() }?.let { StudioSection("MOVIES · TOP RATED", it) },
            pages[3].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · RECENT", it) },
            pages[4].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · POPULAR", it) },
            pages[5].items.takeIf { it.isNotEmpty() }?.let { StudioSection("SERIES · TOP RATED", it) }
        )
    }

    /**
     * True when the Home digital-release filter is enabled in Settings.
     */
    fun isDigitalFilterEnabled(): Boolean =
        AppPreferences.getHomeRailHideUpcoming(appContext)

    /**
     * App-wide availability filter (Home digital-release toggle). Given
     * items keyed by TMDB id + media type, drops movies the shared TMDB
     * cache says are not available at home yet (theatrical-only window,
     * unreleased lifecycle status, or only future dates). Series always
     * pass, unknown verdicts always pass, and id lookup failures keep
     * the item — the filter only hides what it can positively tell.
     * Verdicts reuse fetchEnrichedMetaCached (12h memory / 30d disk),
     * throttled by a semaphore.
     */
    suspend fun <T> filterByHomeAvailability(
        items: List<T>,
        key: (T) -> Pair<Int, String>
    ): List<T> {

        if (items.isEmpty()) return items

        return coroutineScope {

            items.map { item ->

                async {

                    val (tmdbId, mediaType) = key(item)

                    if (!mediaType.equals("movie", ignoreCase = true)) {
                        return@async item
                    }

                    val verdict = availabilitySemaphore.withPermit {

                        runCatching {

                            fetchEnrichedMetaCached(
                                imdbId = "tmdb:$tmdbId",
                                type = "movie"
                            )?.isAvailableAtHome()

                        }.getOrNull()
                    }

                    when (verdict) {
                        false -> null
                        else -> item
                    }
                }
            }.awaitAll().filterNotNull()
        }
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

        val distinct = results.distinctBy { it.item.id }

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(distinct) {
                    it.item.id to it.mediaType
                }
            } else {
                distinct
            }

        return TagRailPage(
            items = filtered,
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

        val distinct = results.distinctBy { it.item.id }

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(distinct) {
                    it.item.id to it.mediaType
                }
            } else {
                distinct
            }

        return TagRailPage(
            items = filtered,
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

        val distinct = results.distinctBy { it.item.id }

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(distinct) {
                    it.item.id to it.mediaType
                }
            } else {
                distinct
            }

        return TagRailPage(
            items = filtered,
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

        val distinct = results.distinctBy { it.item.id }

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(distinct) {
                    it.item.id to it.mediaType
                }
            } else {
                distinct
            }

        return TagRailPage(
            items = filtered,
            hasMore = results.isNotEmpty()
        )
    }

    /**
     * Keyword id lookup for the Search browse browser: TMDB's search/keyword
     * endpoint resolves a user-facing name ("Zombie", "Time Travel") to its
     * stable keyword id, which the Tag screen then discovers with. Cached in
     * the ViewModel so a name is resolved at most once per session.
     */
    suspend fun searchKeywords(query: String): List<TmdbSearchKeywordResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching { api.searchKeyword(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    /**
     * One decade rail, single media type — movies and series stay separate
     * like the genre/keyword screens. Decades reuse the Nuvio filter
     * plumbing: year="1980-1989" becomes primary_release_date /
     * first_air_date bounds. [mediaType] is "movie" or "tv".
     */
    suspend fun getDecadeSectionPage(
        decadeStart: Int,
        mediaType: String,
        sortBy: String,
        page: Int
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)
        val decadeEnd = decadeStart + 9
        val yearRange = "$decadeStart-$decadeEnd"
        val isTv = mediaType.equals("tv", ignoreCase = true)

        // "Most Voted" sorts by vote_average, which without a floor surfaces
        // obscure 8.5-rated shorts with 12 votes; "Popular" (popularity.desc)
        // only needs a light floor to skip no-title entries.
        val voteFloor = if (sortBy.startsWith("vote_average")) 500 else 100
        val filters = com.kennyb1201.kbstream.data.nuvio.NuvioFilters(
            year = yearRange,
            voteCountGte = voteFloor
        )

        val items = runCatching {
            discoverNuvio(
                mediaType = if (isTv) "tv" else "movie",
                page = page,
                sortBy = sortBy,
                filters = filters
            )
        }.getOrNull().orEmpty()
            .map { StudioItem(it, if (isTv) "series" else "movie") }
            .distinctBy { it.item.id }

        // A discover page caps at 20 items; a full page means more exist.
        return TagRailPage(items, items.size >= 20)
    }

    /**
     * One rail of a streaming service's dedicated screen. [mediaType] picks
     * which single discover call runs, so a service always gets both movie
     * and series rails (each with full TMDB page depth, like the genre
     * screens). Modes:
     *  - "originals": what the service produced — network discover for
     *    series, company discover for movies ([networkOrCompanyId] required;
     *    a pure network id has no movie-discover equivalent, so its movie
     *    rail is simply empty).
     *  - "recent" / "popular" / "voted": everything on the service now via
     *    watch-provider discover (US watch region, [providerId] required).
     */
    suspend fun getProviderRailPage(
        mode: String,
        mediaType: String,
        providerId: Int?,
        networkOrCompanyId: Int?,
        networkIsCompany: Boolean,
        page: Int
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)
        val isTv = mediaType.equals("tv", ignoreCase = true)
        val currentYear = java.time.LocalDate.now().year.toString()

        // Recent sorts by release date, which differs per media type; a
        // vote floor keeps "recent" from surfacing announced-but-unreleased
        // entries (they have almost no votes yet).
        val sortBy = when (mode) {
            "recent" -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            "voted" -> "vote_average.desc"
            else -> "popularity.desc"
        }
        val voteFloor = when (mode) {
            "recent" -> 20
            "popular" -> 100
            "voted" -> 500
            else -> 10
        }

        val base = com.kennyb1201.kbstream.data.nuvio.NuvioFilters(
            voteCountGte = voteFloor
        )
        val filters = if (mode == "originals") {
            // A network id only makes sense for TV; a company id applies to
            // both discover endpoints. Mixing them returns wrong/empty results.
            base.copy(
                withNetworks = if (isTv && !networkIsCompany) {
                    networkOrCompanyId?.toString()
                } else {
                    null
                },
                withCompanies = if (networkIsCompany) {
                    networkOrCompanyId?.toString()
                } else {
                    null
                }
            )
        } else {
            base.copy(
                withWatchProviders = providerId?.toString(),
                watchRegion = "US",
                year = if (mode == "recent") currentYear else null
            )
        }

        val items = runCatching {
            discoverNuvio(
                mediaType = if (isTv) "tv" else "movie",
                page = page,
                sortBy = sortBy,
                filters = filters
            )
        }.getOrNull().orEmpty()
            .map { StudioItem(it, if (isTv) "series" else "movie") }
            .distinctBy { it.item.id }

        // A discover page caps at 20 items; a full page means more exist.
        return TagRailPage(items, items.size >= 20)
    }

    // ------------------------------------------------------------------
    // Decade discover screens (Screen.Decade): same rail structure as the
    // genre/keyword screens, minus the RECENT rails — every decade is old
    // by definition, so "recent" adds nothing. Popular + Top Rated for
    // movies and series.
    // ------------------------------------------------------------------

    /** Rail titles a decade screen loads, in display order. */
    private val DECADE_RAIL_TITLES = listOf(
        "MOVIES · POPULAR",
        "MOVIES · TOP RATED",
        "SERIES · POPULAR",
        "SERIES · TOP RATED"
    )

    suspend fun getInitialDecadeSections(decadeStart: Int): List<StudioSection> = coroutineScope {
        val pages = DECADE_RAIL_TITLES.map { title ->
            async { getDecadeRailPage(decadeStart, title, 1) }
        }.awaitAll()
        DECADE_RAIL_TITLES.mapIndexed { index, title ->
            pages[index].items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
        }.filterNotNull()
    }

    /**
     * One page of one decade rail, keyed off the rail title the same way
     * the genre/keyword rail pages are ("MOVIES · POPULAR", ...).
     */
    suspend fun getDecadeRailPage(decadeStart: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("·").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }
        val sortBy = when (parts.getOrNull(1)?.uppercase()) {
            "POPULAR" -> "popularity.desc"
            "TOP RATED" -> "vote_average.desc"
            else -> return TagRailPage(emptyList(), false)
        }

        val result = getDecadeSectionPage(decadeStart, mediaType, sortBy, page)
        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(result.items) { it.item.id to it.mediaType }
            } else {
                result.items
            }
        return TagRailPage(filtered, result.hasMore)
    }

    // ------------------------------------------------------------------
    // Streaming-service screens: the consolidated Services & Networks
    // submenu opens one screen per brand whose rails cover BOTH media
    // types via watch-provider discover (US region) — so opening Netflix
    // shows its movies and shows, not series-only like the old network
    // page. Plain network entries (no provider id) keep the old rails.
    // ------------------------------------------------------------------

    /** Rail titles a service screen loads, in display order. */
    private val SERVICE_RAIL_TITLES = listOf(
        "MOVIES · RECENT",
        "MOVIES · POPULAR",
        "MOVIES · TOP RATED",
        "SERIES · RECENT",
        "SERIES · POPULAR",
        "SERIES · TOP RATED"
    )

    suspend fun getInitialServiceSections(providerId: Int): List<StudioSection> = coroutineScope {
        val pages = SERVICE_RAIL_TITLES.map { title ->
            async { getServiceRailPage(providerId, title, 1) }
        }.awaitAll()
        SERVICE_RAIL_TITLES.mapIndexed { index, title ->
            pages[index].items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
        }.filterNotNull()
    }

    suspend fun getServiceRailPage(providerId: Int, title: String, page: Int): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("·").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }
        val mode = when (parts.getOrNull(1)?.uppercase()) {
            "RECENT" -> "recent"
            "POPULAR" -> "popular"
            "TOP RATED" -> "voted"
            else -> return TagRailPage(emptyList(), false)
        }

        val result = getProviderRailPage(
            mode = mode,
            mediaType = mediaType,
            providerId = providerId,
            networkOrCompanyId = null,
            networkIsCompany = false,
            page = page
        )
        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(result.items) { it.item.id to it.mediaType }
            } else {
                result.items
            }
        return TagRailPage(filtered, result.hasMore)
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
        return when (type.lowercase().trim()) {
            "movie", "anime.movie" -> "movie"
            "series", "show", "tv", "anime", "anime.series" -> "series"
            else -> type.lowercase().trim()
        }
    }

    companion object {
        @Volatile
        private var sharedClient: OkHttpClient? = null

        /** Lazily built, process-wide client for TMDB API traffic. */
        fun sharedOkHttpClient(): OkHttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: OkHttpClient.Builder().build().also { sharedClient = it }
            }

        const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        const val LOGO_BASE = "https://image.tmdb.org/t/p/original"
        private const val MAX_IMDB_DISK_AGE_MS = 90L * 24L * 60L * 60L * 1000L
        private const val TRENDING_CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
