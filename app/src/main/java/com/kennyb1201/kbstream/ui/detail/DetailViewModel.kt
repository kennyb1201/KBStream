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
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val key = "$parentId:$season:$episode"
    return key in watchedKeys
}

class DetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val tmdbRepository = TmdbRepository()
    private val simklRepository = SimklRepository(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()
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

    private var imdbId: String = ""

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun posterLookupKey(tmdbId: Int, mediaType: String): String = "$mediaType::$tmdbId"

    private fun refreshPosterWatchedStatus(type: String) {
        viewModelScope.launch {
            try {
                val rawItems = buildList {
                    _collection.value?.parts.orEmpty().forEach { part ->
                        add(part.id to "movie")
                    }
                    _tmdbDetail.value?.recommendations?.results.orEmpty().forEach { rec ->
                        add(rec.id to type.lowercase())
                    }
                }.distinct()

                if (rawItems.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedPosterIds.value = emptyMap()
                    Log.e("KBStream", "poster watched refresh: no items")
                    return@launch
                }

                val resolvedItems = rawItems.mapNotNull { (tmdbId, mediaType) ->
                    val imdb = runCatching { resolveImdbId(tmdbId, mediaType) }.getOrNull()
                    if (imdb.isNullOrBlank()) null else Triple(tmdbId, mediaType, imdb)
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
                    .filter { (_, _, imdbId) -> watchedStatusRepository.isWatchedCached(imdbId) }
                    .map { (_, mediaType, imdbId) -> watchedKey(imdbId, mediaType) }
                    .toSet()

                Log.e(
                    "KBStream",
                    "poster watched refresh resolved=${resolvedItems.size} watched=${_watchedKeys.value.size}"
                )
                Log.e("KBStream", "poster resolved sample=${_resolvedPosterIds.value.entries.take(10)}")
                Log.e("KBStream", "poster watched sample=${_watchedKeys.value.take(10)}")
            } catch (e: Exception) {
                _watchedKeys.value = emptySet()
                _resolvedPosterIds.value = emptyMap()
                Log.e("KBStream", "poster watched refresh failed", e)
            }
        }
    }

    fun load(type: String, id: String) {
        imdbId = id
        _episodes.value = emptyList()
        _collection.value = null
        _resumeInfo.value = null
        _completedEpisodeIds.value = emptySet()
        _simklWatchedEpisodes.value = emptySet()
        _simklSeriesWatched.value = false
        _watchedEpisodeKeys.value = emptySet()
        _watchedKeys.value = emptySet()
        _resolvedPosterIds.value = emptyMap()

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                Log.e("KBStream", "detail load start type=$type id=$id")

                val addonsDeferred = async {
                    addonManager.getInstalledAddons()
                }

                val tmdbDeferred = async {
                    runCatching { tmdbRepository.fetchEnrichedMeta(id, type) }
                }

                val resumeDeferred = async {
                    runCatching { historyDao.getResumeForParent(id) }
                }

                val completedDeferred = async {
                    runCatching { historyDao.getCompletedForParent(id) }
                }

                val simklDeferred = async {
                    if (type == "series" && simklRepository.isConfigured() && simklRepository.hasToken()) {
                        val tmdbShowId = tmdbDeferred.await().getOrNull()?.id
                        runCatching {
                            simklRepository.getWatchedEpisodesForShowByImdb(
                                imdbId = id,
                                tmdbId = tmdbShowId
                            )
                        }
                    } else {
                        Result.success(emptySet())
                    }
                }

                val addons = addonsDeferred.await()
                val metaAddon = addons.firstOrNull { it.resources.contains("meta") }
                metaAddon?.let {
                    runCatching {
                        val baseUrl = it.manifestUrl.removeSuffix("/manifest.json")
                        repository.getMeta(baseUrl, type, id)
                    }.onSuccess { meta ->
                        _meta.value = meta
                        Log.e("KBStream", "meta load ok name=${meta?.name}")
                    }.onFailure { e ->
                        Log.e("KBStream", "meta load failed", e)
                    }
                }

                tmdbDeferred.await()
                    .onSuccess { detail ->
                        _tmdbDetail.value = detail
                        Log.e(
                            "KBStream",
                            "tmdb fetch ok id=${detail?.id} reviewCount=${detail?.reviews?.results?.size}"
                        )

                        val collectionId = detail?.belongsToCollection?.id
                        if (collectionId != null) {
                            runCatching {
                                tmdbRepository.getCollection(collectionId)
                            }.onSuccess { collection ->
                                _collection.value = collection
                                Log.e(
                                    "KBStream",
                                    "collection load ok id=${collection?.id} parts=${collection?.parts?.size}"
                                )
                                refreshPosterWatchedStatus(type)
                            }.onFailure { e ->
                                Log.e("KBStream", "collection load failed", e)
                                refreshPosterWatchedStatus(type)
                            }
                        } else {
                            refreshPosterWatchedStatus(type)
                        }
                    }
                    .onFailure { e ->
                        Log.e("KBStream", "tmdb enrichment failed", e)
                    }

                _resumeInfo.value = resumeDeferred.await().getOrNull()
                Log.e("KBStream", "local resume info = ${_resumeInfo.value}")

                val localCompleted = completedDeferred.await()
                    .getOrDefault(emptyList())
                    .map { it.id }
                    .toSet()
                _completedEpisodeIds.value = localCompleted
                Log.e("KBStream", "local completed ids = $localCompleted")

                val simklCompleted = simklDeferred.await()
                    .getOrDefault(emptySet())
                _simklWatchedEpisodes.value = simklCompleted
                Log.e("KBStream", "simkl completed episodes for $id = $simklCompleted")

                _simklSeriesWatched.value =
                    type == "series" && simklRepository.isConfigured() && simklRepository.hasToken() &&
                        simklRepository.isShowWatchedByImdb(id)

                Log.e("KBStream", "simkl series watched fallback for $id = ${_simklSeriesWatched.value}")

                val mergedKeys = buildMergedWatchedKeys(
                    localCompletedIds = localCompleted,
                    simklCompletedEpisodes = simklCompleted,
                    parentId = id
                )
                _watchedEpisodeKeys.value = mergedKeys
                Log.e("KBStream", "merged watched keys for $id = $mergedKeys")
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
                Log.e("KBStream", "detail load failed", e)
            } finally {
                _isLoading.value = false
                Log.e("KBStream", "detail load finished type=$type id=$id")
            }
        }
    }

    fun loadEpisodesForSeason(season: Int) {
        val tvId = _tmdbDetail.value?.id
        if (tvId == null) {
            _episodes.value = emptyList()
            Log.e("KBStream", "episodes skipped: tmdbDetail id is null for imdbId=$imdbId season=$season")
            return
        }

        viewModelScope.launch {
            _episodesLoading.value = true
            try {
                _episodes.value = tmdbRepository.getSeasonEpisodes(tvId, season, imdbId)

                                                if (_simklWatchedEpisodes.value.isEmpty() && _simklSeriesWatched.value) {
                    val fallbackKeys = _episodes.value.mapNotNull { ep ->
                        buildEpisodeKey(imdbId, season, ep.episodeNumber)
                    }.toSet()

                    _watchedEpisodeKeys.value = _watchedEpisodeKeys.value + fallbackKeys

                    Log.e(
                        "KBStream",
                        "applied series watched fallback for imdbId=$imdbId season=$season keys=${fallbackKeys.take(20)}"
                    )
                }

                Log.e("KBStream", "loaded episodes for season=$season imdbId=$imdbId")
                Log.e("KBStream", "episode stream ids = ${_episodes.value.map { it.streamId }}")
                Log.e("KBStream", "current watched keys = ${_watchedEpisodeKeys.value}")
            } catch (e: Exception) {
                Log.e("KBStream", "episodes load failed for season=$season imdbId=$imdbId", e)
                _episodes.value = emptyList()
            } finally {
                _episodesLoading.value = false
            }
        }
    }

    private fun buildMergedWatchedKeys(
        localCompletedIds: Set<String>,
        simklCompletedEpisodes: Set<Pair<Int, Int>>,
        parentId: String
    ): Set<String> {
        val merged = linkedSetOf<String>()

        merged += localCompletedIds

        simklCompletedEpisodes.forEach { (season, episode) ->
            buildEpisodeKey(parentId, season, episode)?.let { merged += it }
        }

        return merged
    }

    private fun buildEpisodeKey(
        parentId: String,
        season: Int?,
        episode: Int?
    ): String? {
        if (parentId.isBlank() || season == null || episode == null) return null
        return "$parentId:$season:$episode"
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
</query>
