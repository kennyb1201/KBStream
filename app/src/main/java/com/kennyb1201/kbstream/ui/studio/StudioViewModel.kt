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

            val uniqueItems = sections
                .flatMap { it.items }
                .mapNotNull { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = studioItem.mediaType.lowercase()

                    if (tmdbId <= 0 || mediaType.isBlank()) return@mapNotNull null
                    tmdbId to mediaType
                }
                .distinct()

            if (uniqueItems.isEmpty()) {
                _watchedKeys.value = emptySet()
                return@launch
            }

            val resolved = uniqueItems.mapNotNull { (tmdbId, mediaType) ->
                val imdbId = runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, mediaType)
                }.getOrNull()

                if (imdbId.isNullOrBlank()) {
                    null
                } else {
                    Triple(tmdbId, mediaType, imdbId)
                }
            }

            if (resolved.isEmpty()) {
                _watchedKeys.value = emptySet()
                return@launch
            }

            watchedStatusRepository.preload(
                resolved.map { (_, mediaType, imdbId) -> imdbId to mediaType }
            )

            _watchedKeys.value = resolved
                .filter { (_, _, imdbId) ->
                    watchedStatusRepository.isWatchedCached(imdbId)
                }
                .map { (tmdbId, mediaType, _) ->
                    watchedKey(tmdbId.toString(), mediaType)
                }
                .toSet()

            Log.e(
                "STUDIO_WATCHED",
                "refreshWatchedStatus done, resolved=${resolved.size}, watched=${_watchedKeys.value.size}"
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
