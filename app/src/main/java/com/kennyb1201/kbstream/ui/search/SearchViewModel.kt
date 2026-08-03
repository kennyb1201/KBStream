package com.kennyb1201.kbstream.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import com.kennyb1201.kbstream.data.watched.preloadWatchedKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _results = MutableStateFlow<List<MetaPreview>>(emptyList())
    val results: StateFlow<List<MetaPreview>> = _results.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun search(query: String) {
        if (query.isBlank()) {
            _results.value = emptyList()
            _watchedKeys.value = emptySet()
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
                            // this addon/catalog doesn't support search -- skip it
                        }
                    }
                }

                val distinctResults = found.distinctBy { it.id }
                _results.value = distinctResults

                _watchedKeys.value = preloadWatchedKeys(
                    watchedStatusRepository = watchedStatusRepository,
                    items = distinctResults
                )

                Log.e(
                    "SEARCH_WATCHED",
                    "search done, results=${distinctResults.size}, watched=${_watchedKeys.value.size}"
                )
            } catch (e: Exception) {
                Log.e("SEARCH_WATCHED", "search failed: ${e.message}", e)
                _results.value = emptyList()
                _watchedKeys.value = emptySet()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
