package com.kennyb1201.kbstream.ui.tag

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRecommendationItem
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TagViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository()
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _items = MutableStateFlow<List<TmdbRecommendationItem>>(emptyList())
    val items: StateFlow<List<TmdbRecommendationItem>> = _items.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String = "${mediaType.lowercase()}::$tmdbId"

    private suspend fun refreshWatchedStatus(
        items: List<TmdbRecommendationItem>,
        mediaType: String
    ) {
        try {
            if (items.isEmpty()) {
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
                return
            }

            val normalizedType = mediaType.lowercase()

            val uniqueItems = items
                .mapNotNull { item ->
                    val tmdbId = item.id
                    if (tmdbId <= 0) null else tmdbId
                }
                .distinct()

            if (uniqueItems.isEmpty()) {
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
                return
            }

            val resolved = uniqueItems.mapNotNull { tmdbId ->
                val imdbId = runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

                if (imdbId.isNullOrBlank()) {
                    null
                } else {
                    tmdbId to imdbId
                }
            }

            _resolvedIds.value = resolved.associate { (tmdbId, imdbId) ->
                lookupKey(tmdbId, normalizedType) to imdbId
            }

            if (resolved.isEmpty()) {
                _watchedKeys.value = emptySet()
                return
            }

            val preloadItems = resolved
                .map { (_, imdbId) -> imdbId to normalizedType }
                .distinct()

            watchedStatusRepository.preload(preloadItems)

            _watchedKeys.value = resolved
                .filter { (_, imdbId) ->
                    watchedStatusRepository.isWatchedCached(imdbId, normalizedType)
                }
                .map { (_, imdbId) ->
                    watchedKey(imdbId, normalizedType)
                }
                .toSet()

            Log.e(
                "TAG_WATCHED",
                "refreshWatchedStatus done, resolved=${resolved.size}, watched=${_watchedKeys.value.size}"
            )
        } catch (e: Exception) {
            Log.e("TAG_WATCHED", "refreshWatchedStatus failed: ${e.message}", e)
            _watchedKeys.value = emptySet()
            _resolvedIds.value = emptyMap()
        }
    }

    fun load(
        id: Int,
        name: String,
        isKeyword: Boolean,
        type: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val normalizedType = type.lowercase()

                val result = if (isKeyword) {
                    tmdbRepository.getByKeyword(id, normalizedType)
                } else {
                    tmdbRepository.getByGenre(id, normalizedType)
                }

                _items.value = result
                refreshWatchedStatus(result, normalizedType)
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
