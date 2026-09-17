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

    private val repository = KBRepository(application)

    init {
        load()
    }

    /** Cache-first load; re-run on Home resume to pick up manager edits. */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                arrangement = KBHomeOrderPrefs.get(getApplication())
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
