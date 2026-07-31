package com.kennyb1201.kbstream.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val tmdbRepository = TmdbRepository()
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()

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

    private val _collection = MutableStateFlow<TmdbCollectionDetail?>(null)
    val collection: StateFlow<TmdbCollectionDetail?> = _collection.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var imdbId: String = ""

    fun load(type: String, id: String) {
        imdbId = id
        _episodes.value = emptyList()
        _collection.value = null
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val addons = addonManager.getInstalledAddons()
                val metaAddon = addons.firstOrNull { it.resources.contains("meta") }
                metaAddon?.let {
                    val baseUrl = it.manifestUrl.removeSuffix("/manifest.json")
                    _meta.value = repository.getMeta(baseUrl, type, id)
                }
                try {
                    val detail = tmdbRepository.fetchEnrichedMeta(id, type)
                    _tmdbDetail.value = detail
                    detail?.belongsToCollection?.let { ref ->
                        _collection.value = tmdbRepository.getCollection(ref.id)
                    }
                } catch (e: Exception) {
                    // TMDB enrichment is optional -- never fail the screen over it
                }
                _resumeInfo.value = historyDao.getById(id)
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadEpisodesForSeason(season: Int) {
        val tvId = _tmdbDetail.value?.id
        if (tvId == null) {
            _episodes.value = emptyList()
            return
        }
        viewModelScope.launch {
            _episodesLoading.value = true
            _episodes.value = tmdbRepository.getSeasonEpisodes(tvId, season, imdbId)
            _episodesLoading.value = false
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
