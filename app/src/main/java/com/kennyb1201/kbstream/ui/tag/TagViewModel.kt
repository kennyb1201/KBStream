package com.kennyb1201.kbstream.ui.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.launch

class TagViewModel : ViewModel() {
    private val repository = TmdbRepository()

    suspend fun loadByGenre(genreId: Int): List<StudioSection> = repository.getByGenre(genreId)

    suspend fun loadByKeyword(keywordId: Int): List<StudioSection> = repository.getByKeyword(keywordId)

    fun resolveAndNavigate(tmdbId: Int, mediaType: String, onNavigateDetail: (String, String) -> Unit) {
        viewModelScope.launch {
            val imdbId = repository.resolveImdbId(tmdbId, mediaType)
            if (imdbId != null) onNavigateDetail(mediaType, imdbId)
        }
    }
}
