package com.kennyb1201.kbstream.ui.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.VideoEntry
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import com.kennyb1201.kbstream.data.tmdb.displayRuntimeMinutes
import com.kennyb1201.kbstream.data.tmdb.list
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

fun computeEpisodeWatched(
    parentId: String,
    season: Int?,
    episode: Int?,
    episodeStreamId: String?,
    completedIds: Set<String>,
    watchedKeys: Set<String>
): Boolean {
    val streamMatch = !episodeStreamId.isNullOrBlank() && episodeStreamId in completedIds
    if (streamMatch) return true

    if (parentId.isBlank() || season == null || episode == null) return false
    val key = WatchedEpisodeState.buildEpisodeKey(parentId, season, episode) ?: return false
    return key in watchedKeys
}

class DetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager.getInstance(application)
    private val tmdbRepository = TmdbRepository(application)
    private val simklRepository = SimklRepository.getInstance(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()
    private val watchHistoryRepository = WatchHistoryRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _meta = MutableStateFlow<Meta?>(null)
    val meta: StateFlow<Meta?> = _meta.asStateFlow()

    private val _tmdbDetail = MutableStateFlow<TmdbDetail?>(null)

    /**
     * True when [_tmdbDetail] is a synthetic stand-in built from the addon's
     * Meta.videos list (TVDB/anime titles with no TMDB match) rather than a
     * real TMDB record. Episodes then come from the addon data instead of
     * TMDB season endpoints.
     */
    private val _isSyntheticDetail = MutableStateFlow(false)
    val isSyntheticDetail: StateFlow<Boolean> = _isSyntheticDetail
    val tmdbDetail: StateFlow<TmdbDetail?> = _tmdbDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _episodes = MutableStateFlow<List<ResolvedEpisode>>(emptyList())
    val episodes: StateFlow<List<ResolvedEpisode>> = _episodes.asStateFlow()

    private val _episodesLoading = MutableStateFlow(false)
    val episodesLoading: StateFlow<Boolean> = _episodesLoading.asStateFlow()

    private val _episodeError = MutableStateFlow<String?>(null)
    val episodeError: StateFlow<String?> = _episodeError.asStateFlow()

    private val _resumeInfo = MutableStateFlow<WatchHistoryEntity?>(null)
    val resumeInfo: StateFlow<WatchHistoryEntity?> = _resumeInfo.asStateFlow()

    private val _completedEpisodeIds = MutableStateFlow<Set<String>>(emptySet())
    val completedEpisodeIds: StateFlow<Set<String>> = _completedEpisodeIds.asStateFlow()

    private val _simklWatchedEpisodes = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val simklWatchedEpisodes: StateFlow<Set<Pair<Int, Int>>> = _simklWatchedEpisodes.asStateFlow()

    private val _simklSeriesWatched = MutableStateFlow(false)
    val simklSeriesWatched: StateFlow<Boolean> = _simklSeriesWatched.asStateFlow()

    private val _watchedEpisodeKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedEpisodeKeys: StateFlow<Set<String>> = _watchedEpisodeKeys.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _resolvedPosterIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedPosterIds: StateFlow<Map<String, String>> = _resolvedPosterIds.asStateFlow()

    private val _collection = MutableStateFlow<TmdbCollectionDetail?>(null)
    val collection: StateFlow<TmdbCollectionDetail?> = _collection.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedPersonDetail = MutableStateFlow<TmdbPersonDetail?>(null)
    val selectedPersonDetail: StateFlow<TmdbPersonDetail?> = _selectedPersonDetail.asStateFlow()

    private val _selectedPersonLoading = MutableStateFlow(false)
    val selectedPersonLoading: StateFlow<Boolean> = _selectedPersonLoading.asStateFlow()

    private val _selectedPersonError = MutableStateFlow<String?>(null)
    val selectedPersonError: StateFlow<String?> = _selectedPersonError.asStateFlow()

    private val _targetEpisode = MutableStateFlow<ResolvedEpisode?>(null)
    val targetEpisode: StateFlow<ResolvedEpisode?> = _targetEpisode.asStateFlow()

    private val _loadedSeason = MutableStateFlow<Int?>(null)
    val loadedSeason: StateFlow<Int?> = _loadedSeason.asStateFlow()

    private val _playButtonText = MutableStateFlow("Play")
    val playButtonText: StateFlow<String> = _playButtonText.asStateFlow()

    private var imdbId: String = ""
    private var latestEpisodeSeasonRequest: Int? = null

    // Hot reactive StateFlow checking if stream addons are configured and present globally
    val hasStreamAddons: StateFlow<Boolean> = addonManager.streamAddons
        .map { addons -> addons.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = addonManager.streamAddons.value.isNotEmpty()
        )

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun posterLookupKey(tmdbId: Int, mediaType: String): String = "${mediaType.lowercase()}::$tmdbId"

    private fun normalizeMediaType(type: String): String = when (type.lowercase()) {
        "tv", "show" ->
            "series"
        // Anime catalogs emit type "anime" (and movies "anime.movie"); TMDB
        // and most meta addons serve them under series/movie, so normalize
        // before building meta/episode endpoints. Ids are unaffected.
        "anime", "anime.series" ->
            "series"
        "anime.movie" ->
            "movie"
        else ->
            type.lowercase()
    }

    // Hot reactive check to observe if the current item is marked watched in real-time
    fun observeIsWatched(id: String, type: String): StateFlow<Boolean> {
        return watchedStatusRepository.observeIsWatched(id, type)
    }

    private fun refreshPosterWatchedStatus(type: String) {
        viewModelScope.launch {
            try {
                val normalizedType = type.lowercase()

                val rawItems = buildList {
                    _collection.value?.parts.orEmpty().forEach { part ->
                        add(part.id to "movie")
                    }
                    _tmdbDetail.value?.recommendations?.results.orEmpty().forEach { rec ->
                        add(rec.id to normalizedType)
                    }
                }.distinct()

                if (rawItems.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedPosterIds.value = emptyMap()
                    Log.i("KBStream", "poster watched refresh: no items")
                    return@launch
                }

                val resolvedItems = rawItems.mapNotNull { (tmdbId, mediaType) ->
                    val imdb = runCatching {
                        resolveImdbId(tmdbId, mediaType)
                    }.getOrNull()

                    if (imdb.isNullOrBlank()) {
                        null
                    } else {
                        Triple(tmdbId, mediaType, imdb)
                    }
                }.distinct()

                if (resolvedItems.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedPosterIds.value = emptyMap()
                    Log.i("KBStream", "poster watched refresh: no resolvable imdb ids")
                    return@launch
                }

                _resolvedPosterIds.value = resolvedItems.associate { (tmdbId, mediaType, imdbId) ->
                    posterLookupKey(tmdbId, mediaType) to imdbId
                }

                val preloadItems = resolvedItems
                    .map { (_, mediaType, imdbId) -> imdbId to mediaType }
                    .distinct()

                watchedStatusRepository.preload(preloadItems)

                _watchedKeys.value = resolvedItems
                    .filter { (_, mediaType, imdbId) ->
                        watchedStatusRepository.isWatchedCached(imdbId, mediaType)
                    }
                    .map { (_, mediaType, imdbId) ->
                        watchedKey(imdbId, mediaType)
                    }
                    .toSet()

                Log.i(
                    "KBStream",
                    "poster watched refresh resolved=${resolvedItems.size} watched=${_watchedKeys.value.size}"
                )
            } catch (e: Exception) {
                _watchedKeys.value = emptySet()
                _resolvedPosterIds.value = emptyMap()
                Log.e("KBStream", "poster watched refresh failed", e)
            }
        }
    }

    fun load(
        type: String,
        id: String,
        initialSeason: Int? = null,
        initialMeta: Meta? = null
    ) {
        imdbId = id
        _meta.value = initialMeta
        _tmdbDetail.value = null
        _isSyntheticDetail.value = false
        _episodes.value = emptyList()
        _episodeError.value = null
        _episodesLoading.value = false
        latestEpisodeSeasonRequest = initialSeason
        _collection.value = null
        _simklSeriesWatched.value = false
        _resolvedPosterIds.value = emptyMap()
        _targetEpisode.value = null
        _loadedSeason.value = null
        _playButtonText.value = "Play"
        _selectedPersonDetail.value = null
        _selectedPersonLoading.value = false
        _selectedPersonError.value = null

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val normalizedType = normalizeMediaType(type)
                Log.e("KBStream", "detail load start type=$normalizedType id=$id initialSeason=$initialSeason")

                val addonsDeferred = async { addonManager.getInstalledAddons() }
                val tmdbDeferred = async { runCatching { tmdbRepository.fetchEnrichedMeta(id, normalizedType) } }
                val resumeDeferred = async { runCatching { historyDao.getResumeForParent(id) } }
                val completedDeferred = async { runCatching { historyDao.getCompletedForParent(id) } }

                // 1. Await structural and history components first so watched data is guaranteed ready
                val tmdbDetailResult = tmdbDeferred.await()
                val localResume = resumeDeferred.await().getOrNull()

                // Simkl cloud-session fallback: when local history has no
                // in-progress position for this title, derive a display-only
                // resume row from the paused Simkl playback session so Detail
                // shows RESUME + progress for cloud-tracked progress too.
                _resumeInfo.value =
                    if (localResume != null && localResume.positionMs > 0L) {
                        localResume
                    } else {
                        simklPlaybackResumeFor(
                            id = id,
                            type = normalizedType,
                            tmdbId = tmdbDetailResult.getOrNull()?.id
                        )
                    }

                val localCompletedEntries = completedDeferred.await().getOrDefault(emptyList())
                _completedEpisodeIds.value = localCompletedEntries.map { it.id }.toSet()

                // 2. Fetch Simkl watch states utilizing the resolved TMDB show ID safely
                val simklCompleted = if (
                    normalizedType == "series" &&
                    simklRepository.isConfigured() &&
                    simklRepository.hasToken()
                ) {
                    val tmdbShowId = tmdbDetailResult.getOrNull()?.id
                    runCatching {
                        simklRepository.getWatchedEpisodesForShowByImdb(imdbId = id, tmdbId = tmdbShowId)
                    }.getOrDefault(emptySet())
                } else {
                    emptySet()
                }

                _simklWatchedEpisodes.value = simklCompleted
                _simklSeriesWatched.value = simklCompleted.isNotEmpty()

                // 3. Build merged keys *before* evaluating target episodes or seasons
                _watchedEpisodeKeys.value = WatchedEpisodeState.buildMergedWatchedKeys(
                    parentId = id,
                    localCompletedEntries = localCompletedEntries,
                    simklCompletedEpisodes = simklCompleted
                )

                // 4. Handle Meta addon loading asynchronously in background
                val addons = addonsDeferred.await()

// Probe order: addons whose manifest idPrefixes match this id first
// (a TVDB addon for "tvdb:...", a Cinemeta-style IMDB addon for "tt..."),
// then legacy manifests that declare no prefixes, then declared-but-
// non-matching addons as a last resort. Prevents an IMDB-only addon from
// winning the probe race for TVDB-sourced titles and vice versa.
val metaAddons = addonManager.orderMetaAddonsForId(addons, id, normalizedType)

Log.e(
    "KBStream",
    "detail meta: type=$normalizedType id=$id candidates=${metaAddons.map { it.name }}"
)

var resolvedMeta: Meta? = null
var lastMetaError: Throwable? = null

for (metaAddon in metaAddons) {
    val result = runCatching {
        val baseUrl = metaAddon.manifestUrl.substringBeforeLast("/manifest.json")
        repository.getMeta(baseUrl, normalizedType, id)
    }

    result.onSuccess { response ->
        if (response != null) {
            resolvedMeta = response
            Log.e(
                "KBStream",
                "detail meta resolved addon=${metaAddon.name} id=$id"
            )
        } else {
            Log.e(
                "KBStream",
                "detail meta empty addon=${metaAddon.name} id=$id"
            )
        }
    }.onFailure { error ->
        lastMetaError = error
        Log.e(
            "KBStream",
            "detail meta failed addon=${metaAddon.name} id=$id",
            error
        )
    }

    if (resolvedMeta != null) break
}

    fun buildMergedMeta(
        tmdbDetail: TmdbDetail?,
        addonMeta: Meta?,
        initialMeta: Meta?
    ): Meta {
        val tmdbMeta = tmdbDetail?.let { detail ->
            Meta(
                id = id,
                type = normalizedType,
                name = detail.name ?: detail.title ?: id,
                poster = detail.posterPath?.let { TmdbRepository.POSTER_BASE + it },
                background = detail.backdropPath?.let { TmdbRepository.BACKDROP_BASE + it },
                logo = null,
                description = detail.overview,
                releaseInfo = if (normalizedType == "series") {
                    detail.firstAirDate?.takeIf { it.isNotBlank() }?.take(4)
                } else {
                    detail.releaseDate?.takeIf { it.isNotBlank() }?.take(4)
                },
                imdbRating = detail.voteAverage?.let { "%.1f".format(it) },
                runtime = if (normalizedType == "series") {
                    detail.episodeRunTime.firstOrNull()?.toString()
                } else {
                    detail.runtime?.toString()
                },
                language = detail.originalLanguage,
                country = detail.originCountries.firstOrNull(),
                awards = detail.awards,
                website = null,
                genres = detail.genres.map { it.name },
                cast = detail.credits?.cast?.map { it.name },
                director = detail.credits?.crew
                    ?.filter { it.job.equals("Director", ignoreCase = true) }
                    ?.map { it.name },
                videos = detail.videos?.results?.map { video ->
                    VideoEntry(
                        id = video.key,
                        title = video.name,
                        description = video.site,
                        thumbnail = video.thumbnail
                    )
                }
            )
        }

        val releaseFromAddon = addonMeta?.releaseInfo?.takeIf { it.isNotBlank() }
        val releaseFromTmdb = tmdbMeta?.releaseInfo?.takeIf { it.isNotBlank() }

        return Meta(
            id = id,
            type = normalizedType,
            name = tmdbMeta?.name ?: addonMeta?.name ?: initialMeta?.name ?: id,
            poster = tmdbMeta?.poster ?: addonMeta?.poster ?: initialMeta?.poster,
            background = tmdbMeta?.background ?: addonMeta?.background ?: initialMeta?.background,
            logo = addonMeta?.logo ?: initialMeta?.logo,
            description = tmdbMeta?.description ?: addonMeta?.description ?: initialMeta?.description,
            releaseInfo = releaseFromAddon ?: releaseFromTmdb,
            imdbRating = tmdbMeta?.imdbRating ?: addonMeta?.imdbRating,
            runtime = tmdbMeta?.runtime ?: addonMeta?.runtime,
            language = tmdbMeta?.language ?: addonMeta?.language,
            country = tmdbMeta?.country ?: addonMeta?.country,
            awards = tmdbMeta?.awards ?: addonMeta?.awards,
            website = addonMeta?.website ?: initialMeta?.website,
            genres = tmdbMeta?.genres?.takeIf { it.isNotEmpty() }
                ?: addonMeta?.genres?.takeIf { it.isNotEmpty() }
                ?: initialMeta?.genres,
            cast = tmdbMeta?.cast?.takeIf { it.isNotEmpty() }
                ?: addonMeta?.cast?.takeIf { it.isNotEmpty() }
                ?: initialMeta?.cast,
            director = tmdbMeta?.director?.takeIf { it.isNotEmpty() }
                ?: addonMeta?.director?.takeIf { it.isNotEmpty() }
                ?: initialMeta?.director,
            videos = tmdbMeta?.videos?.takeIf { it.isNotEmpty() }
                ?: addonMeta?.videos?.takeIf { it.isNotEmpty() }
                ?: initialMeta?.videos
        )
    }

    if (resolvedMeta != null) {
        val savedMeta = initialMeta
        _meta.value = buildMergedMeta(
            tmdbDetail = tmdbDetailResult.getOrNull(),
            addonMeta = resolvedMeta,
            initialMeta = savedMeta
        )
    } else {
        val fallbackDetail = tmdbDetailResult.getOrNull()

        if (fallbackDetail != null) {
            _meta.value = buildMergedMeta(
                tmdbDetail = fallbackDetail,
                addonMeta = null,
                initialMeta = initialMeta
            )
        } else if (initialMeta != null) {
            _meta.value = initialMeta
        }

        Log.e(
            "KBStream",
            "detail meta unresolved type=$normalizedType id=$id",
            lastMetaError
        )

        if (_meta.value == null) {
            _error.value =
                "Couldn't load details for this title. " +
                    "Check that your add-ons are reachable, then try again."
        }
    }

    // TVDB / anime fallback: when TMDB has no record for this id (find
    // returned nothing) but the addon meta carries the Stremio-standard
    // Meta.videos episode list, synthesize a minimal TmdbDetail stand-in
    // from it. The existing season/episode pipeline (autoLoadRelevantSeason,
    // season picker, episode browser, watch markers) then works unchanged
    // for titles TMDB does not know; loadEpisodesForSeason checks the
    // synthetic flag and serves addon videos instead of calling TMDB.
    if (tmdbDetailResult.getOrNull() == null && normalizedType == "series") {
        val addonVideos = _meta.value?.videos.orEmpty()
            .filter { it.season != null && it.episode != null }
        if (addonVideos.isNotEmpty()) {
            val seasons = addonVideos
                .mapNotNull { it.season }
                .distinct()
                .sorted()
            _tmdbDetail.value = TmdbDetail(
                id = -1,
                name = _meta.value?.name,
                overview = _meta.value?.description,
                seasons = seasons.map { seasonNum ->
                    TmdbSeasonSummary(
                        seasonNumber = seasonNum,
                        name = "Season $seasonNum",
                        episodeCount = addonVideos.count { it.season == seasonNum }
                    )
                }
            )
            _isSyntheticDetail.value = true
            Log.e(
                "KBStream",
                "detail meta: synthesized seasons from addon videos " +
                    "id=$id seasons=${seasons.size}"
            )
        }
    }

                tmdbDetailResult.onSuccess { detail ->
                    if (_isSyntheticDetail.value) {
                        // A synthetic addon-videos detail is already in place
                        // (TMDB had no match); never clobber it with a null.
                        return@onSuccess
                    }
                    _tmdbDetail.value = detail
                    Log.e(
                        "KBStream",
                        "tmdbDetail populated type=$normalizedType id=${detail?.id} " +
                            "genres=${detail?.genres?.size} keywords=${detail?.keywords?.list()?.size} " +
                            "cast=${detail?.credits?.cast?.size} crew=${detail?.credits?.crew?.size} " +
                            "companies=${detail?.productionCompanies?.size} " +
                            "reviews=${detail?.reviews?.results?.size} " +
                            "recs=${detail?.recommendations?.results?.size}"
                    )
                    val collectionId = detail?.belongsToCollection?.id
                    if (collectionId != null) {
                        runCatching { tmdbRepository.getCollection(collectionId) }
                            .onSuccess { collection -> _collection.value = collection }
                    }
                    refreshPosterWatchedStatus(normalizedType)
                }

                // 5. Trigger season selection strictly after history states are fully loaded
                if (normalizedType == "series") {
                    autoLoadRelevantSeason(
                        detail = tmdbDetailResult.getOrNull() ?: _tmdbDetail.value,
                        localCompletedEntries = localCompletedEntries,
                        initialSeason = initialSeason
                    )
                }
                
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
                Log.e("KBStream", "detail load failed", e)
            } finally {
                _isLoading.value = false
                Log.e("KBStream", "detail load finished type=$type id=$id")
            }
        }
    }

    private fun autoLoadRelevantSeason(
        detail: TmdbDetail?,
        localCompletedEntries: List<WatchHistoryEntity>,
        initialSeason: Int?
    ) {
        if (detail == null) return

        val seasons = detail.seasons
            ?.mapNotNull { it.seasonNumber }
            ?.filter { it > 0 }
            .orEmpty()

        if (seasons.isEmpty()) return

        val targetSeason = when {
            initialSeason != null && initialSeason in seasons -> initialSeason
            _resumeInfo.value?.season?.let { it > 0 && it in seasons } == true -> _resumeInfo.value!!.season!!
            else -> {
                val latestLocal = localCompletedEntries
                    .mapNotNull { entry ->
                        val s = entry.season
                        val ep = entry.episode
                        if (s != null && ep != null && s in seasons) s to ep else null
                    }
                    .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                    ?.first

                val latestSimkl = _simklWatchedEpisodes.value
                    .filter { it.first in seasons }
                    .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                    ?.first

                listOfNotNull(latestLocal, latestSimkl).maxOrNull() ?: seasons.first()
            }
        }

        // This was previously computed and discarded - nothing ever told the
        // UI or the episodes list which season to actually load. Without this
        // call, episodes only ever loaded once something else (e.g. the
        // screen's own season-selection effect) independently decided on a
        // season, which is why episodes could show empty until the user
        // manually switched seasons.
        loadEpisodesForSeason(targetSeason)
    }

    /**
     * Simkl playback fallback for the Detail screen.
     *
     * The resume row above only reflects LOCAL watch history. When progress
     * lives in a Simkl cloud playback session instead (paused mid-episode on
     * this or another device), synthesize a display-only history row from it
     * so the UI lights up RESUME + progress bar + position. Estimated
     * position = progress% x episode runtime (Simkl exposes only a
     * percentage). Never written back to the local database.
     */
    private suspend fun simklPlaybackResumeFor(
        id: String,
        type: String,
        tmdbId: Int?
    ): WatchHistoryEntity? {

        if (!simklRepository.isConfigured() || !simklRepository.hasToken()) {
            return null
        }

        val sessions = runCatching { simklRepository.getPlaybackItems() }
            .getOrDefault(emptyList())

        val normalizedType = type.lowercase()
        val match = sessions.firstOrNull { session ->
            when (normalizedType) {
                "movie" -> {
                    val ids = session.movie?.ids
                    ids != null && (
                        ids.imdb?.equals(id, ignoreCase = true) == true ||
                            (tmdbId != null && ids.tmdb == tmdbId)
                        )
                }
                "series" -> {
                    val ids = session.show?.ids
                    val ep = session.episode
                    if (ids == null || ep == null) {
                        false
                    } else {
                        ep.season != null && ep.episode != null && (
                            ids.imdb?.equals(id, ignoreCase = true) == true ||
                                (tmdbId != null && ids.tmdb == tmdbId)
                            )
                    }
                }
                else -> false
            }
        } ?: return null

        val progress = (match.progress ?: return null)
            .takeIf { it > 0f && it < 100f }
            ?: return null

        val season: Int?
        val episode: Int?
        var episodeTitle: String? = null
        var name: String
        var runtimeMinutes: Int? = null

        if (normalizedType == "movie") {
            season = null
            episode = null
            name = match.movie?.title ?: "movie-$id"
            // Position estimate needs the movie runtime.
            val movieDetail = tmdbId?.let {
                runCatching { tmdbRepository.getDetailByTmdbId(it, "movie") }.getOrNull()
            }
            runtimeMinutes = movieDetail?.displayRuntimeMinutes()
        } else {
            season = match.episode?.season
            episode = match.episode?.episode
            episodeTitle = match.episode?.title
            name = match.show?.title ?: "show-$id"
            // Position estimate needs an episode runtime; TMDB episode
            // runtime is often empty for TV, so also try the show-level
            // episode_run_time list.
            val detail = tmdbId?.let {
                runCatching { tmdbRepository.getDetailByTmdbId(it, "tv") }.getOrNull()
            }
            runtimeMinutes = detail?.displayRuntimeMinutes()
        }

        val durationMs = runtimeMinutes?.times(60_000L)?.takeIf { it > 0L } ?: 0L
        val positionMs = if (durationMs > 0L) {
            (durationMs * (progress / 100f)).toLong()
        } else {
            0L
        }

        // Synthetic row: display-only. Synthetic rows keep positionMs even
        // when the runtime estimate is missing (positionMs = 0) ONLY when a
        // duration exists; otherwise the UI would show a bar with no time.
        if (positionMs <= 0L && durationMs <= 0L) {
            return null
        }

        val syntheticId =
            if (normalizedType == "movie") "simkl-playback:$id"
            else "simkl-playback:$id:$season:$episode"

        return WatchHistoryEntity(
            id = syntheticId,
            parentId = id,
            type = normalizedType,
            name = name,
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
            overview = null,
            clearLogo = null,
            backdropUrl = null,
            totalEpisodesInSeason = null,
            poster = null,
            streamUrl = null,
            season = season,
            episode = episode,
            // Same "imdbId:season:episode" convention TMDB-resolved
            // rows use, so the per-episode progress bar binds.
            episodeStreamId =
                if (normalizedType == "series" && season != null && episode != null) {
                    "$id:$season:$episode"
                } else {
                    null
                },
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
            isCompleted = false,
            completedAt = null
        )
    }

    fun loadEpisodesForSeason(season: Int) {
        // Synthetic (addon-videos) detail: build the episode list from the
        // Stremio Meta.videos entries instead of a TMDB season endpoint so
        // TVDB/anime titles without a TMDB match still get a full browser.
        // streamId uses the same "<parentId>:<s>:<e>" shape TMDB episodes
        // use, so stream addons and watch-history keys stay compatible.
        if (_isSyntheticDetail.value) {
            latestEpisodeSeasonRequest = season
            _episodes.value = emptyList()
            _episodeError.value = null

            viewModelScope.launch {
                _episodesLoading.value = true
                try {
                    val videos = _meta.value?.videos.orEmpty()
                        .filter { it.season == season && it.episode != null }
                        .sortedBy { it.episode ?: 0 }
                    val parentId = imdbId
                    _episodes.value = videos.map { v ->
                        ResolvedEpisode(
                            streamId = "$parentId:${season}:${v.episode}",
                            episodeNumber = v.episode!!,
                            name = v.title,
                            overview = v.overview ?: v.description,
                            thumbnail = v.thumbnail,
                            runtimeMinutes = null,
                            airDate = v.released,
                            voteAverage = null
                        )
                    }
                    _loadedSeason.value = season
                    val targetEp = _episodes.value.firstOrNull { ep ->
                        val isWatched = computeEpisodeWatched(
                            parentId = imdbId,
                            season = season,
                            episode = ep.episodeNumber,
                            episodeStreamId = ep.streamId,
                            completedIds = _completedEpisodeIds.value,
                            watchedKeys = _watchedEpisodeKeys.value
                        )
                        !isWatched
                    } ?: _episodes.value.firstOrNull()
                    _targetEpisode.value = targetEp
                    _playButtonText.value = targetEp?.let {
                        "Play S${season}E${it.episodeNumber}"
                    } ?: "Play"
                } finally {
                    if (latestEpisodeSeasonRequest == season) {
                        _episodesLoading.value = false
                    }
                }
            }
            return
        }

        val tvId = _tmdbDetail.value?.id
        if (tvId == null) {
            Log.e("KBStream", "episodes skipped: tmdbDetail id is null for imdbId=$imdbId season=$season")
            return
        }

        latestEpisodeSeasonRequest = season
        _episodes.value = emptyList()
        _episodeError.value = null

        viewModelScope.launch {
            _episodesLoading.value = true
            try {
                val seasonEpisodes = tmdbRepository.getSeasonEpisodes(tvId, season, imdbId)
                if (latestEpisodeSeasonRequest == season) {
                    _episodes.value = seasonEpisodes
                    _episodeError.value = null
                    _loadedSeason.value = season

                    val resume = _resumeInfo.value
                    val targetEp = if (resume != null && resume.season == season) {
                        seasonEpisodes.firstOrNull { it.episodeNumber == resume.episode }
                    } else {
                        seasonEpisodes.firstOrNull { ep ->
                            val eNum = ep.episodeNumber
                            val isWatched = computeEpisodeWatched(
                                parentId = imdbId,
                                season = season,
                                episode = eNum,
                                episodeStreamId = ep.streamId,
                                completedIds = _completedEpisodeIds.value,
                                watchedKeys = _watchedEpisodeKeys.value
                            )
                            !isWatched
                        } ?: seasonEpisodes.firstOrNull()
                    }

                    _targetEpisode.value = targetEp

                    if (resume != null && resume.season == season && resume.positionMs > 0L) {
                        _playButtonText.value = "Resume S${resume.season}E${resume.episode}"
                    } else if (targetEp != null) {
                        val e = targetEp.episodeNumber
                        _playButtonText.value = "Play S${season}E${e}"
                    } else {
                        _playButtonText.value = "Play"
                    }

                } else {
                    Log.e("KBStream", "episodes ignored stale result for season=$season imdbId=$imdbId")
                }
            } catch (e: Exception) {
                Log.e("KBStream", "episodes load failed for season=$season imdbId=$imdbId", e)
                if (latestEpisodeSeasonRequest == season) {
                    _episodes.value = emptyList()
                    _episodeError.value = e.message ?: "Couldn't load episodes"
                }
            } finally {
                if (latestEpisodeSeasonRequest == season) {
                    _episodesLoading.value = false
                }
            }
        }
    }

    fun loadPerson(personId: Int) {
        _selectedPersonLoading.value = false
        _selectedPersonError.value = null
        _selectedPersonDetail.value = null
    }

    /**
     * Long-press "Mark as Watched" on a More Like This / collection rail
     * poster: resolves the TMDB id to an IMDB id, records the persistent
     * local watched override (mirrored to SIMKL when connected) and updates
     * the reactive poster keys so the checkmark appears immediately.
     */
    fun markPosterWatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = mediaType.lowercase().takeIf {
                it == "movie" || it == "series" || it == "tv"
            } ?: return@launch

            val lookup = posterLookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedPosterIds.value[lookup]
                ?: runCatching {
                    resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedPosterIds.value[lookup] == null) {
                _resolvedPosterIds.value = _resolvedPosterIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("KBStream", "markPosterWatched failed tmdb=$tmdbId type=$normalizedType", e)
            }

            _watchedKeys.value = _watchedKeys.value + watchedKey(imdbId, normalizedType)
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a More Like This / collection rail
     * poster: resolves the TMDB id to an IMDB id, removes the persistent
     * local watched override (mirrored as a Simkl history delete when
     * connected) and drops the poster key so the checkmark clears.
     */
    fun markPosterUnwatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = mediaType.lowercase().takeIf {
                it == "movie" || it == "series" || it == "tv"
            } ?: return@launch

            val lookup = posterLookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedPosterIds.value[lookup]
                ?: runCatching {
                    resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedPosterIds.value[lookup] == null) {
                _resolvedPosterIds.value = _resolvedPosterIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("KBStream", "markPosterUnwatched failed tmdb=$tmdbId type=$normalizedType", e)
            }

            _watchedKeys.value = _watchedKeys.value - watchedKey(imdbId, normalizedType)
        }
    }

    /**
     * Long-press "Mark as Watched" on a season chip: writes one local
     * completed history row per episode (keyed by the derived
     * "parent:season:episode" key so the watched keys / episode badges
     * update immediately and survive restarts; positionMs=0 keeps them out
     * of Continue Watching / Recent) and mirrors the season to SIMKL
     * episode-by-episode when connected.
     */
    fun markSeasonWatched(
        season: Int,
        episodeNumbers: List<Int>
    ) {
        val parentId = imdbId
        if (parentId.isBlank() || season < 0) return

        val validEpisodes =
            episodeNumbers.filter { it > 0 }.distinct()
        if (validEpisodes.isEmpty()) return

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            val posterUrl =
                _meta.value?.poster?.takeIf {
                    it.isNotBlank()
                } ?: _tmdbDetail.value?.posterPath
                ?.let {
                    TmdbRepository.POSTER_BASE + it
                }
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            val now =
                System.currentTimeMillis()

            // 1. Local: one completed row per episode so the next load()
            // re-derives the watched keys from Room.
            validEpisodes.forEach { episode ->
                val key =
                    WatchedEpisodeState.buildEpisodeKey(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )

                if (key == null) {
                    return@forEach
                }

                runCatching {
                    historyDao.upsert(
                        WatchHistoryEntity(
                            id = key,
                            parentId = parentId,
                            type = "series",
                            name = showName,
                            poster = posterUrl,
                            streamUrl = null,
                            positionMs = 0L,
                            durationMs = 1L,
                            season = season,
                            episode = episode,
                            updatedAt = now,
                            isCompleted = true,
                            completedAt = now
                        )
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonWatched row failed s=$season e=$episode",
                        e
                    )
                }
            }

            // 2. Optimistic in-memory state so badges light up instantly.
            val newKeys =
                _watchedEpisodeKeys.value +
                    validEpisodes.mapNotNull { episode ->
                        WatchedEpisodeState.buildEpisodeKey(
                            parentId = parentId,
                            season = season,
                            episode = episode
                        )
                    }
            _watchedEpisodeKeys.value = newKeys

            val newSimklPairs =
                _simklWatchedEpisodes.value +
                    validEpisodes.map { episode ->
                        season to episode
                    }
            _simklWatchedEpisodes.value = newSimklPairs
            _simklSeriesWatched.value =
                newSimklPairs.isNotEmpty()

            // 3. Mirror to SIMKL when connected.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.pushWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = validEpisodes,
                        title = showName.takeIf {
                            it.isNotBlank()
                        },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonWatched simkl failed s=$season",
                        e
                    )
                }
            }
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a season chip: deletes every local
     * completed row for that season, drops the derived watched keys from
     * memory so the badges clear instantly, and mirrors the removal to
     * SIMKL episode-by-episode when connected.
     */
    fun markSeasonUnwatched(
        season: Int,
        episodeNumbers: List<Int>
    ) {
        val parentId = imdbId
        if (parentId.isBlank() || season < 0) return

        val validEpisodes =
            episodeNumbers.filter { it > 0 }.distinct()
        if (validEpisodes.isEmpty()) return

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            // 1. Local: drop every completed row for this season.
            runCatching {
                historyDao.deleteCompletedForParentSeason(
                    parentId = parentId,
                    season = season
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeasonUnwatched delete failed s=$season",
                    e
                )
            }

            // 2. Optimistic in-memory state so badges clear instantly.
            val removeKeys =
                validEpisodes.mapNotNull { episode ->
                    WatchedEpisodeState.buildEpisodeKey(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )
                }.toSet()
            _watchedEpisodeKeys.value =
                _watchedEpisodeKeys.value - removeKeys

            val removePairs =
                validEpisodes.map { episode ->
                    season to episode
                }.toSet()
            _simklWatchedEpisodes.value =
                _simklWatchedEpisodes.value - removePairs
            _simklSeriesWatched.value =
                _simklWatchedEpisodes.value.isNotEmpty()

            // 3. Mirror to SIMKL when connected.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.removeWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = validEpisodes,
                        title = showName.takeIf {
                            it.isNotBlank()
                        },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonUnwatched simkl failed s=$season",
                        e
                    )
                }
            }
        }
    }

    /**
     * Long-press "Mark as Watched" on an episode card: marks exactly one
     * episode locally and on SIMKL (reuses the season machinery with a
     * single-episode list).
     */
    fun markEpisodeWatched(
        season: Int,
        episode: Int
    ) {
        markSeasonWatched(
            season = season,
            episodeNumbers = listOf(episode)
        )
    }

    /**
     * Long-press "Mark as Unwatched" on an episode card: clears exactly one
     * episode locally and on SIMKL, leaving the rest of the season alone.
     */
    fun markEpisodeUnwatched(
        season: Int,
        episode: Int
    ) {
        markSpecificEpisodesUnwatched(
            season = season,
            episodeNumbers = listOf(episode)
        )
    }

    /**
     * Long-press "Mark Previous as Watched" on an episode card: marks every
     * episode before the pressed one in the same season (1..episode-1).
     */
    fun markPreviousWatched(
        season: Int,
        episode: Int
    ) {
        if (episode <= 1) return
        markSeasonWatched(
            season = season,
            episodeNumbers = (1 until episode).toList()
        )
    }

    /**
     * Long-press "Mark Previous as Unwatched" on an episode card: clears
     * every episode before the pressed one in the same season.
     */
    fun markPreviousUnwatched(
        season: Int,
        episode: Int
    ) {
        if (episode <= 1) return
        markSpecificEpisodesUnwatched(
            season = season,
            episodeNumbers = (1 until episode).toList()
        )
    }

    /**
     * Shared core for un-marking an arbitrary set of episodes: deletes the
     * matching local completed rows, drops the derived watched keys / SIMKL
     * pairs from memory so the badges clear instantly, and mirrors the
     * removal to SIMKL when connected.
     */
    private fun markSpecificEpisodesUnwatched(
        season: Int,
        episodeNumbers: List<Int>
    ) {
        val parentId = imdbId
        if (parentId.isBlank() || season < 0) return

        val validEpisodes =
            episodeNumbers.filter { it > 0 }.distinct()
        if (validEpisodes.isEmpty()) return

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            // 1. Local: drop the completed row(s) for each targeted episode.
            validEpisodes.forEach { episode ->
                runCatching {
                    historyDao.deleteCompletedForParentSeasonEpisode(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSpecificEpisodesUnwatched delete failed s=$season e=$episode",
                        e
                    )
                }
            }

            // 2. Optimistic in-memory state so badges clear instantly.
            val removeKeys =
                validEpisodes.mapNotNull { episode ->
                    WatchedEpisodeState.buildEpisodeKey(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )
                }.toSet()
            _watchedEpisodeKeys.value =
                _watchedEpisodeKeys.value - removeKeys

            val removePairs =
                validEpisodes.map { episode ->
                    season to episode
                }.toSet()
            _simklWatchedEpisodes.value =
                _simklWatchedEpisodes.value - removePairs
            _simklSeriesWatched.value =
                _simklWatchedEpisodes.value.isNotEmpty()

            // 3. Mirror to SIMKL when connected.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.removeWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = validEpisodes,
                        title = showName.takeIf {
                            it.isNotBlank()
                        },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSpecificEpisodesUnwatched simkl failed s=$season",
                        e
                    )
                }
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type.lowercase())
}
