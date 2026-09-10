package com.kennyb1201.kbstream.ui.nuvio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.nuvio.NuvioCollectionProfile
import com.kennyb1201.kbstream.data.nuvio.NuvioFolder
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrder
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrderPrefs
import com.kennyb1201.kbstream.data.nuvio.NuvioProfilePrefs
import com.kennyb1201.kbstream.data.nuvio.NuvioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the Collections manager screen: imported profile sources and the
 * merged home-rail arrangement across COLLECTIONS (each = one titled row of
 * folder tiles) + addon catalog rails — pin to top, move up/down, hide/show,
 * mirroring the catalog manager's controls for addon rails.
 */
class NuvioHomeManagerViewModel(application: Application) : AndroidViewModel(application) {

    data class ManagedRail(
        val key: String,
        val isCollection: Boolean,
        val title: String,
        val subtitle: String?,
        // Collection-only: first folder count for context.
        val folderCount: Int = 0
    )

    data class UiState(
        val isLoading: Boolean = true,
        val profileUrls: List<String> = emptyList(),
        val collections: List<NuvioCollectionProfile> = emptyList(),
        val rails: List<ManagedRail> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val pinned: Set<String> = emptySet(),
        val statusMessage: String? = null,
        val reorderMode: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val addonManager = AddonManager.getInstance(application)
    private val repository = NuvioRepository(application)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val orderPrefs = NuvioHomeOrderPrefs.get(getApplication())
            val collections = runCatching { repository.loadProfiles() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.pinToTop }

            val collectionRails = collections.map { collection ->
                ManagedRail(
                    key = NuvioHomeOrderPrefs.collectionKey(collection.id, collection.title),
                    isCollection = true,
                    title = collection.title.ifBlank { "Untitled collection" },
                    subtitle = "${collection.folders.size} folders",
                    folderCount = collection.folders.size
                )
            }

            val addonRails = addonManager.getInstalledAddons()
                .flatMap { addon ->
                    addon.catalogs
                        .filter { it.showOnHome }
                        .map { catalog ->
                            ManagedRail(
                                key = NuvioHomeOrderPrefs.addonKey(
                                    addon.manifestUrl,
                                    catalog.type,
                                    catalog.id
                                ),
                                isCollection = false,
                                title = catalog.displayName,
                                subtitle = addon.displayName
                            )
                        }
                }

            _state.value = _state.value.copy(
                isLoading = false,
                profileUrls = NuvioProfilePrefs.getProfileUrls(getApplication()),
                collections = collections,
                rails = mergeRails(addonRails, collectionRails, orderPrefs),
                hidden = orderPrefs.hiddenSet,
                pinned = orderPrefs.pinned.toSet()
            )
        }
    }

    /** Default arrangement: pinned first, then stored order, then defaults. */
    private fun mergeRails(
        addonRails: List<ManagedRail>,
        collectionRails: List<ManagedRail>,
        prefs: NuvioHomeOrder
    ): List<ManagedRail> {
        val byKey = (collectionRails + addonRails).associateBy { it.key }
        val defaults = collectionRails + addonRails

        val positioned = mutableListOf<ManagedRail>()
        val seen = mutableSetOf<String>()

        for (key in prefs.pinned) {
            byKey[key]?.let { rail ->
                positioned += rail
                seen += key
            }
        }
        for (key in prefs.order) {
            if (seen.add(key)) {
                byKey[key]?.let { positioned += it }
            }
        }
        for (rail in defaults) {
            if (seen.add(rail.key)) {
                positioned += rail
            }
        }
        return positioned
    }

    private fun persist(transform: (NuvioHomeOrder) -> NuvioHomeOrder) {
        val context = getApplication<Application>()
        val updated = transform(NuvioHomeOrderPrefs.get(context))
        NuvioHomeOrderPrefs.save(context, updated)
        reload()
    }

    fun togglePin(key: String) {
        persist { prefs ->
            if (prefs.pinned.contains(key)) {
                prefs.copy(pinned = prefs.pinned - key)
            } else {
                // A pin also lifts the rail out of any stored position.
                prefs.copy(
                    pinned = prefs.pinned + key,
                    order = prefs.order - key
                )
            }
        }
    }

    fun toggleHidden(key: String) {
        persist { prefs ->
            if (prefs.hidden.contains(key)) {
                prefs.copy(hidden = prefs.hidden - key)
            } else {
                prefs.copy(
                    hidden = prefs.hidden + key,
                    pinned = prefs.pinned - key,
                    order = prefs.order - key
                )
            }
        }
    }

    /** Move a rail one slot (delta -1/+1) among visible rails. */
    fun move(key: String, delta: Int) {
        persist { prefs ->
            val visible = _state.value.rails.filter { it.key !in prefs.hiddenSet }
            val from = visible.indexOfFirst { it.key == key }
            if (from == -1) return@persist prefs
            val to = (from + delta).coerceIn(0, visible.lastIndex)
            if (from == to) return@persist prefs

            val ordered = visible.toMutableList()
            val item = ordered.removeAt(from)
            ordered.add(to, item)

            val pinnedCount = prefs.pinned.count { pinKey ->
                visible.any { it.key == pinKey }
            }
            prefs.copy(
                order = ordered.drop(pinnedCount).map { it.key },
                pinned = ordered.take(pinnedCount).map { it.key }
            )
        }
    }

    fun toggleReorderMode() {
        _state.value = _state.value.copy(reorderMode = !_state.value.reorderMode)
    }

    fun addProfileUrl(url: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (!NuvioRepository.isPlausibleUrl(url)) {
                _state.value = _state.value.copy(statusMessage = "Enter an https:// URL")
                return@launch
            }
            _state.value = _state.value.copy(statusMessage = "Importing…")
            val ok = runCatching {
                NuvioProfilePrefs.addProfileUrl(context, url) &&
                    repository.loadProfileForValidation(url).isNotEmpty()
            }.getOrElse { false }
            if (ok) {
                _state.value = _state.value.copy(statusMessage = "Collections imported")
                reload()
            } else {
                NuvioProfilePrefs.removeProfileUrl(context, url)
                repository.evict(url)
                _state.value = _state.value.copy(
                    statusMessage = "Import failed — not a Nuvio collections profile"
                )
            }
        }
    }

    fun removeProfileUrl(url: String) {
        val context = getApplication<Application>()
        NuvioProfilePrefs.removeProfileUrl(context, url)
        repository.evict(url)
        _state.value = _state.value.copy(statusMessage = "Collection source removed")
        reload()
    }

    fun clearStatus() {
        _state.value = _state.value.copy(statusMessage = null)
    }
}
