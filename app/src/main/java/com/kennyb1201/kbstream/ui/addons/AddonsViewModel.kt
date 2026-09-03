package com.kennyb1201.kbstream.ui.addons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonManifest
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.ManifestCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddonsViewModel(application: Application) : AndroidViewModel(application) {

    // Use the shared singleton so catalog changes are seen instantly by
    // HomeViewModel's watcher (a fresh AddonManager would hold a stale
    // StateFlow and Home would never pick up reorder/show-hide changes).
    private val addonManager = AddonManager.getInstance(application)
    private val repository = AddonRepository()

    private val _addons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    /**
     * Every catalog across every addon in one global order — the exact
     * sequence the catalog manager edits and Home renders.
     */
    private val _catalogConfigurations =
        MutableStateFlow<List<CatalogConfiguration>>(emptyList())

    val catalogConfigurations: StateFlow<List<CatalogConfiguration>> =
        _catalogConfigurations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Health of every installed add-on (manifest reachable + usable). */
    data class AddonHealth(
        val healthy: Boolean,
        val checkedAt: Long
    )

    private val _health = MutableStateFlow<Map<String, AddonHealth>>(emptyMap())
    val health: StateFlow<Map<String, AddonHealth>> = _health.asStateFlow()

    private val _checkingHealth = MutableStateFlow(false)
    val checkingHealth: StateFlow<Boolean> = _checkingHealth.asStateFlow()

    init {
        refresh()
        checkHealth()
    }

    /**
     * Pings every installed add-on's manifest URL and records whether it
     * responded with a usable Stremio manifest. Runs sequentially with the
     * same timeouts as a normal manifest fetch, so broken/offline add-ons
     * show a badge in the list instead of silently returning empty rails.
     */
    fun checkHealth() {
        if (_checkingHealth.value || _refreshing.value) return

        val addons = _addons.value
        if (addons.isEmpty()) return

        viewModelScope.launch {
            _checkingHealth.value = true
            val results = mutableMapOf<String, AddonHealth>()
            val now = System.currentTimeMillis()

            addons.forEach { addon ->
                val healthy = runCatching {
                    val manifest = repository.fetchManifest(addon.manifestUrl)
                    manifest.resources.isNotEmpty() || manifest.catalogs.isNotEmpty()
                }.getOrDefault(false)
                results[addon.id] = AddonHealth(healthy = healthy, checkedAt = now)
            }

            _health.value = results
            _checkingHealth.value = false
        }
    }

    fun refresh() {
        _addons.value = addonManager.getInstalledAddons()
        _catalogConfigurations.value =
            addonManager.getCatalogConfigurations()
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

                // Reject pages that aren't really Stremio add-ons (e.g. a
                // website's PWA manifest) instead of storing an empty add-on.
                if (!manifest.isUsableAddonManifest()) {
                    _error.value =
                        "That URL doesn't look like a Stremio add-on manifest " +
                            "(no resources or catalogs found)."
                    return@launch
                }

                val current = addonManager.getInstalledAddons()
                val existing = current.firstOrNull { it.id == manifest.id }

                val catalogs = mergeCatalogSettings(
                    oldCatalogs = existing?.catalogs.orEmpty(),
                    newCatalogs = manifest.catalogs
                )

                val installed = InstalledAddon(
                    manifestUrl = cleanUrl,
                    id = manifest.id,
                    name = manifest.name,
                    resources = manifest.resources,
                    catalogs = catalogs,
                    customName = existing?.customName,
                    version = manifest.version,
                    description = manifest.description,
                    types = manifest.types,
                    logo = manifest.logo ?: manifest.icon
                )

                val updated = if (existing == null) {
                    current + installed
                } else {
                    current.map {
                        if (it.id == manifest.id) {
                            installed
                        } else {
                            it
                        }
                    }
                }

                addonManager.saveInstalledAddons(updated)
                refresh()

                _status.value = if (existing == null) {
                    "Added ${installed.displayName}"
                } else {
                    "Updated ${installed.displayName}"
                }
                checkHealth()
            } catch (e: Exception) {
                _error.value =
                    "Failed to add add-on: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeAddon(id: String) {
        addonManager.removeAddon(id)
        refresh()
        _status.value = "Add-on removed"
        checkHealth()
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

    /**
     * Enable/disable a catalog on the KBStream home screen, keyed by
     * (type, id) — used by the catalog manager so same-named catalogs of
     * different types never collide.
     */
    fun setCatalogShowOnHome(
        addonId: String,
        catalogType: String,
        catalogId: String,
        showOnHome: Boolean
    ) {
        updateAddonCatalogs(addonId) { catalogs ->
            catalogs.map {
                if (it.type == catalogType && it.id == catalogId) {
                    it.copy(showOnHome = showOnHome)
                } else {
                    it
                }
            }
        }
        // The catalog manager dialog renders from _catalogConfigurations,
        // not _addons — without this refresh a toggle saves silently but the
        // row (and any re-open of the dialog) still shows the old state.
        refresh()
    }

    /**
     * Enable/disable a catalog on the KBStream home screen (id-only,
     * used by the add-on details pane).
     */
    fun setCatalogShowOnHome(
        addonId: String,
        catalogId: String,
        showOnHome: Boolean
    ) {
        updateAddonCatalogs(addonId) { catalogs ->
            catalogs.map {
                if (it.id == catalogId) {
                    it.copy(showOnHome = showOnHome)
                } else {
                    it
                }
            }
        }

        _status.value = "Catalog setting saved"
    }

    /**
     * Move a catalog up within its addon.
     */
    fun moveCatalogUp(
        addonId: String,
        catalogId: String
    ) {
        moveCatalog(addonId, catalogId, -1)
    }

    /**
     * Move a catalog down within its addon.
     */
    fun moveCatalogDown(
        addonId: String,
        catalogId: String
    ) {
        moveCatalog(addonId, catalogId, 1)
    }

    /**
     * Move a catalog to the top of its addon.
     */
    fun moveCatalogToTop(
        addonId: String,
        catalogId: String
    ) {
        val addon = _addons.value.firstOrNull { it.id == addonId }
            ?: return

        val sorted = addon.catalogs
            .sortedBy { it.order }
            .toMutableList()

        val index = sorted.indexOfFirst { it.id == catalogId }
        if (index <= 0) return

        val catalog = sorted.removeAt(index)
        sorted.add(0, catalog)

        saveCatalogs(
            addonId = addonId,
            catalogs = sorted.mapIndexed { newIndex, item ->
                item.copy(order = newIndex)
            }
        )
    }

    /**
     * Move a catalog to the bottom of its addon.
     */
    fun moveCatalogToBottom(
        addonId: String,
        catalogId: String
    ) {
        val addon = _addons.value.firstOrNull { it.id == addonId }
            ?: return

        val sorted = addon.catalogs
            .sortedBy { it.order }
            .toMutableList()

        val index = sorted.indexOfFirst { it.id == catalogId }
        if (index == -1 || index == sorted.lastIndex) return

        val catalog = sorted.removeAt(index)
        sorted.add(catalog)

        saveCatalogs(
            addonId = addonId,
            catalogs = sorted.mapIndexed { newIndex, item ->
                item.copy(order = newIndex)
            }
        )
    }

    /**
     * Reset the catalog order to the order supplied by the manifest.
     */
    fun resetCatalogOrder(addonId: String) {
        val addon = _addons.value.firstOrNull { it.id == addonId }
            ?: return

        val reordered = addon.catalogs.mapIndexed { index, catalog ->
            catalog.copy(
                order = index
            )
        }

        saveCatalogs(
            addonId = addonId,
            catalogs = reordered
        )

        _status.value = "Catalog order restored"
    }

    /**
     * Move a catalog one step in the global rail order (across addons).
     */
    fun moveCatalogGlobal(
        addonId: String,
        catalogType: String,
        catalogId: String,
        delta: Int
    ) {
        addonManager.moveCatalog(addonId, catalogType, catalogId, delta)
        refresh()
    }

    /**
     * Move a catalog to an absolute position in the global rail order.
     */
    fun moveCatalogGlobalToPosition(
        addonId: String,
        catalogType: String,
        catalogId: String,
        targetIndex: Int
    ) {
        addonManager.moveCatalogToPosition(
            addonId,
            catalogType,
            catalogId,
            targetIndex
        )
        refresh()
    }

    /**
     * Rename a catalog rail as shown on Home.
     */
    fun renameCatalog(
        addonId: String,
        catalogType: String,
        catalogId: String,
        name: String
    ) {
        addonManager.setCatalogCustomName(
            addonId,
            catalogType,
            catalogId,
            name
        )
        refresh()
        _status.value = "Catalog renamed"
    }

    /**
     * Drop a catalog's custom name and fall back to the manifest name.
     */
    fun clearCatalogName(
        addonId: String,
        catalogType: String,
        catalogId: String
    ) {
        addonManager.setCatalogCustomName(
            addonId,
            catalogType,
            catalogId,
            null
        )
        refresh()
        _status.value = "Manifest name restored"
    }

    private fun moveCatalog(
        addonId: String,
        catalogId: String,
        direction: Int
    ) {
        val addon = _addons.value.firstOrNull { it.id == addonId }
            ?: return

        val sorted = addon.catalogs
            .sortedBy { it.order }
            .toMutableList()

        val index = sorted.indexOfFirst { it.id == catalogId }

        if (index == -1) return

        val targetIndex = index + direction

        if (targetIndex !in sorted.indices) return

        val current = sorted[index]
        val target = sorted[targetIndex]

        sorted[index] = target
        sorted[targetIndex] = current

        val reordered = sorted.mapIndexed { newIndex, catalog ->
            catalog.copy(order = newIndex)
        }

        saveCatalogs(
            addonId = addonId,
            catalogs = reordered
        )
    }

    private fun updateAddonCatalogs(
        addonId: String,
        transform: (List<ManifestCatalog>) -> List<ManifestCatalog>
    ) {
        val addon = _addons.value.firstOrNull { it.id == addonId }
            ?: return

        val updatedCatalogs = transform(addon.catalogs)

        saveCatalogs(
            addonId = addonId,
            catalogs = updatedCatalogs
        )
    }

    private fun saveCatalogs(
        addonId: String,
        catalogs: List<ManifestCatalog>
    ) {
        val updated = _addons.value.map { addon ->
            if (addon.id == addonId) {
                addon.copy(
                    catalogs = catalogs
                        .sortedBy { it.order }
                        .mapIndexed { index, catalog ->
                            catalog.copy(order = index)
                        }
                )
            } else {
                addon
            }
        }

        addonManager.saveInstalledAddons(updated)
        _addons.value = updated
    }

    /**
     * Merge a newly downloaded manifest with the user's local
     * catalog configuration.
     *
     * Existing catalogs retain:
     * - showOnHome
     * - local order
     *
     * New catalogs default to visible and are placed after
     * the existing catalogs.
     */
    private fun mergeCatalogSettings(
        oldCatalogs: List<ManifestCatalog>,
        newCatalogs: List<ManifestCatalog>
    ): List<ManifestCatalog> {

        val oldByKey = oldCatalogs.associateBy {
            catalogKey(it.type, it.id)
        }

        val oldOrder = oldCatalogs
            .sortedBy { it.order }
            .map { catalogKey(it.type, it.id) }

        val newByKey = newCatalogs.associateBy {
            catalogKey(it.type, it.id)
        }

        val result = mutableListOf<ManifestCatalog>()

        // Preserve the user's existing order first.
        oldOrder.forEach { key ->
            val newCatalog = newByKey[key] ?: return@forEach
            val oldCatalog = oldByKey[key]

            result += newCatalog.copy(
                showOnHome = oldCatalog?.showOnHome ?: true
            )
        }

        // Append catalogs that are new in the refreshed manifest.
        // Some addons (e.g. AIOStreams) list the same catalog more than once
        // in their manifest — dedupe by (type, id) so Home never builds two
        // rails with the same key (duplicate LazyColumn keys crash the rail
        // list, which is why catalogs showed in the add-on screen but never
        // appeared on Home).
        val seen = mutableSetOf<String>()
        newCatalogs.forEach { catalog ->
            val key = catalogKey(catalog.type, catalog.id)

            if (oldByKey[key] == null && seen.add(key)) {
                result += catalog.copy(
                    showOnHome = true
                )
            }
        }

        return result.mapIndexed { index, catalog ->
            catalog.copy(order = index)
        }
    }

    private fun catalogKey(
        type: String,
        id: String
    ): String {
        return "${type.trim().lowercase()}::${id.trim().lowercase()}"
    }

    /**
     * A real Stremio add-on manifest declares at least one resource or
     * catalog. Pages that fail both (like a website's PWA manifest, which
     * has no Stremio fields) should never be stored as an add-on.
     */
    private fun AddonManifest.isUsableAddonManifest(): Boolean {
        return resources.isNotEmpty() || catalogs.isNotEmpty()
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

                var newCatalogTotal = 0

                val refreshed = current.map { old ->
                    try {
                        val manifest = repository.fetchManifest(old.manifestUrl)

                        // Guard: if the URL now serves something that isn't a
                        // usable add-on (e.g. the add-on moved and a website
                        // manifest is returned), keep the old stored config
                        // rather than wiping resources/catalogs.
                        if (!manifest.isUsableAddonManifest()) {
                            failureCount++
                            return@map old
                        }

                        successCount++

                        newCatalogTotal += manifest.catalogs.count { catalog ->
                            old.catalogs.none {
                                catalogKey(it.type, it.id) ==
                                    catalogKey(catalog.type, catalog.id)
                            }
                        }

                        old.copy(
                            name = manifest.name,
                            resources = manifest.resources,
                            catalogs = mergeCatalogSettings(
                                oldCatalogs = old.catalogs,
                                newCatalogs = manifest.catalogs
                            ),
                            version = manifest.version,
                            description = manifest.description,
                            types = manifest.types,
                            logo = manifest.logo ?: manifest.icon
                        )
                    } catch (_: Exception) {
                        failureCount++
                        old
                    }
                }

                addonManager.saveInstalledAddons(refreshed)
                refresh()

                _status.value = when {
                    failureCount == 0 -> {
                        val base =
                            "Refreshed $successCount add-on${if (successCount == 1) "" else "s"}"
                        if (newCatalogTotal > 0) {
                            "$base · +$newCatalogTotal new catalog${if (newCatalogTotal == 1) "" else "s"}"
                        } else {
                            base
                        }
                    }

                    successCount == 0 ->
                        "Could not refresh any add-ons"

                    else ->
                        "Refreshed $successCount; $failureCount failed"
                }
            } catch (e: Exception) {
                _error.value =
                    "Refresh failed: ${e.message ?: "Unknown error"}"
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun refreshManifest(id: String) {
        if (_refreshing.value) return

        val addon = addonManager
            .getInstalledAddons()
            .firstOrNull { it.id == id }
            ?: return

        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            _status.value = null

            try {
                val manifest = repository.fetchManifest(addon.manifestUrl)

                if (!manifest.isUsableAddonManifest()) {
                    _error.value =
                        "Manifest doesn't look like a Stremio add-on " +
                            "(no resources or catalogs) — kept the saved config."
                    return@launch
                }

                val newCatalogCount =
                    manifest.catalogs.count { catalog ->
                        addon.catalogs.none {
                            catalogKey(it.type, it.id) ==
                                catalogKey(catalog.type, catalog.id)
                        }
                    }

                addonManager.saveInstalledAddons(
                    addonManager.getInstalledAddons().map { old ->
                        if (old.id == id) {
                            old.copy(
                                name = manifest.name,
                                catalogs = mergeCatalogSettings(
                                    oldCatalogs = old.catalogs,
                                    newCatalogs = manifest.catalogs
                                ),
                                resources = manifest.resources,
                                version = manifest.version,
                                description = manifest.description,
                                types = manifest.types,
                                logo = manifest.logo ?: manifest.icon
                            )
                        } else {
                            old
                        }
                    }
                )

                refresh()

                _status.value = when {
                    newCatalogCount > 0 ->
                        "Refreshed ${addon.displayName} · " +
                            "$newCatalogCount new catalog${if (newCatalogCount == 1) "" else "s"}"

                    else ->
                        "Refreshed ${addon.displayName} · no catalog changes"
                }
            } catch (e: Exception) {
                _error.value =
                    "Refresh failed: ${e.message ?: "Unknown error"}"
            } finally {
                _refreshing.value = false
            }
        }
    }
}
