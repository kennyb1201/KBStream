package com.kennyb1201.kbstream.ui.nuvio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.nuvio.NuvioCollectionProfile
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrder
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrderPrefs
import com.kennyb1201.kbstream.data.nuvio.NuvioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Home-side state: imported Nuvio COLLECTIONS (each renders as a titled row
 * of folder tiles on Home) plus the user's rail arrangement for
 * interleaving with addon catalog rails. Cache-first so returning Home is
 * instant.
 */
class NuvioHomeViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val collections: List<NuvioCollectionProfile> = emptyList(),
        val arrangement: NuvioHomeOrder = NuvioHomeOrder(),
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val repository = NuvioRepository(application)

    init {
        load()
    }

    /** Cache-first load; re-run on Home resume to pick up manager edits. */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                arrangement = NuvioHomeOrderPrefs.get(getApplication())
            )
            val collections = runCatching { repository.loadProfiles() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.pinToTop }
            _state.value = _state.value.copy(
                collections = collections,
                isLoading = false
            )
        }
    }
}
