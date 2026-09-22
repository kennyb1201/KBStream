package com.kennyb1201.kbstream.data.tmdb

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.cache.ImdbResolutionEntity
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.ProfileManager
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

/**
 * The non-genre dimension a discover screen is already filtered by. Genre
 * chip rails compose this WITH a genre (KBFilters fields AND together), so
 * every screen can offer "browse this dimension by genre" without new
 * endpoints. kind: provider | company | network | keyword | decade.
 */
data class CrossBase(
    val kind: String,
    val id: Int,
    /**
     * For a `network` base: the brand's TMDB company id. Network discover is
     * TV-only, so a genre-filtered network screen's MOVIES rails run through
     * company discover instead (null = no movie rails on that screen).
     */
    val companyId: Int? = null
)

/**
 * One page of a browse rail.
 *
 * [nextPage] is the first TMDB page this rail has NOT merged yet. Page
 * deepening (see [TmdbRepository.finishDeepRailPage]) consumes several TMDB
 * pages before a rail first renders, so a later "load more" has to resume
 * after the last one instead of re-fetching pages that are already on screen
 * (which would return nothing new and look like a dead press).
 */
data class TagRailPage(
    val items: List<StudioItem>,
    val hasMore: Boolean,
    val nextPage: Int = 2
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

class TmdbRepository private constructor(context: Context) {

    // Process-wide singleton (see [Companion.getInstance]): Retrofit +
    // Moshi(KotlinJsonAdapterFactory) are heavyweight reflection setups and
    // per-instance detail/episode/imdb caches fragmented across ~18 call
    // sites — the player alone used to build several instances per
    // session. One shared instance means one Retrofit stack and warm caches
    // for the whole process.
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    internal val api: TmdbApiService = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(sharedOkHttpClient())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TmdbApiService::class.java)

    internal val apiKey = BuildConfig.TMDB_API_KEY
    private val appContext = context.applicationContext

    // Caps parallel TMDB availability lookups for the digital-release filter.
    private val availabilitySemaphore = Semaphore(permits = 6)

    // Vote floors per rail kind. RECENT is light (newest-first, just skip
    // no-title junk); POPULAR and TOP RATED use 10 so smaller catalogs
    // (Tubi, Pluto, Crunchyroll, keywords, mid-size studios) keep real rows
    // instead of starving to empty.
    internal val minVoteCount = 10

    /** TOP-RATED / "Most Voted" rail floor (sort is vote_count.desc). */
    internal val minTopRatedVoteCount = 10

    /**
     * RECENT-rail floor: none. These rails are strictly newest-first, and a
     * vote floor can only punch holes in a chronological list — a brand-new
     * title has almost no votes yet, which is exactly the title a "recent"
     * rail exists to show. (History's whole 2026 slate sat behind the old
     * floor of 5.) Junk without artwork is dropped by [finishRailPage]
     * instead, which is what the floor was really guarding against — see
     * [dropPosterless].
     */
    internal val minRecentVoteCount = 0

    /**
     * Rail depth policy for the Search browse chips' screens (genre,
     * keyword, network, studio, service, decade, cross-genre). TMDB returns
     * 20 rows per discover page and the rail loaders used to render exactly
     * that first page — so after the artwork and availability filters a
     * chip's rails showed barely a dozen rows and read as a half-empty
     * catalog. Page 1 now keeps pulling until a rail reaches
     * [RAIL_DEPTH_TARGET_ITEMS] rows, capped at [RAIL_DEPTH_MAX_PAGE] so
     * opening one screen costs at most a few requests per rail.
     */
    internal val RAIL_DEPTH_TARGET_ITEMS = 60
    internal val RAIL_DEPTH_MAX_PAGE = 3

    internal val today: String
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
        val loaded = api.getPerson(personId, apiKey) ?: return null
        // Kids Mode: trim the combined filmography to the active ceiling at
        // the source. One chokepoint covers every consumer — the actor
        // screen, detail person chips, and KB PERSON/DIRECTOR/WRITER
        // rails (which read combinedCredits straight off this payload).
        val ceiling = kidsMaxAge()
        val credits = loaded.combinedCredits
        return if (ceiling != null && credits != null) {
            loaded.copy(
                combinedCredits = TmdbCombinedCredits(
                    cast = kidsFilterPersonCredits(credits.cast),
                    crew = kidsFilterPersonCredits(credits.crew)
                )
            )
        } else {
            loaded
        }
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

        // Kids Mode first: a kids profile never sees franchise pages whose
        // parts are rated above its ceiling (and the whole page drops when
        // no part survives).
        val kidsParts = if (kidsMaxAge() == null) {
            detail.parts
        } else {
            kidsFilter(detail.parts) { it.id to "movie" }
        }
        if (kidsParts.isEmpty() && detail.parts.isNotEmpty()) {
            return detail.copy(parts = emptyList())
        }

        if (!isDigitalFilterEnabled()) {
            return detail.copy(parts = kidsParts)
        }

        val filteredParts =
            filterByHomeAvailability(kidsParts) {
                it.id to "movie"
            }

        return detail.copy(parts = filteredParts)
    }

    // ------------------------------------------------------------------
    // KB collections: raw TMDB source loaders (discover / list /
    // collection / company / network). A null return means the request
    // failed; an empty list means the query legitimately has no results.
    // ------------------------------------------------------------------

    /** Generic /discover with the full KB filter-builder parameter set. */
    suspend fun discoverKB(
        mediaType: String,
        page: Int = 1,
        sortBy: String? = null,
        filters: com.kennyb1201.kbstream.data.kb.KBFilters? = null
    ): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        val isTv = mediaType.lowercase() == "tv"
        val yearRange = filters?.yearRange()
        // Explicit date bounds (e.g. the rolling RECENT window) — only used
        // when no year range is set, so decade/decade-style year filters win.
        val dateGte = yearRange?.first ?: filters?.releaseDateGte
        val dateLte = yearRange?.second ?: filters?.releaseDateLte
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
                    firstAirDateGte = dateGte,
                    firstAirDateLte = dateLte
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
                    primaryReleaseDateGte = dateGte,
                    primaryReleaseDateLte = dateLte
                )
            }.results
        }.getOrNull()
            ?.let { items ->
                // Kids Mode: this is the raw loader behind every KB
                // folder rail, so the ceiling check runs here once and
                // covers all folder screens (movies + series mixed).
                if (kidsMaxAge() == null) items
                else kidsFilterItems(
                    items.map { StudioItem(it, if (isTv) "series" else "movie") }
                ).map { it.item }
            }
    }

    /**
     * TMDB "LIST" source: items of a hosted TMDB list id. Kids Mode note:
     * /list items are heterogeneous (movies + series mixed), so the ceiling
     * check keys off each item's own media type — inferred the same way the
     * rail renderer does (first_air_date present => series) since /list
     * results carry no media_type field.
     */
    suspend fun getKBListItems(listId: Int, page: Int = 1): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        return runCatching {
            api.getListItems(listId, apiKey, page).results
        }.getOrNull()?.let { items ->
            if (kidsMaxAge() == null) items
            else kidsFilterItems(
                items.map {
                    StudioItem(it, if (it.firstAirDate != null) "series" else "movie")
                }
            ).map { it.item }
        }
    }

    /** TMDB "COLLECTION" source: the collection's parts. */
    /**
     * Titles sharing a TMDB keyword ("heist", "space western", ...) via the
     * generic discover endpoint. Powers the "same vibe" tier of the player's
     * because-you-watched blend; sorted by TMDB's default relevance.
     */
    suspend fun getKeywordItems(
        keywordId: Int,
        type: String,
        page: Int = 1
    ): List<com.kennyb1201.kbstream.data.tmdb.TmdbKeywordDiscoverItem>? {
        if (apiKey.isBlank()) return null
        val isTv = normalizeType(type) == "series"
        return runCatching {
            if (isTv) {
                api.discoverTvGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = "popularity.desc",
                    withKeywords = keywordId.toString()
                )
            } else {
                api.discoverMovieGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = "popularity.desc",
                    withKeywords = keywordId.toString()
                )
            }.results.map { item ->
                com.kennyb1201.kbstream.data.tmdb.TmdbKeywordDiscoverItem(
                    id = item.id,
                    title = item.title,
                    name = item.name,
                    posterPath = item.posterPath,
                    backdropPath = item.backdropPath,
                    overview = null
                )
            }
        }.getOrNull()
    }

    suspend fun getKBCollectionItems(collectionId: Int): List<TmdbCollectionPart>? {
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
    /**
     * A network's rails: its series, plus its movies when the browse entry
     * also carries the brand's TMDB company id ([companyId]) — network
     * discover is TV-only, so the movie rails go through company discover
     * (see [TmdbRailPages.networkPage]). Series stay first: they are what a
     * network page is for, and the movie rails are the bonus.
     */
    suspend fun getInitialNetworkSections(
        networkId: Int,
        companyId: Int? = null
    ): List<StudioSection> = coroutineScope {
        val titles = TmdbRailPages.networkRailTitles(companyId)
        val pages = titles.map { title ->
            async { getNetworkRailPage(networkId, title, 1, companyId) }
        }.awaitAll()
        titles.mapIndexed { index, title ->
            pages[index].items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
        }.filterNotNull()
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

    // ------------------------------------------------------------------
    // Genre chip rails: compose a genre filter with whatever dimension a
    // discover screen already uses (provider, company, network, keyword,
    // or decade). Uses discoverKB so the genre ANDs with the base filter,
    // instead of adding new API endpoints per screen.
    // ------------------------------------------------------------------

    /** Cached union of TMDB movie + TV genre lists (merged by id). */
    @Volatile
    private var browseGenreCache: List<TmdbGenre>? = null

    suspend fun getBrowseGenres(): List<TmdbGenre> {
        browseGenreCache?.let { return it }
        val movie = runCatching { api.getMovieGenreList(apiKey).genres }.getOrDefault(emptyList())
        val tv = runCatching { api.getTvGenreList(apiKey).genres }.getOrDefault(emptyList())
        val merged = (movie + tv.filter { tvGenre -> movie.none { it.id == tvGenre.id } })
            .sortedBy { it.name }
        if (merged.isNotEmpty()) browseGenreCache = merged
        return merged
    }

    /**
     * One page of one rail where a genre is ANDed onto the screen's base
     * dimension. Rail title parses exactly like the per-dimension rail
     * pages ("MOVIES · POPULAR", ...). A network base is TV-only; movie
     * rails against it return an empty page.
     */
    suspend fun getCrossGenreRailPage(
        base: CrossBase,
        genreId: Int,
        title: String,
        page: Int
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("\u00B7").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }
        val isTv = mediaType == "tv"
        val mode = parts.getOrNull(1)?.uppercase()
        val sortBy = when (mode) {
            "RECENT" -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            "POPULAR" -> "popularity.desc"
            "TOP RATED" -> "vote_count.desc"
            else -> return TagRailPage(emptyList(), false)
        }
        val voteFloor = when (mode) {
            "RECENT" -> minRecentVoteCount
            "POPULAR" -> minVoteCount
            else -> minTopRatedVoteCount
        }
        // A network base is TV-only in TMDB's discover (a network id is not
        // a company id), so its MOVIES rails run through the brand's company
        // id when the screen has one. Without it they stay empty, exactly as
        // before — never another brand's catalog.
        val networkMovieCompanyId = when {
            base.kind != "network" || isTv -> null
            base.companyId != null -> base.companyId
            else -> return TagRailPage(emptyList(), false)
        }

        val filters = com.kennyb1201.kbstream.data.kb.KBFilters(
            voteCountGte = voteFloor,
            // English-only catalogs while that switch is on (see
            // [browseLanguage]) — this is the cross-genre and decade rails
            // behind the Search browse chips.
            withOriginalLanguage = browseLanguage(),
            // A genre-base screen (Tag on a genre) ANDs the chip genre via
            // TMDB's comma OR semantics within the same filter field.
            withGenres = if (base.kind == "genre") {
                if (base.id == genreId) base.id.toString() else "${base.id},$genreId"
            } else {
                genreId.toString()
            },
            releaseDateLte = today,
            withWatchProviders = if (base.kind == "provider") base.id.toString() else null,
            watchRegion = if (base.kind == "provider") "US" else null,
            withCompanies = when {
                base.kind == "company" -> base.id.toString()
                networkMovieCompanyId != null -> networkMovieCompanyId.toString()
                else -> null
            },
            withNetworks = if (base.kind == "network" && isTv) base.id.toString() else null,
            withKeywords = if (base.kind == "keyword") base.id.toString() else null,
            year = if (base.kind == "decade") base.id else null
        )

        val (items, nextPage) = deepenDiscover(
            mediaType = if (isTv) "tv" else "movie",
            sortBy = sortBy,
            filters = filters,
            page = page
        )

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(items) { it.item.id to it.mediaType }
            } else {
                items
            }
        return kidsFilterPage(
            TagRailPage(filtered, items.size >= 20, nextPage = nextPage)
        )
    }

    /**
     * Initial rail set for a genre-filtered discover screen. Rail titles
     * (and therefore section keys) match the unfiltered screens so the
     * ViewModels' paging code works unchanged.
     */
    suspend fun getInitialCrossGenreSections(
        base: CrossBase,
        genreId: Int
    ): List<StudioSection> = coroutineScope {
        val titles = when (base.kind) {
            "decade" -> DECADE_RAIL_TITLES
            // Same rail set when a genre chip is active — the genre filter
            // must not silently drop the page's movie rails.
            "network" -> TmdbRailPages.networkRailTitles(base.companyId)
            else -> SERVICE_RAIL_TITLES
        }
        val pages = titles.map { title ->
            async { getCrossGenreRailPage(base, genreId, title, 1) }
        }.awaitAll()
        titles.mapIndexed { index, title ->
            pages[index].items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
        }.filterNotNull()
    }

    /**
     * True when the Home digital-release filter is enabled in Settings.
     */
    fun isDigitalFilterEnabled(): Boolean =
        AppPreferences.getHomeRailHideUpcoming(appContext)

    /**
     * The `with_original_language` value every browse/discover rail should
     * filter with: "en" while Settings' English-only switch is on (the
     * default), null when it is off.
     *
     * Deliberately not applied to free-text search — a search for a title by
     * name must still find it whatever its original language. This gates the
     * discover surfaces only: the Search browse chips (Genre / Keyword /
     * Service / Network / Studio / Decade) and the rails they open.
     */
    internal fun browseLanguage(): String? =
        if (AppPreferences.getBrowseEnglishOnly(appContext)) "en" else null

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

    // ------------------------------------------------------------------
    // Kids Mode enforcement. Every discover-backed rail (genres, keywords,
    // studios, networks, services, decades, KB folders) funnels through
    // the *RailPage / *Section helpers below, so certifying each page once
    // here covers the whole browse surface for the ACTIVE profile. Titles
    // above the profile's rating ceiling are dropped; the check reuses the
    // shared detail cache, so it costs nothing once a title has been
    // enriched elsewhere (and each item is one cached TMDB detail fetch
    // the first time it appears on any kids profile).
    // ------------------------------------------------------------------

    /** Kids Mode ceiling of the ACTIVE profile (null = off). */
    fun kidsMaxAge(): Int? =
        ProfileManager.activeProfile.value?.kidsMaxAge

    /** True when kids mode is on and the ceiling is strict (PG/G). */
    /**
     * True when kids mode is on and the ceiling is strict (PG/G): strict
     * ceilings drop titles whose certification cannot be resolved, and
     * hide person credits that carry no media type at all.
     */
    fun isKidsModeStrict(): Boolean = kidsMaxAge() != null && kidsMaxAge() != KidsMode.CEIL_PG13

    /** Certification check for one (tmdbId, mediaType) pair under the active ceiling. */
    private suspend fun kidsAllowed(tmdbId: Int, mediaType: String): Boolean {
        val ceiling = kidsMaxAge() ?: return true
        val isSeries = mediaType.equals("series", ignoreCase = true) ||
            mediaType.equals("tv", ignoreCase = true)
        val detail = availabilitySemaphore.withPermit {
            runCatching {
                fetchEnrichedMetaCached(
                    imdbId = "tmdb:$tmdbId",
                    type = if (isSeries) "series" else "movie"
                )
            }.getOrNull()
        } ?: return ceiling == KidsMode.CEIL_PG13
        return KidsMode.allowed(ceiling, detail.certification(isMovie = !isSeries))
    }

    /**
     * Drop every item above the active profile's rating ceiling. Returns
     * the list unchanged when kids mode is off, so non-kids profiles pay
     * a single null-check per page.
     */
    suspend fun <T> kidsFilter(
        items: List<T>,
        key: (T) -> Pair<Int, String>
    ): List<T> {
        if (items.isEmpty() || kidsMaxAge() == null) return items
        return coroutineScope {
            items.map { item ->
                async {
                    val (tmdbId, mediaType) = key(item)
                    if (kidsAllowed(tmdbId, mediaType)) item else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Meta-level kids filter for Stremio catalog rails (Home, addon
     * results): keys by the meta's raw id — IMDB ids resolve through TMDB
     * /find, "tmdb:"/numeric ids fetch directly — via the same enriched-
     * detail cache every other surface uses. The generic [kidsFilter]
     * above only handles TMDB-id-keyed items, which catalog metas are not.
     */
    suspend fun kidsFilterMetas(metas: List<com.kennyb1201.kbstream.data.addon.MetaPreview>): List<com.kennyb1201.kbstream.data.addon.MetaPreview> {
        if (metas.isEmpty() || kidsMaxAge() == null) return metas
        return coroutineScope {
            metas.map { meta ->
                async {
                    val isSeries = meta.type.equals("series", ignoreCase = true) ||
                        meta.type.equals("tv", ignoreCase = true)
                    val ceiling = kidsMaxAge()
                    val detail = availabilitySemaphore.withPermit {
                        runCatching {
                            fetchEnrichedMetaCached(
                                imdbId = meta.id,
                                type = if (isSeries) "series" else "movie"
                            )
                        }.getOrNull()
                    }
                    val allowed = if (detail == null) {
                        ceiling == KidsMode.CEIL_PG13
                    } else {
                        KidsMode.allowed(ceiling, detail.certification(isMovie = !isSeries))
                    }
                    if (allowed) meta else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Kids filter for person combined-credits (cast or crew). Credits carry
     * media_type on /person/{id}?append_to_response=combined_credits; items
     * without one are dropped only under a strict ceiling (PG/G hides
     * unknowns), matching the known-unknown rule elsewhere.
     */
    suspend fun kidsFilterPersonCredits(credits: List<TmdbPersonCredit>): List<TmdbPersonCredit> {
        if (credits.isEmpty() || kidsMaxAge() == null) return credits
        return coroutineScope {
            credits.map { credit ->
                async {
                    val mediaType = when (credit.mediaType?.lowercase()) {
                        "movie" -> "movie"
                        "tv", "series" -> "series"
                        else -> return@async if (isKidsModeStrict()) null else credit
                    }
                    if (kidsAllowed(credit.id, mediaType)) credit else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Kids filter keyed on the discover item itself. */
    private suspend fun kidsFilterItems(items: List<StudioItem>): List<StudioItem> {
        if (items.isEmpty() || kidsMaxAge() == null) return items
        return kidsFilter(items) { it.item.id to it.mediaType }
    }

    /** Kids filter for a rail page (shared return shape of every rail loader). */
    internal suspend fun kidsFilterPage(page: TagRailPage): TagRailPage {
        if (page.items.isEmpty() || kidsMaxAge() == null) return page
        return page.copy(items = kidsFilterItems(page.items))
    }

    /**
     * Entries with no artwork. TMDB carries placeholder entries (announced
     * announcements, festival stubs, merges) that have a title and nothing
     * else; a poster grid can only render them as an empty card, and the
     * RECENT rails no longer have a vote floor to keep them out.
     */
    private fun dropPosterless(items: List<StudioItem>): List<StudioItem> =
        items.filter { !it.item.posterPath.isNullOrBlank() }

    /**
     * Shared tail of every rail-page loader (see [TmdbRailPages]): drop
     * duplicates, drop artwork-less placeholders, apply the digital-release
     * filter when it is on, then the active profile's kids ceiling. `hasMore`
     * reflects the RAW result set, so filtering can never stop a rail from
     * paging.
     */
    internal suspend fun finishRailPage(
        results: List<StudioItem>,
        nextPage: Int = 2
    ): TagRailPage {
        val distinct = dropPosterless(results.distinctBy { it.item.id })

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(distinct) {
                    it.item.id to it.mediaType
                }
            } else {
                distinct
            }

        return kidsFilterPage(
            TagRailPage(
                items = filtered,
                hasMore = results.isNotEmpty(),
                nextPage = nextPage
            )
        )
    }

    /**
     * [finishRailPage] with depth: the four dimension rail loaders in
     * [TmdbRailPages] (genre / keyword / network / company) fetch page 1
     * through [load] and, while the rail is still under
     * [RAIL_DEPTH_TARGET_ITEMS], through the pages after it. A page is only
     * followed by another when it came back non-empty — a short catalog ends
     * the loop instead of asking TMDB for pages that cannot exist.
     *
     * Merged pages go through [finishRailPage] once, so the artwork and
     * availability filters still run a single time over the whole rail, and
     * the reported [TagRailPage.nextPage] is the first page not merged yet.
     * Paging past page 1 is untouched: a "load more" fetches exactly the page
     * it asked for.
     */
    internal suspend fun finishDeepRailPage(
        page: Int,
        load: suspend (Int) -> List<StudioItem>
    ): TagRailPage {
        val first = load(page)
        if (page != 1) return finishRailPage(first, nextPage = page + 1)

        val merged = first.toMutableList()
        var next = 2
        while (merged.size < RAIL_DEPTH_TARGET_ITEMS && next <= RAIL_DEPTH_MAX_PAGE) {
            val more = load(next)
            if (more.isEmpty()) break
            merged += more
            next++
        }
        return finishRailPage(merged, nextPage = next)
    }

    /**
     * The same deepening for the discover-backed rails (service, decade,
     * cross-genre), which build their own `KBFilters` instead of going through
     * [TmdbRailPages]. Returns the merged rows paired with the page a later
     * "load more" should ask for.
     */
    private suspend fun deepenDiscover(
        mediaType: String,
        sortBy: String,
        filters: com.kennyb1201.kbstream.data.kb.KBFilters,
        page: Int
    ): Pair<List<StudioItem>, Int> {
        val itemType = if (mediaType.equals("tv", ignoreCase = true)) "series" else "movie"

        suspend fun load(p: Int): List<StudioItem> = runCatching {
            discoverKB(mediaType = mediaType, page = p, sortBy = sortBy, filters = filters)
        }.getOrNull().orEmpty()
            .map { StudioItem(it, itemType) }
            .distinctBy { it.item.id }

        val first = load(page)
        if (page != 1) return first to (page + 1)

        val merged = first.toMutableList()
        var next = 2
        while (merged.size < RAIL_DEPTH_TARGET_ITEMS && next <= RAIL_DEPTH_MAX_PAGE) {
            val more = load(next)
            if (more.isEmpty()) break
            merged += more
            next++
        }
        // Across pages, not just within one: a popularity-sorted discover
        // page shifts as items gain votes, so page 2 can repeat a row page 1
        // already had. (The [finishDeepRailPage] path is deduped by
        // [finishRailPage] instead.)
        return merged.distinctBy { it.item.id } to next
    }

    suspend fun getGenreRailPage(genreId: Int, title: String, page: Int): TagRailPage =
        TmdbRailPages.genrePage(this, genreId, title, page)

    suspend fun getKeywordRailPage(keywordId: Int, title: String, page: Int): TagRailPage =
        TmdbRailPages.keywordPage(this, keywordId, title, page)

    suspend fun getNetworkRailPage(
        networkId: Int,
        title: String,
        page: Int,
        companyId: Int? = null
    ): TagRailPage = TmdbRailPages.networkPage(this, networkId, title, page, companyId)

    suspend fun getCompanyRailPage(companyId: Int, title: String, page: Int): TagRailPage =
        TmdbRailPages.companyPage(this, companyId, title, page)

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
     * like the genre/keyword screens. Decades reuse the KB filter
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

        // TOP RATED sorts by vote COUNT ("most voted"): vote_average
        // surfaces obscure 8.5-rated shorts with 12 votes (heavily anime);
        // vote_count surfaces what people actually voted on. Floors are
        // light so mid-size catalogs keep real rails; "Popular"
        // (popularity.desc) needs just enough to skip no-title entries.
        val voteFloor = if (sortBy.startsWith("vote_count")) {
            minTopRatedVoteCount
        } else {
            minVoteCount
        }
        val filters = com.kennyb1201.kbstream.data.kb.KBFilters(
            year = yearRange,
            voteCountGte = voteFloor,
            withOriginalLanguage = browseLanguage()
        )

        val (items, nextPage) = deepenDiscover(
            mediaType = if (isTv) "tv" else "movie",
            sortBy = sortBy,
            filters = filters,
            page = page
        )

        // A discover page caps at 20 items; a full page means more exist.
        return kidsFilterPage(TagRailPage(items, items.size >= 20, nextPage = nextPage))
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

        // Recent sorts by release date, which differs per media type; a
        // vote floor keeps "recent" from surfacing announced-but-unreleased
        // entries (they have almost no votes yet). "voted" sorts by
        // vote COUNT (not average): average surfaces obscure 10-vote
        // foreign/anime titles; count is what "most voted" means and is
        // anime-resistant without the language filter. RECENT has NO year
        // cap — newest first, whatever the service's catalog holds.
        val sortBy = when (mode) {
            "recent" -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            "voted" -> "vote_count.desc"
            else -> "popularity.desc"
        }
        val voteFloor = when (mode) {
            "recent" -> minRecentVoteCount
            "popular" -> minVoteCount
            "voted" -> minTopRatedVoteCount
            else -> minRecentVoteCount
        }

        val base = com.kennyb1201.kbstream.data.kb.KBFilters(
            voteCountGte = voteFloor,
            withOriginalLanguage = browseLanguage()
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
                // Same released-content cap as the genre/keyword/company rails:
                // a floor of 5 doesn't stop a heavily-anticipated unreleased
                // blockbuster (thousands of pre-release votes) from topping RECENT.
                releaseDateLte = today
            )
        }
        val (items, nextPage) = deepenDiscover(
            mediaType = if (isTv) "tv" else "movie",
            sortBy = sortBy,
            filters = filters,
            page = page
        )

        // A discover page caps at 20 items; a full page means more exist.
        return kidsFilterPage(TagRailPage(items, items.size >= 20, nextPage = nextPage))
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
            "TOP RATED" -> "vote_count.desc"
            else -> return TagRailPage(emptyList(), false)
        }

        val result = getDecadeSectionPage(decadeStart, mediaType, sortBy, page)
        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(result.items) { it.item.id to it.mediaType }
            } else {
                result.items
            }
        return kidsFilterPage(
            TagRailPage(filtered, result.hasMore, nextPage = result.nextPage)
        )
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

    /**
     * Service-page rails. The six provider rails cover everything streaming
     * on the service NOW; the ORIGINALS rails (network + company discover)
     * add everything the brand PRODUCED — including titles that have since
     * left the service and co-productions TMDB tags with the company but
     * never listed under the provider. Originals sections are inserted
     * FIRST (they are the identity of the page); provider rails follow.
     *
     * Both originals ids are optional and independent:
     *  - [networkOrCompanyId] + [networkIsCompany=false] → TV-only originals
     *    (network discover; a network id has no movie equivalent).
     *  - [originalsCompanyId] → full originals (company discover: movies +
     *    TV). When the header id IS the company (niche streamers), the
     *    network rail would duplicate it, so only the company rail runs.
     */
    suspend fun getInitialServiceSections(
        providerId: Int?,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        originalsCompanyId: Int? = null
    ): List<StudioSection> = coroutineScope {
        val networkOriginals = async {
            if (networkOrCompanyId != null && !networkIsCompany) {
                runCatching { getNetworkRailPage(networkOrCompanyId, "SERIES · RECENT", 1) }
                    .getOrNull()
            } else {
                null
            }
        }
        val companyOriginals = async {
            if (originalsCompanyId != null) {
                runCatching { getCompanyRailPage(originalsCompanyId, "MOVIES · RECENT", 1) }
                    .getOrNull()
            } else {
                null
            }
        }
        val providerPages = SERVICE_RAIL_TITLES.map { title ->
            async {
                providerId?.let { runCatching { getServiceRailPage(it, title, 1) }.getOrNull() }
            }
        }.awaitAll()

        val sections = mutableListOf<StudioSection>()
        networkOriginals.await()?.items?.takeIf { it.isNotEmpty() }?.let {
            sections.add(StudioSection("ORIGINALS · SERIES", it))
        }
        companyOriginals.await()?.items?.takeIf { it.isNotEmpty() }?.let {
            sections.add(StudioSection("ORIGINALS · MOVIES", it))
        }
        SERVICE_RAIL_TITLES.mapIndexed { index, title ->
            providerPages[index]?.items?.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
        }.filterNotNull().let { sections.addAll(it) }
        sections
    }

    suspend fun getServiceRailPage(
        providerId: Int?,
        title: String,
        page: Int,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        originalsCompanyId: Int? = null
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("·").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }

        // ORIGINALS rails discover through the brand's network/company ids
        // (what it made) instead of the watch provider (what is streaming).
        // A company id discovers movies AND TV; a network id is TV-only.
        if (parts.getOrNull(0)?.uppercase() == "ORIGINALS") {
            val isMoviesRail = parts.getOrNull(1)?.uppercase() == "MOVIES"
            val companyId = when {
                isMoviesRail -> originalsCompanyId
                networkIsCompany -> networkOrCompanyId
                else -> originalsCompanyId
            }
            if (companyId != null) {
                val railTitle = (if (isMoviesRail) "MOVIES" else "SERIES") + " \u00B7 RECENT"
                return getCompanyRailPage(companyId, railTitle, page)
            }
            if (!networkIsCompany && networkOrCompanyId != null) {
                return getNetworkRailPage(networkOrCompanyId, "SERIES \u00B7 RECENT", page)
            }
            return TagRailPage(emptyList(), false)
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
        return kidsFilterPage(
            TagRailPage(filtered, result.hasMore, nextPage = result.nextPage)
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

    suspend fun getByNetwork(
        networkId: Int,
        companyId: Int? = null
    ): List<StudioSection> =
        getInitialNetworkSections(networkId, companyId)

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

        fun rankLogos(logos: List<TmdbCompanyLogo>): List<TmdbCompanyLogo> =
            logos.filter { !it.filePath.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<TmdbCompanyLogo> { it.iso6391 == "en" }
                        .thenByDescending { it.iso6391 == null }
                        .thenByDescending { it.voteAverage ?: 0.0 }
                        .thenByDescending { it.width ?: 0 }
                )

        /**
         * True when the logo is a solid filled badge — a near-square mark
         * with most of its bounding box opaque (ABC's top-voted logo is a
         * filled disc). White-tinted on the dark header these read as an
         * anonymous circle, so the picker skips them when a letterform
         * option exists. Downloads a w185 thumbnail (a few KB) only for the
         * top-ranked candidate; any failure returns false (keep the pick).
         */
        suspend fun isSolidBadge(filePath: String): Boolean = runCatching {
            // Network + decode must stay off the caller's (Main) dispatcher.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = okhttp3.Request.Builder()
                    .url("https://image.tmdb.org/t/p/w185$filePath")
                    .build()
                TmdbRepository.sharedOkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val bytes = response.body?.bytes() ?: return@withContext false
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size
                    ) ?: return@withContext false
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    if (pixels.isEmpty()) return@withContext false
                    var opaque = 0
                    for (pixel in pixels) {
                        if (((pixel ushr 24) and 0xFF) > 200) opaque++
                    }
                    val coverage = opaque.toFloat() / pixels.size
                    val aspect = bitmap.width.toFloat() / bitmap.height
                    coverage > 0.80f && aspect in 0.70f..1.40f
                }
            }
        }.getOrDefault(false)

        suspend fun pickSmartLogo(company: Boolean): String? {
            val ranked = rankLogos(fetchLogos(company))
            val best = ranked.firstOrNull() ?: return null
            val path = if (isSolidBadge(best.filePath!!)) {
                ranked.firstOrNull { candidate ->
                    candidate.filePath != null && !isSolidBadge(candidate.filePath)
                }?.filePath ?: best.filePath
            } else {
                best.filePath
            }
            return path?.let { TmdbRepository.LOGO_BASE + it }
        }

        if (isNetwork) {
            // Primary: the network's own logos. Fallback: same id as a company
            // (some entries exist in both spaces).
            return pickSmartLogo(company = false)
                ?: pickSmartLogo(company = true)
        }
        return pickSmartLogo(company = true)
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
        private var instance: TmdbRepository? = null

        /**
         * Process-wide instance. Context is only used on first construction
         * (applicationContext is retained); afterwards it is ignored, so
         * passing an Activity context from any call site is leak-safe.
         */
        fun getInstance(context: Context): TmdbRepository =
            instance ?: synchronized(this) {
                instance ?: TmdbRepository(context).also { instance = it }
            }

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
        // w780, not original: company/network logo PNGs at "original" are
        // routinely 1500-2500px wide (hundreds of KB to a few MB). They
        // are drawn into a 360dp header slot and force-decoded in software
        // for the pixel analysis behind BrandLogo, so the original size
        // only made headers look logo-less for as long as the download
        // took.
        const val LOGO_BASE = "https://image.tmdb.org/t/p/w780"
        private const val MAX_IMDB_DISK_AGE_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
