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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(personId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = tmdbRepository.getPerson(personId)
                _person.value = result
                if (result != null && result.combinedCredits?.cast.isNullOrEmpty()) {
                    _error.value = "TMDB returned this person but with zero filmography credits"
                }
            } catch (e: Exception) {
                _error.value = "TMDB request failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
