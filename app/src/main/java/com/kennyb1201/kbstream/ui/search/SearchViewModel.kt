package com.kennyb1201.kbstream.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)

    private val _results = MutableStateFlow<List<MetaPreview>>(emptyList())
    val results: StateFlow<List<MetaPreview>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val found = mutableListOf<MetaPreview>()
            val addons = addonManager.getInstalledAddons()
            for (addon in addons) {
                if (!addon.resources.contains("catalog")) continue
                val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                for (catalog in addon.catalogs) {
                    try {
                        found += repository.searchCatalog(baseUrl, catalog.type, catalog.id, query)
                    } catch (e: Exception) {
                        // this addon/catalog doesn't support search -- skip it
                    }
                }
            }
            _results.value = found.distinctBy { it.id }
            _isLoading.value = false
        }
    }
}
