package com.kennyb1201.kbstream.ui.tag

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PagedStudioSection(
    val title: String,
    val items: List<StudioItem> = emptyList(),
    val nextPage: Int = 2,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true
)

class TagViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TmdbRepository()
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _sections = MutableStateFlow<List<PagedStudioSection>>(emptyList())
    val sections: StateFlow<List<PagedStudioSection>> = _sections.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentTagId: Int? = null
    private var currentMode: Mode? = null

    private enum class Mode {
        GENRE, KEYWORD, NETWORK
    }

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String = "${mediaType.lowercase()}::$tmdbId"

    private fun refreshWatchedStatus(sections: List<PagedStudioSection>) {
        viewModelScope.launch {
            try {
                if (sections.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedIds.value = emptyMap()
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
                    _resolvedIds.value = emptyMap()
                    return@launch
                }

                val resolved = uniqueItems.mapNotNull { (tmdbId, mediaType) ->
                    val imdbId = runCatching {
                        repository.resolveImdbId(tmdbId, mediaType)
                    }.getOrNull()

                    if (imdbId.isNullOrBlank()) {
                        null
                    } else {
                        Triple(tmdbId, mediaType, imdbId)
                    }
                }

                if (resolved.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedIds.value = emptyMap()
                    return@launch
                }

                _resolvedIds.value = resolved.associate { (tmdbId, mediaType, imdbId) ->
                    lookupKey(tmdbId, mediaType) to imdbId
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
    }

    fun loadGenre(tagId: Int) {
        if (currentTagId == tagId && currentMode == Mode.GENRE && _sections.value.isNotEmpty()) return
        currentTagId = tagId
        currentMode = Mode.GENRE

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = repository.getInitialGenreSections(tagId)
                _sections.value = initial.map {
                    PagedStudioSection(
                        title = it.title,
                        items = it.items,
                        nextPage = 2,
                        hasMore = it.items.isNotEmpty()
                    )
                }
                refreshWatchedStatus(_sections.value)
            } catch (e: Exception) {
                Log.e("TAG_LOAD", "loadGenre failed: ${e.message}", e)
                _sections.value = emptyList()
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadKeyword(tagId: Int) {
        if (currentTagId == tagId && currentMode == Mode.KEYWORD && _sections.value.isNotEmpty()) return
        currentTagId = tagId
        currentMode = Mode.KEYWORD

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = repository.getInitialKeywordSections(tagId)
                _sections.value = initial.map {
                    PagedStudioSection(
                        title = it.title,
                        items = it.items,
                        nextPage = 2,
                        hasMore = it.items.isNotEmpty()
                    )
                }
                refreshWatchedStatus(_sections.value)
            } catch (e: Exception) {
                Log.e("TAG_LOAD", "loadKeyword failed: ${e.message}", e)
                _sections.value = emptyList()
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadNetwork(tagId: Int) {
        if (currentTagId == tagId && currentMode == Mode.NETWORK && _sections.value.isNotEmpty()) return
        currentTagId = tagId
        currentMode = Mode.NETWORK

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = repository.getInitialNetworkSections(tagId)
                _sections.value = initial.map {
                    PagedStudioSection(
                        title = it.title,
                        items = it.items,
                        nextPage = 2,
                        hasMore = it.items.isNotEmpty()
                    )
                }
                refreshWatchedStatus(_sections.value)
            } catch (e: Exception) {
                Log.e("TAG_LOAD", "loadNetwork failed: ${e.message}", e)
                _sections.value = emptyList()
                _watchedKeys.value = emptySet()
                _resolvedIds.value = emptyMap()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMore(title: String) {
        val tagId = currentTagId ?: return
        val mode = currentMode ?: return
        val current = _sections.value.firstOrNull { it.title == title } ?: return
        if (current.isLoadingMore || !current.hasMore) return

        _sections.value = _sections.value.map {
            if (it.title == title) it.copy(isLoadingMore = true) else it
        }

        viewModelScope.launch {
            try {
                val page = when (mode) {
                    Mode.GENRE -> repository.getGenreRailPage(tagId, title, current.nextPage)
                    Mode.KEYWORD -> repository.getKeywordRailPage(tagId, title, current.nextPage)
                    Mode.NETWORK -> repository.getNetworkRailPage(tagId, title, current.nextPage)
                }

                _sections.value = _sections.value.map { section ->
                    if (section.title != title) {
                        section
                    } else {
                        val merged = (section.items + page.items)
                            .distinctBy { item -> item.item.id to item.mediaType.lowercase() }

                        section.copy(
                            items = merged,
                            nextPage = if (page.hasMore) current.nextPage + 1 else current.nextPage,
                            isLoadingMore = false,
                            hasMore = page.hasMore
                        )
                    }
                }

                refreshWatchedStatus(_sections.value)
            } catch (e: Exception) {
                Log.e("TAG_LOAD", "loadMore failed for $title: ${e.message}", e)
                _sections.value = _sections.value.map { section ->
                    if (section.title == title) section.copy(isLoadingMore = false) else section
                }
            }
        }
    }

    fun resolveAndNavigate(
        tmdbId: Int,
        mediaType: String,
        onNavigateDetail: (String, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedType = mediaType.lowercase()
            val imdbId = resolvedIds.value[lookupKey(tmdbId, normalizedType)]
                ?: runCatching { repository.resolveImdbId(tmdbId, normalizedType) }.getOrNull()

            if (!imdbId.isNullOrBlank()) {
                onNavigateDetail(normalizedType, imdbId)
            }
        }
    }
}
