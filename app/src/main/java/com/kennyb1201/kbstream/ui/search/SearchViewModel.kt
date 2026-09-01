package com.kennyb1201.kbstream.ui.search

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchCollectionResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchPersonResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import com.kennyb1201.kbstream.data.watched.WatchStateBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * A searchable title result. `meta` is the navigation payload the detail
 * screen consumes; year/rating are display extras resolved from the TMDB
 * search data (add-on results simply have none).
 */
data class SearchTitleResult(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val year: Int? = null,
    val rating: Double? = null,
    val meta: MetaPreview
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AddonRepository()

    private val addonManager =
        AddonManager.getInstance(application)

    private val tmdbRepository =
        TmdbRepository(application)

    private val prefs =
        application.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val _searchQuery =
        MutableStateFlow("")

    val searchQuery: StateFlow<String> =
        _searchQuery.asStateFlow()

    private val _results =
        MutableStateFlow<List<SearchTitleResult>>(
            emptyList()
        )

    val results: StateFlow<List<SearchTitleResult>> =
        _results.asStateFlow()

    private val _actorResults =
        MutableStateFlow<List<TmdbSearchPersonResult>>(
            emptyList()
        )

    val actorResults: StateFlow<List<TmdbSearchPersonResult>> =
        _actorResults.asStateFlow()

    private val _studioResults =
        MutableStateFlow<List<TmdbSearchStudioResult>>(
            emptyList()
        )

    val studioResults: StateFlow<List<TmdbSearchStudioResult>> =
        _studioResults.asStateFlow()

    private val _collectionResults =
        MutableStateFlow<List<TmdbSearchCollectionResult>>(
            emptyList()
        )

    val collectionResults: StateFlow<List<TmdbSearchCollectionResult>> =
        _collectionResults.asStateFlow()

    private val _trendingResults =
        MutableStateFlow<List<SearchTitleResult>>(
            emptyList()
        )

    val trendingResults: StateFlow<List<SearchTitleResult>> =
        _trendingResults.asStateFlow()

    private val _recentSearches =
        MutableStateFlow<List<String>>(
            emptyList()
        )

    val recentSearches: StateFlow<List<String>> =
        _recentSearches.asStateFlow()

    private val _watchedKeys =
        MutableStateFlow<Set<String>>(
            emptySet()
        )

    val watchedKeys: StateFlow<Set<String>> =
        _watchedKeys.asStateFlow()

    private val _isLoading =
        MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadRecentSearches()

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

    fun watchedKey(
        id: String,
        type: String
    ): String = "$type::$id"

    fun onQueryChanged(query: String) {
        _searchQuery.value = query
        search(query)
    }

    fun commitSearch(
        query: String = _searchQuery.value
    ) {
        val normalized = query.trim()
        if (normalized.isBlank()) return

        val updated = listOf(normalized)
            .plus(
                _recentSearches.value.filterNot {
                    it.equals(
                        normalized,
                        ignoreCase = true
                    )
                }
            )
            .take(MAX_RECENT_SEARCHES)

        _recentSearches.value = updated
        persistRecentSearches()
    }

    fun search(query: String) {
        val normalized = query.trim()
        _searchQuery.value = query

        searchJob?.cancel()

        if (normalized.isBlank()) {
            _results.value = emptyList()
            _actorResults.value = emptyList()
            _studioResults.value = emptyList()
            _collectionResults.value = emptyList()
            _isLoading.value = false
            return
        }

        searchJob = viewModelScope.launch {
            // Debounce keystrokes: only the latest query survives, and
            // TMDB/add-ons aren't hammered on every character.
            delay(SEARCH_DEBOUNCE_MS)

            _isLoading.value = true
            try {
                val tmdbMoviesDeferred = async {
                    runCatching { tmdbRepository.searchMovies(normalized) }
                        .onFailure { e ->
                            Log.e("KBStream", "Movie search failed", e)
                        }
                        .getOrDefault(emptyList())
                }
                val tmdbTvDeferred = async {
                    runCatching { tmdbRepository.searchTv(normalized) }
                        .onFailure { e ->
                            Log.e("KBStream", "TV search failed", e)
                        }
                        .getOrDefault(emptyList())
                }
                val addonTitlesDeferred = async {
                    searchAddonCatalogs(normalized)
                }
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
                val collectionDeferred = async {
                    runCatching { tmdbRepository.searchCollection(normalized) }
                        .onFailure { e -> Log.e("KBStream", "Collection search failed", e) }
                        .getOrDefault(emptyList())
                }

                val tmdbMovies = tmdbMoviesDeferred.await()
                val tmdbTv = tmdbTvDeferred.await()
                val addonTitles = addonTitlesDeferred.await()
                val personResults = personDeferred.await()
                val studioResults = studioDeferred.await()
                val collectionResults = collectionDeferred.await()

                // Cap result counts so the list stays instantly scrollable
                // even for very broad queries (e.g. one-letter searches).
                _results.value =
                    mergeTitles(
                        query = normalized,
                        movies = tmdbMovies,
                        tv = tmdbTv,
                        addonResults = addonTitles
                    )
                        .take(MAX_TITLE_RESULTS)
                _actorResults.value = personResults.distinctBy { it.id }.take(MAX_PERSON_RESULTS)
                _studioResults.value = studioResults.distinctBy { it.id }.take(MAX_STUDIO_RESULTS)
                _collectionResults.value = collectionResults.distinctBy { it.id }.take(MAX_COLLECTION_RESULTS)
            } catch (e: Exception) {
                Log.e("KBStream", "Search failed", e)
                _results.value = emptyList()
                _actorResults.value = emptyList()
                _studioResults.value = emptyList()
                _collectionResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun searchAddonCatalogs(
        query: String
    ): List<MetaPreview> {
        val found = mutableListOf<MetaPreview>()
        val addons = addonManager.getInstalledAddons()

        for (addon in addons) {
            if (!addon.resources.contains("catalog")) continue

            val baseUrl =
                addon.manifestUrl.removeSuffix("/manifest.json")

            for (catalog in addon.catalogs) {
                try {
                    found += repository.searchCatalog(
                        baseUrl,
                        catalog.type,
                        catalog.id,
                        query
                    )
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    /*
     * Merge TMDB + add-on title results. TMDB is the primary source (works
     * with no add-ons installed); add-on hits are layered on top but
     * de-duplicated against exact TMDB title/type matches so the same movie
     * from Cinemeta doesn't appear twice. Sorted by match quality, then
     * newest first.
     */
    private fun mergeTitles(
        query: String,
        movies: List<TmdbSearchTitleResult>,
        tv: List<TmdbSearchTitleResult>,
        addonResults: List<MetaPreview>
    ): List<SearchTitleResult> {

        val lowerQuery = query.trim().lowercase()

        fun matchScore(name: String?): Int {
            val n = name?.trim()?.lowercase().orEmpty()
            if (n.isEmpty()) return 0
            return when {
                n == lowerQuery -> 100
                n.startsWith(lowerQuery) -> 80
                n.contains(lowerQuery) -> 60
                else -> 0
            }
        }

        fun tmdbItem(
            result: TmdbSearchTitleResult,
            type: String,
            displayName: String
        ): SearchTitleResult {
            val id = "tmdb:${result.id}"
            val poster =
                result.posterPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { TmdbRepository.POSTER_BASE + it }
            return SearchTitleResult(
                id = id,
                type = type,
                name = displayName,
                poster = poster,
                year = (
                    result.releaseDate
                        ?: result.firstAirDate
                    )?.take(4)?.toIntOrNull(),
                rating = result.voteAverage?.takeIf { it > 0.0 },
                meta = MetaPreview(
                    id = id,
                    type = type,
                    name = displayName,
                    poster = poster
                )
            )
        }

        val tmdbItems = buildList {
            movies.forEach { result ->
                val name =
                    result.title?.takeIf { it.isNotBlank() }
                        ?: result.name?.takeIf { it.isNotBlank() }
                        ?: "Untitled"
                add(tmdbItem(result, "movie", name))
            }
            tv.forEach { result ->
                val name =
                    result.name?.takeIf { it.isNotBlank() }
                        ?: result.title?.takeIf { it.isNotBlank() }
                        ?: "Untitled"
                add(tmdbItem(result, "series", name))
            }
        }

        val tmdbKeys =
            tmdbItems
                .map { "${it.type}:${it.name.lowercase()}" }
                .toSet()

        val addonItems =
            addonResults
                .distinctBy { "${it.type}:${it.id}" }
                .filter {
                    "${it.type}:${it.name.lowercase()}" !in tmdbKeys
                }
                .map { meta ->
                    SearchTitleResult(
                        id = meta.id,
                        type = meta.type,
                        name = meta.name,
                        poster = meta.poster,
                        meta = meta
                    )
                }

        return (tmdbItems + addonItems)
            .sortedWith(
                compareByDescending<SearchTitleResult> {
                    matchScore(it.name)
                }
                    .thenByDescending { it.year ?: 0 }
                    .thenBy { it.name.lowercase() }
            )
    }

    /*
     * Loads weekly trending titles for the idle (blank query) state. The
     * repository short-caches the TMDB response, so re-entering the screen
     * doesn't refetch.
     */
    fun loadTrending() {
        if (_trendingResults.value.isNotEmpty()) return

        viewModelScope.launch {
            val raw =
                runCatching {
                    tmdbRepository.getTrendingTitles()
                }
                    .onFailure { e ->
                        Log.e("KBStream", "Trending load failed", e)
                    }
                    .getOrDefault(emptyList())

            _trendingResults.value =
                raw.mapNotNull { (type, result) ->
                    val name =
                        result.name?.takeIf { it.isNotBlank() }
                            ?: result.title?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null

                    val id = "tmdb:${result.id}"
                    val poster =
                        result.posterPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.POSTER_BASE + it }

                    SearchTitleResult(
                        id = id,
                        type = type,
                        name = name,
                        poster = poster,
                        year = (
                            result.releaseDate
                                ?: result.firstAirDate
                            )?.take(4)?.toIntOrNull(),
                        rating = result.voteAverage?.takeIf { it > 0.0 },
                        meta = MetaPreview(
                            id = id,
                            type = type,
                            name = name,
                            poster = poster
                        )
                    )
                }
                    .take(MAX_TRENDING_RESULTS)
        }
    }

    fun onResultOpened(result: SearchTitleResult) {
        commitSearch()
    }

    fun onActorOpened(person: TmdbSearchPersonResult) {
        commitSearch()
    }

    fun onStudioOpened(studio: TmdbSearchStudioResult) {
        commitSearch()
    }

    fun onCollectionOpened(collection: TmdbSearchCollectionResult) {
        commitSearch()
    }

    fun onRecentSearchClicked(query: String) {
        _searchQuery.value = query
        search(query)
        commitSearch(query)
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        persistRecentSearches()
    }

    fun resetSearchState() {
        searchJob?.cancel()
        searchJob = null
        _searchQuery.value = ""
        _results.value = emptyList()
        _actorResults.value = emptyList()
        _studioResults.value = emptyList()
        _collectionResults.value = emptyList()
        _isLoading.value = false
    }

    private fun loadRecentSearches() {
        val saved =
            prefs.getString(
                KEY_RECENT_SEARCHES,
                null
            )

        _recentSearches.value =
            saved
                ?.split(RECENT_SEARCHES_SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.take(MAX_RECENT_SEARCHES)
                .orEmpty()
    }

    private fun persistRecentSearches() {
        prefs
            .edit()
            .putString(
                KEY_RECENT_SEARCHES,
                _recentSearches.value.joinToString(
                    RECENT_SEARCHES_SEPARATOR
                )
            )
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "search_prefs"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val RECENT_SEARCHES_SEPARATOR = "\u0001"
        const val MAX_RECENT_SEARCHES = 10
        const val MAX_TITLE_RESULTS = 24
        const val MAX_PERSON_RESULTS = 12
        const val MAX_STUDIO_RESULTS = 8
        const val MAX_COLLECTION_RESULTS = 8
        const val MAX_TRENDING_RESULTS = 20
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
