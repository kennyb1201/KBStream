package com.kennyb1201.kbstream.ui.kb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.KBHomeOrder
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.KBRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Home-side state: imported KB COLLECTIONS (each renders as a titled row
 * of folder tiles on Home) plus the user's rail arrangement for
 * interleaving with addon catalog rails. Cache-first so returning Home is
 * instant.
 */
class KBHomeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val collections: List<KBCollectionProfile> = emptyList(),
        val arrangement: KBHomeOrder = KBHomeOrder(),
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val repository = KBRepository.getInstance(application)

    init {
        load()
        observeProfileSwitches()
    }

    /**
     * Cache-first load; re-run on Home resume to pick up manager edits.
     *
     * The result is dropped when the active profile changed while the read
     * was in flight: the collection list and the rail arrangement are
     * profile-scoped prefs, so publishing a stale read would repaint the
     * profile the user just left over the new one.
     */
    fun load() {
        val requestedProfileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
        viewModelScope.launch {
            val arrangement = KBHomeOrderPrefs.get(getApplication())
            val collections = runCatching { repository.loadProfiles() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.pinToTop }
            if (
                requestedProfileId !=
                com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
            ) {
                return@launch
            }
            _state.value = _state.value.copy(
                arrangement = arrangement,
                collections = collections,
                isLoading = false
            )
        }
    }

    /**
     * Profile-switch isolation: KB collection rails are interleaved with the
     * addon rails on Home, so the previous profile's collections must be
     * dropped the moment the profile changes — Home otherwise interleaves
     * them with the incoming profile's rails until this reload lands (the
     * "old rails flash" right after a switch).
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var appliedProfileId =
                com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect { profile ->
                    val id = profile?.id
                    if (id == appliedProfileId) return@collect
                    appliedProfileId = id
                    _state.value = UiState()
                    load()
                }
        }
    }
}
