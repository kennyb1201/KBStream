package com.kennyb1201.kbstream.ui.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
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
    private val addonManager = AddonManager(application)
    private val tmdbRepository = TmdbRepository(application)
    private val simklRepository = SimklRepository(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()
    private val watchHistoryRepository = WatchHistoryRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _meta = MutableStateFlow<Meta?>(null)
    val meta: StateFlow<Meta?> = _meta.asStateFlow()

    private val _tmdbDetail = MutableStateFlow<TmdbDetail?>(null)
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
                    Log.e("KBStream", "poster watched refresh: no items")
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
                    Log.e("KBStream", "poster watched refresh: no resolvable imdb ids")
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

                Log.e(
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

    fun load(type: String, id: String, initialSeason: Int? = null) {
        imdbId = id
        _meta.value = null
        _tmdbDetail.value = null
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
                val normalizedType = type.lowercase()
                Log.e("KBStream", "detail load start type=$normalizedType id=$id initialSeason=$initialSeason")

                val addonsDeferred = async { addonManager.getInstalledAddons() }
                val tmdbDeferred = async { runCatching { tmdbRepository.fetchEnrichedMeta(id, normalizedType) } }
                val resumeDeferred = async { runCatching { historyDao.getResumeForParent(id) } }
                val completedDeferred = async { runCatching { historyDao.getCompletedForParent(id) } }

                // 1. Await structural and history components first so watched data is guaranteed ready
                val tmdbDetailResult = tmdbDeferred.await()
                _resumeInfo.value = resumeDeferred.await().getOrNull()

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
                addons.firstOrNull { it.resources.contains("meta") }?.let { metaAddon ->
                    runCatching {
                        val baseUrl = metaAddon.manifestUrl.removeSuffix("/manifest.json")
                        repository.getMeta(baseUrl, normalizedType, id)
                    }.onSuccess { meta ->
                        _meta.value = meta
                    }
                }

                tmdbDetailResult.onSuccess { detail ->
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
                        detail = tmdbDetailResult.getOrNull(),
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

    fun loadEpisodesForSeason(season: Int) {
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

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type.lowercase())
}
