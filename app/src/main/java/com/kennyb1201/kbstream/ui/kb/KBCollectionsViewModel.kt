package com.kennyb1201.kbstream.ui.kb

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.kb.KBProfilePrefs
import com.kennyb1201.kbstream.data.kb.KBRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hub state for the Collections screen: every folder tile across all
 * configured KB profiles, plus the configured profile URL list and the
 * import/refresh status surfaced in Settings and the hub header.
 */
class KBCollectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KBRepository.getInstance(application)

    data class UiState(
        val folders: List<KBFolder> = emptyList(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val lastRefreshMs: Long = 0L,
        // Feedback line for import/remove actions (Settings + hub header).
        val statusMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _profileUrls = MutableStateFlow<List<String>>(emptyList())
    val profileUrls: StateFlow<List<String>> = _profileUrls.asStateFlow()

    init {
        _profileUrls.value = KBProfilePrefs.getProfileUrls(application)
        _state.value = _state.value.copy(lastRefreshMs = KBProfilePrefs.getLastRefreshMs(application))
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val folders = runCatching { repository.loadAllFolders() }
                .onFailure { e ->
                    Log.e("KB_VM", "load failed: ${e.message}")
                }
                .getOrDefault(emptyList())
            _state.value = _state.value.copy(
                isLoading = false,
                folders = folders,
                error = if (folders.isEmpty() && _profileUrls.value.isNotEmpty()) {
                    "Couldn't load your collections. Check your connection or the profile URL."
                } else {
                    null
                },
                lastRefreshMs = System.currentTimeMillis()
            )
            KBProfilePrefs.setLastRefreshMs(getApplication(), System.currentTimeMillis())
        }
    }

    /** Force re-download of every profile (bypasses the 12h cache). */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            val profiles = runCatching { repository.refreshAll() }
                .onFailure { e ->
                    Log.e("KB_VM", "refresh failed: ${e.message}")
                }
                .getOrDefault(emptyList())
            KBProfilePrefs.setLastRefreshMs(getApplication(), System.currentTimeMillis())
            _state.value = _state.value.copy(
                isRefreshing = false,
                folders = profiles.sortedByDescending { it.pinToTop }.flatMap { it.folders },
                lastRefreshMs = System.currentTimeMillis(),
                statusMessage = if (profiles.isEmpty()) "Refresh failed" else null
            )
        }
    }

    /**
     * Import a hosted profile URL: validate it downloads + parses as a KB
     * collections profile, then persist it and merge its folders in.
     */
    fun addProfileUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!com.kennyb1201.kbstream.data.kb.KBRepository.isPlausibleUrl(url)) {
            _state.value = _state.value.copy(statusMessage = "Enter an https:// URL to a KB collections JSON")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(statusMessage = "Importing collections…")
            val ok = runCatching {
                KBProfilePrefs.addProfileUrl(getApplication(), url) &&
                    repository.loadProfileForValidation(url).isNotEmpty()
            }.getOrElse { false }

            if (ok) {
                _profileUrls.value = KBProfilePrefs.getProfileUrls(getApplication())
                _state.value = _state.value.copy(statusMessage = "Collections imported")
                load()
            } else {
                // Roll back so a bad URL never lingers in the list.
                KBProfilePrefs.removeProfileUrl(getApplication(), url)
                repository.evict(url)
                _state.value = _state.value.copy(
                    statusMessage = "Import failed — that URL isn't a KB collections profile"
                )
            }
        }
    }

    fun removeProfileUrl(url: String) {
        KBProfilePrefs.removeProfileUrl(getApplication(), url)
        viewModelScope.launch { repository.evict(url) }
        _profileUrls.value = KBProfilePrefs.getProfileUrls(getApplication())
        load()
    }

    fun clearStatus() {
        _state.value = _state.value.copy(statusMessage = null)
    }
}
