package com.kennyb1201.kbstream.ui.studio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository()
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _sections = MutableStateFlow<List<StudioSection>>(emptyList())
    val sections: StateFlow<List<StudioSection>> = _sections.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun watchedKey(id: String, type: String): String = "$type::$id"

    private fun refreshWatchedStatus(sections: List<StudioSection>) {
        viewModelScope.launch {
            try {
                if (sections.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    return@launch
                }

                val items = sections
                    .flatMap { it.items }
                    .mapNotNull { studioItem ->
                        val id = studioItem.item.id.toString()
                        val type = studioItem.mediaType.lowercase()

                        if (id.isBlank() || type.isBlank()) return@mapNotNull null
                        id to type
                    }
                    .distinct()

                if (items.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    return@launch
                }

                watchedStatusRepository.preload(items)

                _watchedKeys.value = items
                    .filter { (id, _) -> watchedStatusRepository.isWatchedCached(id) }
                    .map { (id, type) -> watchedKey(id, type) }
                    .toSet()

                Log.e(
                    "STUDIO_WATCHED",
                    "refreshWatchedStatus done, items=${items.size}, watched=${_watchedKeys.value.size}"
                )
            } catch (e: Exception) {
                Log.e("STUDIO_WATCHED", "refreshWatchedStatus failed: ${e.message}", e)
                _watchedKeys.value = emptySet()
            }
        }
    }

    fun load(id: Int, isNetwork: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = if (isNetwork) {
                    tmdbRepository.getByNetwork(id)
                } else {
                    tmdbRepository.getByCompany(id)
                }

                _sections.value = result
                refreshWatchedStatus(result)
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
