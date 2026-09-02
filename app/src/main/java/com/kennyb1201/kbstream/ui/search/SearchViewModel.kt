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
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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

/**
 * Search hits from one installed add-on (AIOMetadata, BingeCat, ...),
 * grouped so the search screen can show them as a labelled source rail.
 */
data class AddonResultGroup(
    val addonName: String,
    val results: List<SearchTitleResult>
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AddonRepository()

    private val addonManager =
        AddonManager.getInstance(application)

    private val tmdbRepository =
        TmdbRepository(application)

    private val watchedStatusRepository =
        WatchedStatusRepository(application)

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

    // TMDB id -> IMDB id resolutions for the visible title results, filled
    // asynchronously after each search/trending load so poster badges and the
    // long-press "Mark as Watched" action can key off the IMDB id.
    private val _resolvedIds =
        MutableStateFlow<Map<String, String>>(
            emptyMap()
        )

    val resolvedIds: StateFlow<Map<String, String>> =
        _resolvedIds.asStateFlow()


    private val _isLoading =
        MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private val _addonResultGroups =
        MutableStateFlow<List<AddonResultGroup>>(
            emptyList()
        )

    val addonResultGroups: StateFlow<List<AddonResultGroup>> =
        _addonResultGroups.asStateFlow()

    private var searchJob: Job? = null

    private var addonSearchJob: Job? = null

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

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    private fun normalizedType(mediaType: String?): String? =
        when (mediaType?.lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "series"
            else -> null
        }

    /**
     * Resolves the TMDB ids of the visible title tiles to IMDB in the
     * background (cached by the repository), so watched badges and the
     * long-press "Mark as Watched" action can key off the IMDB id.
     */
    private fun resolveTmdbTitles(items: List<SearchTitleResult>) {
        if (items.isEmpty()) return

        val uniqueItems = items
            .mapNotNull { result ->
                val normalizedType = normalizedType(result.type) ?: return@mapNotNull null
                val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull() ?: return@mapNotNull null
                if (tmdbId <= 0) null else tmdbId to normalizedType
            }
            .filterNot { (tmdbId, mediaType) ->
                _resolvedIds.value.containsKey(lookupKey(tmdbId, mediaType))
            }
            .distinct()
            .take(MAX_RESOLUTION_BATCH)

        if (uniqueItems.isEmpty()) return

        viewModelScope.launch {
            val resolvedTriples = supervisorScope {
                uniqueItems.map { (tmdbId, mediaType) ->
                    async {
                        val imdbId = runCatching {
                            tmdbRepository.resolveImdbId(tmdbId, mediaType)
                        }.getOrNull()
                        Triple(tmdbId, mediaType, imdbId)
                    }
                }.map { it.await() }
            }

            val resolved = resolvedTriples.filter { (_, _, imdbId) ->
                !imdbId.isNullOrBlank()
            }

            if (resolved.isEmpty()) return@launch

            _resolvedIds.value = _resolvedIds.value + resolved.associate {
                (tmdbId, mediaType, imdbId) ->
                lookupKey(tmdbId, mediaType) to imdbId!!
            }
        }
    }

    /**
     * Long-press "Mark as Watched" on a search/trending tile: records the
     * persistent local watched override (mirrored to SIMKL when connected).
     * TMDB-keyed results resolve to an IMDB id first; add-on results are
     * already keyed by their IMDB id and are marked directly.
     */
    fun markAsWatched(result: SearchTitleResult) {
        viewModelScope.launch {
            val normalizedType = normalizedType(result.type) ?: return@launch
            val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()

            val imdbId = if (tmdbId != null) {
                val lookup = lookupKey(tmdbId, normalizedType)
                val resolved = _resolvedIds.value[lookup]
                    ?: runCatching {
                        tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                    }.getOrNull()

                if (resolved != null && _resolvedIds.value[lookup] == null) {
                    _resolvedIds.value = _resolvedIds.value + (lookup to resolved)
                }
                resolved
            } else {
                result.id.trim().takeIf { it.isNotBlank() }
            } ?: return@launch

            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("SEARCH_WATCHED", "markAsWatched failed id=${result.id}", e)
            }
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a search/trending tile: removes the
     * persistent local watched override (mirrored as a Simkl history delete
     * when connected). TMDB-keyed results resolve to an IMDB id first;
     * add-on results are already keyed by their IMDB id and are unmarked
     * directly.
     */
    fun markUnwatched(result: SearchTitleResult) {
        viewModelScope.launch {
            val normalizedType = normalizedType(result.type) ?: return@launch
            val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()

            val imdbId = if (tmdbId != null) {
                val lookup = lookupKey(tmdbId, normalizedType)
                val resolved = _resolvedIds.value[lookup]
                    ?: runCatching {
                        tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                    }.getOrNull()

                if (resolved != null && _resolvedIds.value[lookup] == null) {
                    _resolvedIds.value = _resolvedIds.value + (lookup to resolved)
                }
                resolved
            } else {
                result.id.trim().takeIf { it.isNotBlank() }
            } ?: return@launch

            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("SEARCH_WATCHED", "markUnwatched failed id=${result.id}", e)
            }
        }
    }

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
        addonSearchJob?.cancel()
        _addonResultGroups.value = emptyList()

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

                // Add-on / AI search (AIOMetadata, BingeCat, ...) runs as a
                // second wave: it awaits the same TMDB lookups for de-dupe
                // keys and publishes its own grouped section when done, so it
                // never delays the TMDB results above.
                launchAddonSearch(
                    normalized,
                    tmdbMoviesDeferred,
                    tmdbTvDeferred
                )

                val tmdbMovies = tmdbMoviesDeferred.await()
                val tmdbTv = tmdbTvDeferred.await()
                val personResults = personDeferred.await()
                val studioResults = studioDeferred.await()
                val collectionResults = collectionDeferred.await()

                // Cap result counts so the list stays instantly scrollable
                // even for very broad queries (e.g. one-letter searches).
                _results.value =
                    mergeTitles(
                        query = normalized,
                        movies = tmdbMovies,
                        tv = tmdbTv
                    )
                        .take(MAX_TITLE_RESULTS)
                _actorResults.value = personResults.distinctBy { it.id }.take(MAX_PERSON_RESULTS)
                _studioResults.value = studioResults.distinctBy { it.id }.take(MAX_STUDIO_RESULTS)
                _collectionResults.value = collectionResults.distinctBy { it.id }.take(MAX_COLLECTION_RESULTS)

                // Resolve the visible TMDB ids -> IMDB in the background so
                // badges/marks can key off the IMDB id.
                resolveTmdbTitles(_results.value)
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

    /**
     * Second wave of a search: query every catalog add-on (AIOMetadata's AI /
     * meta catalogs, BingeCat lists, Cinemeta, ...) via the standard Stremio
     * search endpoint and publish the hits grouped by add-on so the search
     * screen can show them as their own labelled rails. Runs after — and
     * never delays — the TMDB wave.
     */
    private fun launchAddonSearch(
        query: String,
        tmdbMoviesDeferred: Deferred<List<TmdbSearchTitleResult>>,
        tmdbTvDeferred: Deferred<List<TmdbSearchTitleResult>>
    ) {
        addonSearchJob?.cancel()
        addonSearchJob = viewModelScope.launch {
            val tmdbKeys = buildSet {
                tmdbMoviesDeferred.await().forEach {
                    add(tmdbNameKey("movie", it))
                }
                tmdbTvDeferred.await().forEach {
                    add(tmdbNameKey("series", it))
                }
            }

            val groups =
                try {
                    searchAddonCatalogs(query, tmdbKeys)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("KBStream", "Add-on search failed", e)
                    emptyList()
                }

            _addonResultGroups.value = groups
        }
    }

    private suspend fun searchAddonCatalogs(
        query: String,
        tmdbKeys: Set<String>
    ): List<AddonResultGroup> {
        val groups = mutableListOf<AddonResultGroup>()
        val addons = addonManager.getInstalledAddons()

        for (addon in addons) {
            if (!addon.resources.contains("catalog")) continue

            val baseUrl =
                addon.manifestUrl.removeSuffix("/manifest.json")

            val collected = mutableListOf<MetaPreview>()

            for (catalog in addon.catalogs) {
                try {
                    collected += repository.searchCatalog(
                        baseUrl,
                        catalog.type,
                        catalog.id,
                        query
                    )
                } catch (_: Exception) {
                }
            }

            if (collected.isEmpty()) continue

            val addonItems =
                collected
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
                    .take(MAX_ADDON_RESULTS_PER_ADDON)

            if (addonItems.isEmpty()) continue

            groups += AddonResultGroup(
                addonName = addon.customName ?: addon.name,
                results = addonItems
            )

            if (groups.size >= MAX_ADDON_GROUPS) break
        }

        return groups
    }

    private fun tmdbNameKey(
        type: String,
        result: TmdbSearchTitleResult
    ): String {
        val name =
            if (type == "movie") {
                result.title?.takeIf { it.isNotBlank() }
                    ?: result.name?.takeIf { it.isNotBlank() }
                    ?: "Untitled"
            } else {
                result.name?.takeIf { it.isNotBlank() }
                    ?: result.title?.takeIf { it.isNotBlank() }
                    ?: "Untitled"
            }
        return "$type:${name.lowercase()}"
    }

    /*
     * Merge TMDB movie + series results into one ranked title list, sorted
     * by match quality, then newest first. Add-on/AI results are published
     * separately (see launchAddonSearch) so they keep their source label.
     */
    private fun mergeTitles(
        query: String,
        movies: List<TmdbSearchTitleResult>,
        tv: List<TmdbSearchTitleResult>
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

        return tmdbItems
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

            resolveTmdbTitles(_trendingResults.value)
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
        addonSearchJob?.cancel()
        addonSearchJob = null
        _searchQuery.value = ""
        _results.value = emptyList()
        _addonResultGroups.value = emptyList()
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
        const val MAX_RESOLUTION_BATCH = 300
        const val MAX_RECENT_SEARCHES = 10
        const val MAX_TITLE_RESULTS = 24
        const val MAX_PERSON_RESULTS = 12
        const val MAX_STUDIO_RESULTS = 8
        const val MAX_COLLECTION_RESULTS = 8

        const val MAX_ADDON_RESULTS_PER_ADDON = 40
        const val MAX_ADDON_GROUPS = 6
        const val MAX_TRENDING_RESULTS = 20
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
