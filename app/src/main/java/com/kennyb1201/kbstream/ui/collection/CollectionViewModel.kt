package com.kennyb1201.kbstream.ui.collection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import com.kennyb1201.kbstream.data.runCatchingCancellable

/**
 * Loads a TMDB collection and its parts, resolving each part's TMDB id to
 * an IMDB id so watched badges and the long-press "Mark as Watched" action
 * can key off the same ids the rest of the app uses.
 */
class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository.getInstance(application)
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
        // No `resolved.isEmpty()` guard: the badges no longer depend on the
        // resolution pass at all (see the tmdb-flavored candidate below).
        if (detail == null) {
            emptySet()
        } else {
            detail.parts
                .flatMap { part ->
                    // The keys a part has: the resolved IMDB flavor first,
                    // the raw `tmdb:<n>` one second (see [WatchedStatusKeys],
                    // which owns the rule and is where the past-the-cap and
                    // no-resolution cases are tested). Parts are movies.
                    WatchedStatusKeys.watchedKeysOf(
                        tmdbId = part.id,
                        mediaType = "movie",
                        resolvedImdbId = resolved[lookupKey(part.id, "movie")],
                        matches = watchedStatusRepository::isWatchedCached
                    )
                }
                .toSet()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    // Eye-badge twin of watchedKeys (collection parts are movies, so the
    // eye only ever resolves through a manual in-progress state; kept for
    // parity with every other screen's watched pipeline).
    val partialWatchedKeys: StateFlow<Set<String>> = combine(
        _collection,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { detail: TmdbCollectionDetail?, resolved: Map<String, String>, _: Long ->
        // Same two candidates as watchedKeys, against the partially-watched
        // state, and the same dropped `resolved.isEmpty()` guard.
        if (detail == null) {
            emptySet()
        } else {
            detail.parts
                .flatMap { part ->
                    WatchedStatusKeys.watchedKeysOf(
                        tmdbId = part.id,
                        mediaType = "movie",
                        resolvedImdbId = resolved[lookupKey(part.id, "movie")],
                        matches = watchedStatusRepository::isPartiallyWatchedCached
                    )
                }
                .toSet() - watchedKeys.value
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    // Caps parallel TMDB imdb-id lookups (same rationale as TagViewModel).
    private val imdbResolveSemaphore = Semaphore(permits = 8)

    private var currentId: Int? = null

    /**
     * Which load owns the state. This ViewModel is activity-scoped, so the
     * same-route guard above only stops a RE-load of one collection; two
     * different collections opened in a row run two launches through one
     * instance, and the slower one used to publish - its parts, its watched
     * resolution and its spinner - under the other's page.
     */
    private var loadGeneration = 0

    /**
     * The key a tile is compared under. Delegates to [WatchedStatusKeys.key],
     * so the keys the screen tests and the keys the sets above are built from
     * cannot describe the same title differently.
     */
    fun watchedKey(id: String, type: String): String = WatchedStatusKeys.key(id, type)

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    fun load(collectionId: Int) {
        val isSameRoute =
            currentId == collectionId &&
                _collection.value != null

        if (isSameRoute) return

        currentId = collectionId
        val generation = ++loadGeneration

        viewModelScope.launch {
            _isLoading.value = true
            _collection.value = null
            _resolvedIds.value = emptyMap()

            try {
                val loaded = tmdbRepository.getCollection(collectionId)
                if (generation != loadGeneration) return@launch
                _collection.value = loaded
            } catch (e: Exception) {
                Log.e("COLLECTION_VM", "load failed id=$collectionId", e)
                if (generation != loadGeneration) return@launch
                _collection.value = null
            }

            // The spinner and the badge resolution belong to the page that owns
            // the state: clearing it here would stop the in-flight newer load's
            // spinner and resolve this collection's parts under its page.
            if (generation != loadGeneration) return@launch
            _isLoading.value = false

            val detail = _collection.value
            if (detail != null) {
                resolveAndPreload(detail.parts.map { it.id })
            }
        }
    }

    private suspend fun resolveAndPreload(tmdbIds: List<Int>) {
        if (tmdbIds.isEmpty()) return

        // The badge preload, UNCAPPED and ahead of the resolution below: parts
        // are movies, and the raw `tmdb:<n>` flavor is the one the watched
        // pipeline answers under for them - local DB plus the already-cached
        // Simkl snapshot, no network. It runs even when every resolution below
        // comes back null, which is the case the `resolved.isEmpty()` return
        // used to leave bare. HomeViewModel's watched refresh preloads its whole
        // rail list the same way, and for the same reason: the old tt-only view
        // of the pipeline silently dropped every tmdb-keyed item.
        val tmdbPairs = tmdbIds
            .filter { it > 0 }
            .distinct()
            .map { "tmdb:$it" to "movie" }
        watchedStatusRepository.preload(tmdbPairs)

        val unique = tmdbIds
            .filter { it > 0 }
            .distinct()
            .take(300)

        if (unique.isEmpty()) return

        try {
            val resolvedPairs = supervisorScope {
                unique.map { tmdbId ->
                    async {
                        val imdbId = runCatchingCancellable {
                            imdbResolveSemaphore.withPermit {
                                tmdbRepository.resolveImdbId(tmdbId, "movie")
                            }
                        }.getOrNull()
                        Pair(tmdbId, imdbId)
                    }
                }.map { it.await() }
            }

            val resolved = resolvedPairs.filter { (_, imdbId) ->
                !imdbId.isNullOrBlank()
            }

            if (resolved.isEmpty()) return

            _resolvedIds.value = resolved.mapNotNull { (tmdbId, imdbId) ->
                imdbId?.let { lookupKey(tmdbId, "movie") to it }
            }.toMap()

            watchedStatusRepository.preload(
                resolved.mapNotNull { (_, imdbId) -> imdbId?.let { it to "movie" } }.distinct()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a failure: rethrow so the scope that was
            // canceled stops here instead of logging it and clearing state as
            // if the work had simply failed.
            throw e
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
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, "movie")
                }.getOrNull()
                    ?: return@launch

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatchingCancellable {
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
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, "movie")
                }.getOrNull()
                    ?: return@launch

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatchingCancellable {
                watchedStatusRepository.markUnwatchedLocal(imdbId, "movie")
            }.onFailure { e ->
                Log.e("COLLECTION_VM", "markUnwatched failed tmdb=$tmdbId", e)
            }
        }
    }
}
