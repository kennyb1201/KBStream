package com.kennyb1201.kbstream.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Rail(
    val addonName: String,
    val catalogName: String,
    val type: String,
    val items: List<MetaPreview>
)

enum class UpNextBadge {
    CONTINUE_WATCHING,
    NEXT_UP,
    NEW_EPISODE,
    NEW_SEASON
}

data class UpNextItem(
    val id: String,
    val title: String,
    val poster: String?,
    val badge: UpNextBadge,
    val subtitle: String? = null,
    val progressPercent: Float? = null,
    val streamUrl: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeStreamId: String? = null,
    val startPositionMs: Long = 0L
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()

    private val _rails = MutableStateFlow<List<Rail>>(emptyList())
    val rails: StateFlow<List<Rail>> = _rails.asStateFlow()

    private val _upNext = MutableStateFlow<List<UpNextItem>>(emptyList())
    val upNext: StateFlow<List<UpNextItem>> = _upNext.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadRails()
        loadUpNext()
    }

    fun loadUpNext() {
        viewModelScope.launch {
            val history = historyDao.getRecent()

            _upNext.value = history.map { entry ->
                UpNextItem(
                    id = buildString {
                        append(entry.id)
                        entry.season?.let { append(":s$it") }
                        entry.episode?.let { append(":e$it") }
                    },
                    title = entry.name,
                    poster = entry.poster,
                    badge = UpNextBadge.CONTINUE_WATCHING,
                    subtitle = when {
                        entry.season != null && entry.episode != null -> "S${entry.season}:E${entry.episode}"
                        entry.positionMs > 0 -> "Resume"
                        else -> null
                    },
                    streamUrl = entry.streamUrl,
                    parentId = entry.id,
                    parentType = entry.type,
                    season = entry.season,
                    episode = entry.episode,
                    episodeStreamId = entry.episodeStreamId,
                    startPositionMs = entry.positionMs
                )
            }
        }
    }

    fun loadRails() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = mutableListOf<Rail>()
            try {
                val addons = addonManager.getInstalledAddons()
                for (addon in addons) {
                    if (!addon.resources.contains("catalog")) continue
                    val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                    for (catalog in addon.catalogs) {
                        try {
                            val metas = repository.getCatalog(baseUrl, catalog.type, catalog.id)
                            if (metas.isNotEmpty()) {
                                result.add(Rail(addon.name, catalog.name, catalog.type, metas))
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                _rails.value = result
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
