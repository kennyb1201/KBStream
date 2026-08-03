package com.kennyb1201.kbstream.ui.actor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ActorViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository()
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _person = MutableStateFlow<TmdbPersonDetail?>(null)
    val person: StateFlow<TmdbPersonDetail?> = _person.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _resolvedCreditIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedCreditIds: StateFlow<Map<String, String>> = _resolvedCreditIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun creditLookupKey(tmdbId: Int, mediaType: String): String = "$mediaType::$tmdbId"

    fun load(personId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _watchedKeys.value = emptySet()
            _resolvedCreditIds.value = emptyMap()

            try {
                val result = tmdbRepository.getPerson(personId)
                _person.value = result

                if (result != null) {
                    refreshWatchedStatus(result)
                }

                if (result != null &&
                    result.combinedCredits?.cast.isNullOrEmpty() &&
                    result.combinedCredits?.crew.isNullOrEmpty()
                ) {
                    _error.value = "TMDB returned this person but with zero credits"
                }
            } catch (e: Exception) {
                _error.value = "TMDB request failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun refreshWatchedStatus(person: TmdbPersonDetail) {
        try {
            val featuredCrew = person.combinedCredits?.crew
                .orEmpty()
                .filter(::isFeaturedCrewJob)
                .distinctBy { Triple(it.id, normalizeMediaType(it.mediaType), it.job) }

            val cast = person.combinedCredits?.cast
                .orEmpty()
                .distinctBy { Pair(it.id, normalizeMediaType(it.mediaType)) }

            val allCredits = (featuredCrew + cast)
                .mapNotNull { credit ->
                    val mediaType = normalizeMediaType(credit.mediaType) ?: return@mapNotNull null
                    credit to mediaType
                }

            if (allCredits.isEmpty()) {
                _watchedKeys.value = emptySet()
                _resolvedCreditIds.value = emptyMap()
                Log.e("ACTOR_WATCHED", "no actor credits eligible for watched preload")
                return
            }

            val resolvedMap = linkedMapOf<String, String>()
            val preloadItems = linkedSetOf<Pair<String, String>>()

            allCredits.forEach { (credit, mediaType) ->
                val imdbId = tmdbRepository.resolveImdbId(credit.id, mediaType) ?: return@forEach
                resolvedMap[creditLookupKey(credit.id, mediaType)] = imdbId
                preloadItems += imdbId to mediaType
            }

            _resolvedCreditIds.value = resolvedMap

            if (preloadItems.isEmpty()) {
                _watchedKeys.value = emptySet()
                Log.e("ACTOR_WATCHED", "no resolvable imdb ids for actor credits")
                return
            }

            watchedStatusRepository.preload(preloadItems.toList())

            _watchedKeys.value = preloadItems
                .filter { (imdbId, _) -> watchedStatusRepository.isWatchedCached(imdbId) }
                .map { (imdbId, mediaType) -> watchedKey(imdbId, mediaType) }
                .toSet()

            Log.e(
                "ACTOR_WATCHED",
                "actor watched refresh credits=${allCredits.size} resolved=${preloadItems.size} watched=${_watchedKeys.value.size}"
            )
        } catch (e: Exception) {
            _watchedKeys.value = emptySet()
            _resolvedCreditIds.value = emptyMap()
            Log.e("ACTOR_WATCHED", "actor watched refresh failed", e)
        }
    }

    private fun normalizeMediaType(mediaType: String?): String? =
        when (mediaType?.lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "series"
            else -> null
        }

    private fun isFeaturedCrewJob(credit: TmdbPersonCredit): Boolean {
        val job = credit.job?.trim()?.lowercase() ?: return false
        return job in setOf(
            "director",
            "writer",
            "screenplay",
            "story",
            "teleplay",
            "author",
            "novel",
            "characters"
        )
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
