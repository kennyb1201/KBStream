package com.kennyb1201.kbstream.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.watched.WatchStateBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _results = MutableStateFlow<List<MetaPreview>>(emptyList())
    val results: StateFlow<List<MetaPreview>> = _results.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // Hot reactive subscription to live watch state changes from WatchStateBus
        WatchStateBus.updates
            .onEach { (key, isWatched) ->
                val current = _watchedKeys.value.toMutableSet()
                if (isWatched) {
                    current.add(key)
                } else {
                    current.remove(key)
                }
                _watchedKeys.value = current
            }
            .launchIn(viewModelScope)
    }

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun onQueryChanged(query: String) {
        _searchQuery.value = query
        search(query)
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _results.value = emptyList()
            _isLoading.value = false
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val found = mutableListOf<MetaPreview>()
                val addons = addonManager.getInstalledAddons()

                for (addon in addons) {
                    if (!addon.resources.contains("catalog")) continue

                    val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                    for (catalog in addon.catalogs) {
                        try {
                            found += repository.searchCatalog(baseUrl, catalog.type, catalog.id, query)
                        } catch (_: Exception) {
                            // skip unsupported catalogs
                        }
                    }
                }

                _results.value = found.distinctBy { it.id }

                // Push to hot recent searches cache
                if (query.isNotBlank() && !_recentSearches.value.contains(query)) {
                    _recentSearches.value = listOf(query) + _recentSearches.value.take(9)
                }
            } catch (e: Exception) {
                Log.e("KBStream", "Search failed", e)
                _results.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
    }
}
