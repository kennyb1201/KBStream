package com.kennyb1201.kbstream.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.domain.streamengine.StreamRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val tmdbRepository = TmdbRepository()

    private val _meta = MutableStateFlow<Meta?>(null)
    val meta: StateFlow<Meta?> = _meta.asStateFlow()

    private val _tmdbDetail = MutableStateFlow<TmdbDetail?>(null)
    val tmdbDetail: StateFlow<TmdbDetail?> = _tmdbDetail.asStateFlow()

    private val _streams = MutableStateFlow<List<Stream>>(emptyList())
    val streams: StateFlow<List<Stream>> = _streams.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _streamsLoading = MutableStateFlow(false)
    val streamsLoading: StateFlow<Boolean> = _streamsLoading.asStateFlow()

    private val _streamsRequested = MutableStateFlow(false)
    val streamsRequested: StateFlow<Boolean> = _streamsRequested.asStateFlow()

    private val _episodeRuntimes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val episodeRuntimes: StateFlow<Map<Int, Int>> = _episodeRuntimes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var contentType: String = ""

    fun load(type: String, id: String) {
        contentType = type
        _streamsRequested.value = false
        _streams.value = emptyList()
        _episodeRuntimes.value = emptyMap()
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
                    _tmdbDetail.value = tmdbRepository.fetchEnrichedMeta(id, type)
                } catch (e: Exception) {
                    // TMDB enrichment is optional -- never fail the screen over it
                }
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadEpisodeRuntimes(season: Int) {
        val tvId = _tmdbDetail.value?.id ?: return
        viewModelScope.launch {
            _episodeRuntimes.value = tmdbRepository.getSeasonRuntimes(tvId, season)
        }
    }

    fun loadStreamsFor(videoId: String) {
        _streamsRequested.value = true
        viewModelScope.launch {
            _streamsLoading.value = true
            val allStreams = mutableListOf<Stream>()
            val addons = addonManager.getInstalledAddons()
            for (addon in addons.filter { it.resources.contains("stream") }) {
                try {
                    val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                    allStreams += repository.getStreams(baseUrl, contentType, videoId)
                } catch (e: Exception) {
                    // one broken stream addon shouldn't block the others
                }
            }
            _streams.value = StreamRanker.rank(allStreams)
            _streamsLoading.value = false
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
