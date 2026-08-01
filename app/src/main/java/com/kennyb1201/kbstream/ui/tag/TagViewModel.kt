package com.kennyb1201.kbstream.ui.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
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

class TagViewModel : ViewModel() {
    private val repository = TmdbRepository()

    private val _sections = MutableStateFlow<List<PagedStudioSection>>(emptyList())
    val sections: StateFlow<List<PagedStudioSection>> = _sections.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentTagId: Int? = null
    private var currentMode: Mode? = null

    private enum class Mode {
        GENRE, KEYWORD, NETWORK
    }

    fun loadGenre(tagId: Int) {
        if (currentTagId == tagId && currentMode == Mode.GENRE && _sections.value.isNotEmpty()) return
        currentTagId = tagId
        currentMode = Mode.GENRE
        viewModelScope.launch {
            _isLoading.value = true
            val initial = repository.getInitialGenreSections(tagId)
            _sections.value = initial.map {
                PagedStudioSection(title = it.title, items = it.items, nextPage = 2, hasMore = it.items.isNotEmpty())
            }
            _isLoading.value = false
        }
    }

    fun loadKeyword(tagId: Int) {
        if (currentTagId == tagId && currentMode == Mode.KEYWORD && _sections.value.isNotEmpty()) return
        currentTagId = tagId
        currentMode = Mode.KEYWORD
        viewModelScope.launch {
            _isLoading.value = true
            val initial = repository.getInitialKeywordSections(tagId)
            _sections.value = initial.map {
                PagedStudioSection(title = it.title, items = it.items, nextPage = 2, hasMore = it.items.isNotEmpty())
            }
            _isLoading.value = false
        }
    }

    fun loadNetwork(tagId: Int) {
        if (currentTagId == tagId && currentMode == Mode.NETWORK && _sections.value.isNotEmpty()) return
        currentTagId = tagId
        currentMode = Mode.NETWORK
        viewModelScope.launch {
            _isLoading.value = true
            val initial = repository.getInitialNetworkSections(tagId)
            _sections.value = initial.map {
                PagedStudioSection(title = it.title, items = it.items, nextPage = 2, hasMore = it.items.isNotEmpty())
            }
            _isLoading.value = false
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
            val page = when (mode) {
                Mode.GENRE -> repository.getGenreRailPage(tagId, title, current.nextPage)
                Mode.KEYWORD -> repository.getKeywordRailPage(tagId, title, current.nextPage)
                Mode.NETWORK -> repository.getNetworkRailPage(tagId, title, current.nextPage)
            }

            _sections.value = _sections.value.map { section ->
                if (section.title != title) {
                    section
                } else {
                    val merged = (section.items + page.items).distinctBy { it.item.id }
                    section.copy(
                        items = merged,
                        nextPage = if (page.hasMore) section.nextPage + 1 else section.nextPage,
                        isLoadingMore = false,
                        hasMore = page.hasMore
                    )
                }
            }
        }
    }

    fun resolveAndNavigate(tmdbId: Int, mediaType: String, onNavigateDetail: (String, String) -> Unit) {
        viewModelScope.launch {
            val imdbId = repository.resolveImdbId(tmdbId, mediaType)
            if (imdbId != null) onNavigateDetail(mediaType, imdbId)
        }
    }
}
