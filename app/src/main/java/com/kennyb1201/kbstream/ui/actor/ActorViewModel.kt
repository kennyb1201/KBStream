package com.kennyb1201.kbstream.ui.actor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ActorViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository()

    private val _person = MutableStateFlow<TmdbPersonDetail?>(null)
    val person: StateFlow<TmdbPersonDetail?> = _person.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun load(personId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _person.value = tmdbRepository.getPerson(personId)
            _isLoading.value = false
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
