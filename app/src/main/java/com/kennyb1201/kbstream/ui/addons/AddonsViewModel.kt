package com.kennyb1201.kbstream.ui.addons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddonsViewModel(application: Application) : AndroidViewModel(application) {
    private val addonManager = AddonManager(application)
    private val repository = AddonRepository()

    private val _addons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _addons.value = addonManager.getInstalledAddons()
    }

    fun addAddon(manifestUrl: String) {
        val cleanUrl = manifestUrl.trim()
        if (cleanUrl.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val manifest = repository.fetchManifest(cleanUrl)
                val installed = InstalledAddon(
                    manifestUrl = cleanUrl,
                    id = manifest.id,
                    name = manifest.name,
                    resources = manifest.resources,
                    catalogs = manifest.catalogs
                )
                val current = addonManager.getInstalledAddons().filterNot { it.id == installed.id }
                addonManager.saveInstalledAddons(current + installed)
                refresh()
            } catch (e: Exception) {
                _error.value = "Failed to add addon: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeAddon(id: String) {
        addonManager.removeAddon(id)
        refresh()
    }
}
