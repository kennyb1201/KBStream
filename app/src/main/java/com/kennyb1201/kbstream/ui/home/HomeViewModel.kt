package com.kennyb1201.kbstream.ui.home

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

data class Rail(
    val addonName: String,
    val catalogName: String,
    val type: String,
    val items: List<MetaPreview>
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)

    private val _rails = MutableStateFlow<List<Rail>>(emptyList())
    val rails: StateFlow<List<Rail>> = _rails.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadRails()
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
                        } catch (e: Exception) {
                            // one broken catalog shouldn't take down the whole home screen
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
