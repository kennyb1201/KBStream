package com.kennyb1201.kbstream.ui.tag

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

class TagViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository()
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _sections = MutableStateFlow<List<StudioSection>>(emptyList())
    val sections: StateFlow<List<StudioSection>> = _sections.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String = "$mediaType::$tmdbId"

    fun load(id: Int, isKeyword: Boolean, type: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _sections.value = emptyList()
            _watchedKeys.value = emptySet()
            _resolvedIds.value = emptyMap()

            try {
                val result = if (isKeyword) {
                    tmdbRepository.getByKeyword(id)
                } else {
                    tmdbRepository.getByGenre(id)
                }

                _sections.value = result
                refreshWatchedStatus(result)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load tag"
                Log.e("TAG_VM", "load failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun refreshWatchedStatus(sections: List<StudioSection>) {
        try {
            if (sections.isEmpty()) {
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
                return
            }

            val uniqueItems = sections
                .flatMap { it.items }
                .mapNotNull { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = studioItem.mediaType.lowercase()
                    if (tmdbId <= 0 || mediaType.isBlank()) null else tmdbId to mediaType
                }
                .distinct()

            if (uniqueItems.isEmpty()) {
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
                return
            }

            val resolved = uniqueItems.mapNotNull { (tmdbId, mediaType) ->
                val imdbId = runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, mediaType)
                }.getOrNull()

                if (imdbId.isNullOrBlank()) null else Triple(tmdbId, mediaType, imdbId)
            }

            _resolvedIds.value = resolved.associate { (tmdbId, mediaType, imdbId) ->
                lookupKey(tmdbId, mediaType) to imdbId
            }

            if (resolved.isEmpty()) {
                _watchedKeys.value = emptySet()
                return
            }

            val preloadItems = resolved
                .map { (_, mediaType, imdbId) -> imdbId to mediaType }
                .distinct()

            watchedStatusRepository.preload(preloadItems)

            _watchedKeys.value = resolved
                .filter { (_, mediaType, imdbId) ->
                    watchedStatusRepository.isWatchedCached(imdbId, mediaType)
                }
                .map { (_, mediaType, imdbId) ->
                    watchedKey(imdbId, mediaType)
                }
                .toSet()
        } catch (e: Exception) {
            Log.e("TAG_WATCHED", "refresh failed", e)
            _watchedKeys.value = emptySet()
            _resolvedIds.value = emptyMap()
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
