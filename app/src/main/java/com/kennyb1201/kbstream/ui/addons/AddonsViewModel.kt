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
import com.kennyb1201.kbstream.data.nuvio.NuvioCollectionProfile
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrder
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrderPrefs
import com.kennyb1201.kbstream.data.nuvio.NuvioProfilePrefs
import com.kennyb1201.kbstream.data.nuvio.NuvioRepository
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

    private val nuvioRepository = NuvioRepository(application)

    /**
     * addonId -> manifestUrl, so rail keys match Home exactly. Home builds
     * its rail keys from the manifest BASE URL (NuvioHomeOrderPrefs.addonKey
     * takes the baseUrl the rail was loaded from); keying by addon id here
     * wrote arrangement keys Home could never match, so reordered catalogs
     * fell back to their default slots on Home while the manager claimed
     * otherwise.
     */
    private val manifestUrlByAddonId: Map<String, String>
        get() = _addons.value.associate { it.id to it.manifestUrl }

    /**
     * Imported Nuvio collections: import URL list plus each loaded
     * collection (title, folder count) so the home manager can arrange
     * them among addon catalog rails and import/remove profile URLs.
     */
    data class ManagedCollection(
        val key: String,
        val title: String,
        val folderCount: Int,
        val isPinned: Boolean,
        val isHidden: Boolean
    )

    data class CollectionUiState(
        val profileUrls: List<String> = emptyList(),
        val collections: List<ManagedCollection> = emptyList(),
        val statusMessage: String? = null
    )

    private val _collections =
        MutableStateFlow(CollectionUiState())
    val collections: StateFlow<CollectionUiState> =
        _collections.asStateFlow()

    /**
     * Bumped on every home-arrangement write. Catalog arrangement moves
     * often leave addon/catalog state EQUAL (StateFlow dedupes, no
     * recomposition) while only the prefs order changed — the manager
     * dialog keys its row layout on this counter so every move re-renders
     * instantly instead of looking dead.
     */
    private val _homeOrderVersion = MutableStateFlow(0)
    val homeOrderVersion: StateFlow<Int> =
        _homeOrderVersion.asStateFlow()

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
        reloadCollections()
    }

    /**
     * Reload the imported-collections slice of the home manager: profile
     * URLs, loaded collections (from cache), and each one's pin/hidden
     * state from the shared home order prefs.
     */
    private fun reloadCollections() {
        val context = getApplication<Application>()
        val prefs = NuvioHomeOrderPrefs.get(context)
        // loadProfiles is suspend (it can hit the network for uncached
        // profile URLs), so the collection slice resolves in a coroutine;
        // the prefs snapshot is synchronous and cheap.
        viewModelScope.launch {
            val collections = runCatching { nuvioRepository.loadProfiles() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.pinToTop }
            // Fresh imports have never been arranged anywhere: surface them as
            // hidden in the manager (matching Home's default-off rendering).
            val arranged = prefs.pinned.toSet() + prefs.order.toSet() + prefs.hiddenSet
            _collections.value = CollectionUiState(
                profileUrls = NuvioProfilePrefs.getProfileUrls(context),
                collections = collections.map { collection ->
                    val key = NuvioHomeOrderPrefs.collectionKey(
                        collection.id,
                        collection.title
                    )
                    ManagedCollection(
                        key = key,
                        title = collection.title.ifBlank { "Untitled collection" },
                        folderCount = collection.folders.size,
                        isPinned = key in prefs.pinned,
                        isHidden = key in prefs.hiddenSet || key !in arranged
                    )
                },
                statusMessage = _collections.value.statusMessage
            )
        }
    }

    // ------------------------------------------------------------------
    // Nuvio collections: import / remove / arrange (home manager)
    // ------------------------------------------------------------------

    fun addCollectionProfileUrl(url: String) {
        val context = getApplication<Application>()
        if (!NuvioRepository.isPlausibleUrl(url)) {
            _collections.value = _collections.value.copy(
                statusMessage = "Enter an https:// URL"
            )
            return
        }
        viewModelScope.launch {
            _collections.value = _collections.value.copy(
                statusMessage = "Importing…"
            )
            val ok = runCatching {
                NuvioProfilePrefs.addProfileUrl(context, url) &&
                    nuvioRepository.loadProfileForValidation(url).isNotEmpty()
            }.getOrElse { false }
            if (ok) {
                _collections.value = _collections.value.copy(
                    statusMessage = "Collections imported"
                )
                refresh()
            } else {
                NuvioProfilePrefs.removeProfileUrl(context, url)
                nuvioRepository.evict(url)
                _collections.value = _collections.value.copy(
                    statusMessage = "Import failed — not a Nuvio collections profile"
                )
            }
        }
    }

    /**
     * Import a local Nuvio profile JSON document (picked from device
     * storage). The parsed document is stored inside app storage behind a
     * "local:" pseudo-URL so the rest of the pipeline (Home rails, folder
     * screens) treats it exactly like a hosted profile.
     */
    fun importCollectionProfileJson(jsonText: String) {
        if (jsonText.isBlank()) {
            _collections.value = _collections.value.copy(
                statusMessage = "Pick a .json collections profile"
            )
            return
        }
        viewModelScope.launch {
            _collections.value = _collections.value.copy(
                statusMessage = "Importing file…"
            )
            val result = runCatching {
                val pseudoUrl = nuvioRepository.importLocalProfile(jsonText)
                val context = getApplication<Application>()
                if (NuvioProfilePrefs.addProfileUrl(context, pseudoUrl)) {
                    pseudoUrl
                } else {
                    nuvioRepository.evict(pseudoUrl)
                    null
                }
            }.getOrElse { null }

            _collections.value = _collections.value.copy(
                statusMessage = if (result != null) {
                    "Collections file imported"
                } else {
                    "Import failed — not a Nuvio collections profile"
                }
            )
            if (result != null) refresh()
        }
    }

    fun onCollectionImportFileError() {
        _collections.value = _collections.value.copy(
            statusMessage = "Couldn't read the selected file"
        )
    }

    fun removeCollectionProfileUrl(url: String) {
        val context = getApplication<Application>()
        NuvioProfilePrefs.removeProfileUrl(context, url)
        viewModelScope.launch { nuvioRepository.evict(url) }
        _collections.value = _collections.value.copy(
            statusMessage = "Collection source removed"
        )
        refresh()
    }

    fun toggleCollectionPinned(key: String) {
        persistHomeOrder { prefs ->
            if (prefs.pinned.contains(key)) {
                prefs.copy(pinned = prefs.pinned - key)
            } else {
                prefs.copy(
                    pinned = prefs.pinned + key,
                    order = prefs.order - key
                )
            }
        }
    }

    fun toggleCollectionHidden(key: String) {
        // Intent comes from the row's DISPLAYED state, not just the stored
        // hidden list: a freshly imported collection is hidden by default
        // WITHOUT a hidden entry ("never arranged"), so keying off
        // hiddenSet alone made the first SHOW click take the hide path and
        // the toggle visibly do nothing.
        val currentlyHidden = _collections.value.collections
            .firstOrNull { it.key == key }?.isHidden ?: true
        persistHomeOrder { prefs ->
            if (currentlyHidden) {
                // SHOW: clear the hidden flag AND arrange the rail. A
                // never-arranged collection only counts as arranged once it
                // sits in pinned/order — otherwise Home re-derives its
                // "hidden by default" state and it never appears.
                prefs.copy(
                    hidden = prefs.hidden - key,
                    order = if (key in prefs.order) {
                        prefs.order
                    } else {
                        prefs.order + key
                    }
                )
            } else {
                // HIDE: also lift it out of pinned/order so the hidden
                // state is the only thing remembered about it.
                prefs.copy(
                    hidden = prefs.hidden + key,
                    pinned = prefs.pinned - key,
                    order = prefs.order - key
                )
            }
        }
    }

    /**
     * Move a collection one slot among VISIBLE home rails (collections and
     * addon catalogs interleaved). Pinned keys stay pinned in their new
     * order. delta = Int.MIN_VALUE jumps to the very top, Int.MAX_VALUE to
     * the very bottom of the visible list.
     */
    fun moveCollection(key: String, delta: Int) {
        moveRailInArrangement(key, delta)
    }

    /**
     * Move one ADDON catalog rail inside the merged home arrangement — the
     * same visible list (collections + catalogs interleaved) the manager
     * dialog shows and Home renders. Writes NuvioHomeOrderPrefs, NOT the
     * addon-global catalog order: the global order only decides default
     * tail positions, so moving there never changed what the dialog (or
     * Home, once any arrangement exists) displayed.
     *
     * delta = -1/+1 steps one slot; Int.MIN_VALUE jumps to the very top,
     * Int.MAX_VALUE to the very bottom.
     */
    fun moveCatalogArrangement(config: CatalogConfiguration, delta: Int) {
        moveRailInArrangement(
            NuvioHomeOrderPrefs.addonKeyFromManifest(
                config.addonManifestUrl,
                config.catalog.type,
                config.catalog.id
            ),
            delta
        )
    }

    /**
     * Shared reorder core. Works on the merged VISIBLE rail keys (the exact
     * list the manager dialog shows and Home renders):
     *  - ±1 steps one slot; pinned keys stay pinned inside the pinned block.
     *  - Int.MIN_VALUE (VERY TOP) puts the key at the HEAD of the pinned
     *    list, so it renders as the absolute first rail — ABOVE everything,
     *    including previously pinned collections.
     *  - Int.MAX_VALUE (VERY BOTTOM) unpins the key and appends it after
     *    everything else.
     */
    private fun moveRailInArrangement(key: String, delta: Int) {
        persistHomeOrder { prefs ->
            when (delta) {
                Int.MIN_VALUE -> prefs.copy(
                    pinned = listOf(key) + prefs.pinned.filter { it != key },
                    order = prefs.order - key,
                    hidden = prefs.hidden - key
                )

                Int.MAX_VALUE -> prefs.copy(
                    pinned = prefs.pinned - key,
                    order = (prefs.order - key) + key,
                    hidden = prefs.hidden - key
                )

                else -> {
                    val visible =
                        mergedRailKeys(prefs).filter { it !in prefs.hiddenSet }
                    val from = visible.indexOf(key)
                    if (from == -1) return@persistHomeOrder prefs
                    val to = (from + delta).coerceIn(0, visible.lastIndex)
                    if (from == to) return@persistHomeOrder prefs

                    val ordered = visible.toMutableList()
                    val item = ordered.removeAt(from)
                    ordered.add(to, item)

                    // Re-split by pin membership: a moved PIN keeps its
                    // pinned status (re-ordered within the pinned block),
                    // and hidden/stale pins can never inflate the head.
                    val pinnedSet = prefs.pinned.toSet()
                    prefs.copy(
                        order = ordered.filter { it !in pinnedSet },
                        pinned = ordered.filter { it in pinnedSet }
                    )
                }
            }
        }
    }

    /**
     * Merged visible rail keys in display order: pinned (pin order) first,
     * then stored order, then defaults (import order for collections, global
     * catalog order for addons). Mirrors NuvioHomeSlots.buildMergedEntries.
     */
    private fun mergedRailKeys(prefs: NuvioHomeOrder): List<String> {
        val collectionKeys = _collections.value.collections.map { it.key }
        val urls = manifestUrlByAddonId
        val addonKeys = _catalogConfigurations.value
            .filter { it.catalog.showOnHome }
            .map {
                NuvioHomeOrderPrefs.addonKeyFromManifest(
                    urls[it.addonId],
                    it.catalog.type,
                    it.catalog.id
                )
            }
        val defaults = collectionKeys + addonKeys
        val known = defaults.toSet()

        val positioned = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (key in prefs.pinned) {
            if (key in known && seen.add(key)) positioned += key
        }
        for (key in prefs.order) {
            if (seen.add(key) && key in known) positioned += key
        }
        for (key in defaults) {
            if (seen.add(key)) positioned += key
        }
        return positioned
    }

    private fun persistHomeOrder(
        transform: (NuvioHomeOrder) -> NuvioHomeOrder
    ) {
        val context = getApplication<Application>()
        val updated = transform(NuvioHomeOrderPrefs.get(context))
        NuvioHomeOrderPrefs.save(context, updated)
        _homeOrderVersion.value += 1
        refresh()
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
                    idPrefixes = manifest.idPrefixes,
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
    /**
     * Bulk enable/disable every catalog from every addon on the home screen.
     * Used by the catalog manager's Show All / Hide All actions.
     */
    fun setAllCatalogsShowOnHome(showOnHome: Boolean) {
        _addons.value.forEach { addon ->
            updateAddonCatalogs(addon.id) { catalogs ->
                catalogs.map { it.copy(showOnHome = showOnHome) }
            }
        }
        refresh()
        _status.value = if (showOnHome) "All catalogs shown" else "All catalogs hidden"
    }

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
     *
     * Delegates to the (type, id) overload for every matching type variant
     * of this catalog id, so both entry points share ONE write path. The
     * old standalone implementation matched by id alone — silently flipping
     * the same catalog id of every type (AIOStreams lists top_rated as both
     * movie and series) — and skipped refresh(), leaving the catalog
     * manager's state stale so the two screens visibly disagreed.
     */
    fun setCatalogShowOnHome(
        addonId: String,
        catalogId: String,
        showOnHome: Boolean
    ) {
        val addon = _addons.value.firstOrNull { it.id == addonId }
            ?: return

        addon.catalogs
            .filter { it.id == catalogId }
            .distinctBy { it.type.trim().lowercase() }
            .forEach { catalog ->
                setCatalogShowOnHome(
                    addonId = addonId,
                    catalogType = catalog.type,
                    catalogId = catalogId,
                    showOnHome = showOnHome
                )
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
        // Renumber the edited addon's catalogs in GLOBAL slot space, not
        // 0..n-1: per-addon renumbering would collapse this addon's block to
        // the front of the global order on every per-addon edit (hide/show,
        // rename, intra-addon move), stomping cross-addon arrangements made
        // in the catalog manager. Keeping the addon's other catalogs at their
        // current global offsets and spacing the edited set between them
        // preserves everything else exactly.
        val currentAddon = _addons.value.firstOrNull { it.id == addonId }
        val previousGlobalOrders = currentAddon
            ?.catalogs
            ?.map { "${it.type.trim().lowercase()}::${it.id.trim().lowercase()}" to it.order }
            ?.toMap()
            .orEmpty()
        val previousMax = previousGlobalOrders.values.maxOrNull() ?: -1

        val updated = _addons.value.map { addon ->
            if (addon.id == addonId) {
                addon.copy(
                    catalogs = catalogs
                        .sortedBy { it.order }
                        .mapIndexed { index, catalog ->
                            val key = "${catalog.type.trim().lowercase()}::${catalog.id.trim().lowercase()}"
                            val previous = previousGlobalOrders[key]
                            catalog.copy(
                                order = previous ?: (previousMax + 1 + index)
                            )
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
