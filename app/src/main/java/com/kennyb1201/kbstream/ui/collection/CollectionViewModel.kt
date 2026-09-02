package com.kennyb1201.kbstream.ui.collection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Loads a TMDB collection and its parts, resolving each part's TMDB id to
 * an IMDB id so watched badges and the long-press "Mark as Watched" action
 * can key off the same ids the rest of the app uses.
 */
class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _collection = MutableStateFlow<TmdbCollectionDetail?>(null)
    val collection: StateFlow<TmdbCollectionDetail?> = _collection.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // TMDB part id -> IMDB id, filled once parts load so badges can compare
    // against the same imdb-keyed watched keys used everywhere else.
    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    // Reactive watched keys for the visible parts, recomputed whenever the
    // parts, resolutions, or watch state change.
    val watchedKeys: StateFlow<Set<String>> = combine(
        _collection,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { detail: TmdbCollectionDetail?, resolved: Map<String, String>, _: Long ->
        if (detail == null || resolved.isEmpty()) {
            emptySet()
        } else {
            detail.parts
                .mapNotNull { part ->
                    val imdbId = resolved[lookupKey(part.id, "movie")] ?: return@mapNotNull null
                    if (watchedStatusRepository.isWatchedCached(imdbId, "movie")) {
                        watchedKey(imdbId, "movie")
                    } else {
                        null
                    }
                }
                .toSet()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    private var currentId: Int? = null

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    fun load(collectionId: Int) {
        val isSameRoute =
            currentId == collectionId &&
                _collection.value != null

        if (isSameRoute) return

        currentId = collectionId

        viewModelScope.launch {
            _isLoading.value = true
            _collection.value = null
            _resolvedIds.value = emptyMap()

            try {
                _collection.value = tmdbRepository.getCollection(collectionId)
            } catch (e: Exception) {
                Log.e("COLLECTION_VM", "load failed id=$collectionId", e)
                _collection.value = null
            }

            _isLoading.value = false

            val detail = _collection.value
            if (detail != null) {
                resolveAndPreload(detail.parts.map { it.id })
            }
        }
    }

    private suspend fun resolveAndPreload(tmdbIds: List<Int>) {
        if (tmdbIds.isEmpty()) return

        val unique = tmdbIds
            .filter { it > 0 }
            .distinct()
            .take(300)

        if (unique.isEmpty()) return

        try {
            val resolvedPairs = supervisorScope {
                unique.map { tmdbId ->
                    async {
                        val imdbId = runCatching {
                            tmdbRepository.resolveImdbId(tmdbId, "movie")
                        }.getOrNull()
                        Pair(tmdbId, imdbId)
                    }
                }.map { it.await() }
            }

            val resolved = resolvedPairs.filter { (_, imdbId) ->
                !imdbId.isNullOrBlank()
            }

            if (resolved.isEmpty()) return

            _resolvedIds.value = resolved.associate { (tmdbId, imdbId) ->
                lookupKey(tmdbId, "movie") to imdbId!!
            }

            watchedStatusRepository.preload(
                resolved.map { (_, imdbId) -> imdbId!! to "movie" }.distinct()
            )
        } catch (e: Exception) {
            Log.e("COLLECTION_VM", "resolveAndPreload failed: ${e.message}", e)
            _resolvedIds.value = emptyMap()
        }
    }

    /**
     * Long-press "Mark as Watched" on a collection part poster: resolves the
     * TMDB id to an IMDB id, records the persistent local watched override
     * (mirrored to SIMKL when connected) and caches the resolution so the
     * row badge updates reactively.
     */
    fun markAsWatched(tmdbId: Int) {
        viewModelScope.launch {
            val lookup = lookupKey(tmdbId, "movie")
            val imdbId = _resolvedIds.value[lookup]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, "movie")
                }.getOrNull()
                    ?: return@launch

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, "movie")
            }.onFailure { e ->
                Log.e("COLLECTION_VM", "markAsWatched failed tmdb=$tmdbId", e)
            }
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a collection part poster: resolves the
     * TMDB id to an IMDB id, removes the persistent local watched override
     * (mirrored as a Simkl history delete when connected) and caches the
     * resolution so the row badge updates reactively.
     */
    fun markUnwatched(tmdbId: Int) {
        viewModelScope.launch {
            val lookup = lookupKey(tmdbId, "movie")
            val imdbId = _resolvedIds.value[lookup]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, "movie")
                }.getOrNull()
                    ?: return@launch

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, "movie")
            }.onFailure { e ->
                Log.e("COLLECTION_VM", "markUnwatched failed tmdb=$tmdbId", e)
            }
        }
    }
}
