package com.kennyb1201.kbstream.ui.tag

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TagRailPage
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class RailPagingState(
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false
)

class TagViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository(application)
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

    private val _pagingStates = MutableStateFlow<Map<String, RailPagingState>>(emptyMap())
    val pagingStates: StateFlow<Map<String, RailPagingState>> = _pagingStates.asStateFlow()

    private var currentId: Int? = null
    private var currentIsKeyword: Boolean = false

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    fun load(id: Int, isKeyword: Boolean, type: String) {
        currentId = id
        currentIsKeyword = isKeyword

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _sections.value = emptyList()
            _watchedKeys.value = emptySet()
            _resolvedIds.value = emptyMap()
            _pagingStates.value = emptyMap()

            try {
                val result = if (isKeyword) {
                    tmdbRepository.getByKeyword(id)
                } else {
                    tmdbRepository.getByGenre(id)
                }

                _sections.value = result
                _pagingStates.value = result.associate { section ->
                    section.title to RailPagingState(
                        nextPage = 2,
                        hasMore = section.items.isNotEmpty(),
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load tag"
                Log.e("TAG_VM", "load failed", e)
            } finally {
                _isLoading.value = false
            }

            val loadedSections = _sections.value
            if (loadedSections.isNotEmpty()) {
                launch {
                    refreshWatchedStatus(loadedSections)
                }
            }
        }
    }

    fun loadMoreSection(title: String) {
        val screenId = currentId ?: return
        var pageToLoad: Int? = null

        _pagingStates.value = _pagingStates.value.toMutableMap().apply {
            val current = this[title] ?: return
            if (current.isLoadingMore || !current.hasMore) return
            pageToLoad = current.nextPage
            this[title] = current.copy(isLoadingMore = true)
        }

        val pageNumber = pageToLoad ?: return

        viewModelScope.launch {
            try {
                val page: TagRailPage = if (currentIsKeyword) {
                    tmdbRepository.getKeywordRailPage(screenId, title, pageNumber)
                } else {
                    tmdbRepository.getGenreRailPage(screenId, title, pageNumber)
                }

                val existingSection = _sections.value.firstOrNull { it.title == title }
                if (existingSection != null && page.items.isNotEmpty()) {
                    val mergedItems = (existingSection.items + page.items)
                        .distinctBy { item -> item.item.id }

                    val updatedSections = _sections.value.map { section ->
                        if (section.title == title) {
                            section.copy(items = mergedItems)
                        } else {
                            section
                        }
                    }

                    _sections.value = updatedSections
                    refreshWatchedStatus(updatedSections)
                }

                _pagingStates.value = _pagingStates.value.toMutableMap().apply {
                    this[title] = RailPagingState(
                        nextPage = pageNumber + 1,
                        hasMore = page.hasMore,
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                Log.e("TAG_VM", "loadMoreSection failed for $title", e)
                _pagingStates.value = _pagingStates.value.toMutableMap().apply {
                    val current = this[title] ?: return@apply
                    this[title] = current.copy(isLoadingMore = false)
                }
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
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@mapNotNull null
                    if (tmdbId <= 0) null else tmdbId to mediaType
                }
                .distinct()
                .take(300)

            if (uniqueItems.isEmpty()) {
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
                return
            }

            val resolvedTriples = supervisorScope {
                uniqueItems.map { (tmdbId, mediaType) ->
                    async {
                        val imdbId = runCatching {
                            tmdbRepository.resolveImdbId(tmdbId, mediaType)
                        }.getOrNull()
                        Triple(tmdbId, mediaType, imdbId)
                    }
                }.map { it.await() }
            }

            val resolved = resolvedTriples.filter { (_, _, imdbId) ->
                !imdbId.isNullOrBlank()
            }

            _resolvedIds.value = resolved.associate { (tmdbId, mediaType, imdbId) ->
                lookupKey(tmdbId, mediaType) to imdbId!!
            }

            if (resolved.isEmpty()) {
                _watchedKeys.value = emptySet()
                return
            }

            val preloadItems = resolved
                .map { (_, mediaType, imdbId) -> imdbId!! to mediaType }
                .distinct()

            watchedStatusRepository.preload(preloadItems)

            _watchedKeys.value = resolved
                .filter { (_, mediaType, imdbId) ->
                    watchedStatusRepository.isWatchedCached(imdbId!!, mediaType)
                }
                .map { (_, mediaType, imdbId) ->
                    watchedKey(imdbId!!, mediaType)
                }
                .toSet()

            Log.e(
                "TAG_WATCHED",
                "tag watched refresh items=${uniqueItems.size} resolved=${resolved.size} watched=${_watchedKeys.value.size}"
            )
        } catch (e: Exception) {
            Log.e("TAG_WATCHED", "refresh failed", e)
            _watchedKeys.value = emptySet()
            _resolvedIds.value = emptyMap()
        }
    }

    fun resolveAndNavigate(
        tmdbId: Int,
        mediaType: String,
        onNavigateDetail: (String, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val imdbId = _resolvedIds.value[lookupKey(tmdbId, normalizedType)]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (!imdbId.isNullOrBlank()) {
                onNavigateDetail(normalizedType, imdbId)
            }
        }
    }

    private fun normalizeMediaType(mediaType: String?): String? =
        when (mediaType?.lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "series"
            else -> null
        }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, normalizeMediaType(type) ?: type)
}