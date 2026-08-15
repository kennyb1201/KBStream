package com.kennyb1201.kbstream.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TmdbGenreMatch
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.data.watched.WatchStateBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val tmdbRepository = TmdbRepository(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _results = MutableStateFlow<List<MetaPreview>>(emptyList())
    val results: StateFlow<List<MetaPreview>> = _results.asStateFlow()

    private val _actorResults = MutableStateFlow<List<TmdbSearchPersonResult>>(emptyList())
    val actorResults: StateFlow<List<TmdbSearchPersonResult>> = _actorResults.asStateFlow()

    private val _studioResults = MutableStateFlow<List<TmdbSearchStudioResult>>(emptyList())
    val studioResults: StateFlow<List<TmdbSearchStudioResult>> = _studioResults.asStateFlow()

    private val _genreResults = MutableStateFlow<List<TmdbGenreMatch>>(emptyList())
    val genreResults: StateFlow<List<TmdbGenreMatch>> = _genreResults.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var searchJob: Job? = null

    private var movieGenres: List<TmdbGenre> = emptyList()
    private var tvGenres: List<TmdbGenre> = emptyList()
    private var genresLoaded = false

    init {
        WatchStateBus.updates
            .onEach { (key, isWatched) ->
                val current = _watchedKeys.value.toMutableSet()
                if (isWatched) {
                    current.add(key)
                } else {
                    current.remove(key)
                }
                _watchedKeys.value = current
            }
            .launchIn(viewModelScope)
    }

    fun watchedKey(id: String, type: String): String = "$type::$id"

    fun onQueryChanged(query: String) {
        _searchQuery.value = query
        search(query)
    }

    fun commitSearch(query: String = _searchQuery.value) {
        val normalized = query.trim()
        if (normalized.isBlank()) return

        _recentSearches.value = listOf(normalized)
            .plus(_recentSearches.value.filterNot { it.equals(normalized, ignoreCase = true) })
            .take(10)
    }

    fun search(query: String) {
        val normalized = query.trim()
        _searchQuery.value = query

        searchJob?.cancel()

        if (normalized.isBlank()) {
            _results.value = emptyList()
            _actorResults.value = emptyList()
            _studioResults.value = emptyList()
            _genreResults.value = emptyList()
            _isLoading.value = false
            return
        }

        searchJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                ensureGenresLoaded()

                val catalogDeferred = async { searchCatalogs(normalized) }
                val personDeferred = async {
                    runCatching { tmdbRepository.searchPerson(normalized) }
                        .onFailure { e -> Log.e("KBStream", "Person search failed", e) }
                        .getOrDefault(emptyList())
                }
                val studioDeferred = async {
                    runCatching { tmdbRepository.searchCompany(normalized) }
                        .onFailure { e -> Log.e("KBStream", "Studio search failed", e) }
                        .getOrDefault(emptyList())
                }

                val catalogResults = catalogDeferred.await()
                val personResults = personDeferred.await()
                val studioResults = studioDeferred.await()

                val genreMatches = buildList {
                    movieGenres
                        .filter { it.name.contains(normalized, ignoreCase = true) }
                        .forEach { add(TmdbGenreMatch(it.id, it.name, "movie")) }

                    tvGenres
                        .filter { it.name.contains(normalized, ignoreCase = true) }
                        .forEach { add(TmdbGenreMatch(it.id, it.name, "series")) }
                }

                _results.value = catalogResults.distinctBy { "${it.type}:${it.id}" }
                _actorResults.value = personResults.distinctBy { it.id }
                _studioResults.value = studioResults.distinctBy { it.id }
                _genreResults.value = genreMatches.distinctBy { "${it.mediaType}:${it.id}" }
            } catch (e: Exception) {
                Log.e("KBStream", "Search failed", e)
                _results.value = emptyList()
                _actorResults.value = emptyList()
                _studioResults.value = emptyList()
                _genreResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun ensureGenresLoaded() {
        if (genresLoaded) return
        try {
            movieGenres = tmdbRepository.getMovieGenres()
            tvGenres = tmdbRepository.getTvGenres()
            genresLoaded = true
        } catch (e: Exception) {
            Log.e("KBStream", "Genre list load failed", e)
        }
    }

    private suspend fun searchCatalogs(query: String): List<MetaPreview> {
        val found = mutableListOf<MetaPreview>()
        val addons = addonManager.getInstalledAddons()

        for (addon in addons) {
            if (!addon.resources.contains("catalog")) continue

            val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
            for (catalog in addon.catalogs) {
                try {
                    found += repository.searchCatalog(baseUrl, catalog.type, catalog.id, query)
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    fun onResultOpened(meta: MetaPreview) {
        commitSearch()
    }

    fun onActorOpened(person: TmdbSearchPersonResult) {
        commitSearch()
    }

    fun onStudioOpened(studio: TmdbSearchStudioResult) {
        commitSearch()
    }

    fun onGenreOpened(genre: TmdbGenreMatch) {
        commitSearch()
    }

    fun onRecentSearchClicked(query: String) {
        _searchQuery.value = query
        search(query)
        commitSearch(query)
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
    }
}
