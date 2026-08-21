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

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _addons.value = addonManager.getInstalledAddons()
    }

    fun clearError() {
        _error.value = null
    }

    fun clearStatus() {
        _status.value = null
    }

    fun addAddon(manifestUrl: String) {
        val cleanUrl = manifestUrl.trim()
        if (cleanUrl.isEmpty()) {
            _error.value = "Enter a manifest URL."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _status.value = null

            try {
                val manifest = repository.fetchManifest(cleanUrl)
                val current = addonManager.getInstalledAddons()
                val existing = current.firstOrNull { it.id == manifest.id }

                val installed = InstalledAddon(
                    manifestUrl = cleanUrl,
                    id = manifest.id,
                    name = manifest.name,
                    resources = manifest.resources,
                    catalogs = manifest.catalogs,
                    customName = existing?.customName,
                    version = manifest.version,
                    description = manifest.description,
                    types = manifest.types
                )

                val updated = if (existing == null) {
                    current + installed
                } else {
                    current.map { if (it.id == manifest.id) installed else it }
                }

                addonManager.saveInstalledAddons(updated)
                refresh()
                _status.value = if (existing == null) {
                    "Added ${installed.displayName}"
                } else {
                    "Updated ${installed.displayName}"
                }
            } catch (e: Exception) {
                _error.value = "Failed to add add-on: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeAddon(id: String) {
        addonManager.removeAddon(id)
        refresh()
        _status.value = "Add-on removed"
    }

    fun renameAddon(id: String, newName: String) {
        addonManager.renameAddon(id, newName)
        refresh()
        _status.value = "Name saved"
    }

    fun resetAddonName(id: String) {
        addonManager.renameAddon(id, null)
        refresh()
        _status.value = "Manifest name restored"
    }

    fun moveAddonUp(id: String) {
        addonManager.moveAddon(id, -1)
        refresh()
    }

    fun moveAddonDown(id: String) {
        addonManager.moveAddon(id, 1)
        refresh()
    }

    fun refreshAllManifests() {
        if (_refreshing.value) return

        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            _status.value = null

            try {
                val current = addonManager.getInstalledAddons()
                var successCount = 0
                var failureCount = 0

                val refreshed = current.map { old ->
                    try {
                        val manifest = repository.fetchManifest(old.manifestUrl)
                        successCount++

                        old.copy(
                            name = manifest.name,
                            resources = manifest.resources,
                            catalogs = manifest.catalogs,
                            version = manifest.version,
                            description = manifest.description,
                            types = manifest.types
                        )
                    } catch (_: Exception) {
                        failureCount++
                        old
                    }
                }

                addonManager.saveInstalledAddons(refreshed)
                refresh()

                _status.value = when {
                    failureCount == 0 ->
                        "Refreshed $successCount add-on${if (successCount == 1) "" else "s"}"
                    successCount == 0 ->
                        "Could not refresh any add-ons"
                    else ->
                        "Refreshed $successCount; $failureCount failed"
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun refreshManifest(id: String) {
        if (_refreshing.value) return

        val addon = addonManager.getInstalledAddons().firstOrNull { it.id == id }
            ?: return

        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            _status.value = null

            try {
                val manifest = repository.fetchManifest(addon.manifestUrl)
                addonManager.saveInstalledAddons(
                    addonManager.getInstalledAddons().map { old ->
                        if (old.id == id) {
                            old.copy(
                                name = manifest.name,
                                resources = manifest.resources,
                                catalogs = manifest.catalogs,
                                version = manifest.version,
                                description = manifest.description,
                                types = manifest.types
                            )
                        } else {
                            old
                        }
                    }
                )
                refresh()
                _status.value = "Refreshed ${addon.displayName}"
            } catch (e: Exception) {
                _error.value = "Refresh failed: ${e.message ?: "Unknown error"}"
            } finally {
                _refreshing.value = false
            }
        }
    }
}
