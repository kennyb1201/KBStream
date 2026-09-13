package com.kennyb1201.kbstream.ui.search

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.addon.ManifestCatalog
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicReferenceArray

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
    // Display name of the add-on (e.g. "AIOMetadata"). Rendered as the
    // rail-title prefix only when "Show Addon Name on Search Rails" is on.
    val addonName: String,
    // Rail label for the catalog that produced these hits (e.g. "Movies",
    // "Series", "AI Search") — the search-catalog name, without any type.
    val railLabel: String,
    val results: List<SearchTitleResult>,
    // Catalog type ("movie" / "series" / "all" / ...) so the screen can
    // honor the "Show Catalog Type on Search Rails" toggle at render time.
    // Null for mixed rails that have no single type.
    val catalogType: String? = null
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

    // Set by MainActivity: every browse entry opens a dedicated discover
    // screen (Tag = genre/keyword, Studio = network/company/service —
    // services carry a non-null providerId for movies+series rails,
    // Collection = franchise page, Decade = per-decade page).
    var onOpenTagScreen:
        ((id: Int, name: String, isKeyword: Boolean, mediaType: String) -> Unit)? = null
    var onOpenStudioScreen:
        ((id: Int, name: String, isNetwork: Boolean, providerId: Int?) -> Unit)? = null
    var onOpenCollectionScreen:
        ((id: Int, name: String) -> Unit)? = null
    var onOpenDecadeScreen:
        ((decadeStart: Int, name: String) -> Unit)? = null

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
        resolveTmdbIds(
            items.mapNotNull { result ->
                val mediaType = normalizedType(result.type) ?: return@mapNotNull null
                val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()
                    ?: return@mapNotNull null
                if (tmdbId <= 0) null else tmdbId to mediaType
            }
        )
    }

    /** (tmdbId, mediaType) variant shared by search results and browse rails. */
    private fun resolveTmdbIds(items: List<Pair<Int, String>>) {
        if (items.isEmpty()) return

        val uniqueItems = items
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
        updateSuggestions(query)
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
            _suggestions.value = emptyList()
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

                // Add-on / AI search (AIOMetadata, BingeCat, ...) fires in
                // parallel with the TMDB wave: probes start immediately and
                // publish their own grouped section when done, so neither
                // wave delays the other.
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
            // The add-on probes start immediately now — they used to wait
            // for BOTH TMDB searches to finish before firing a single
            // request, adding ~0.5-1s to every addon rail. The de-dupe keys
            // are instead resolved lazily: each addon consults them only
            // after its own network response is back, by which time TMDB
            // has almost always finished anyway (and if not, that addon
            // was the slow one regardless).
            val tmdbKeysDeferred = async {
                buildSet {
                    tmdbMoviesDeferred.await().forEach {
                        add(tmdbNameKey("movie", it))
                    }
                    tmdbTvDeferred.await().forEach {
                        add(tmdbNameKey("series", it))
                    }
                }
            }

            val groups =
                try {
                    searchAddonCatalogs(query, tmdbKeysDeferred)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("KBStream", "Add-on search failed", e)
                    emptyList()
                }

            // Add-on catalogs often omit releaseInfo/imdbRating on their
            // preview metas (AIOStreams movies frequently ship neither), so
            // the year/rating captions never render even with the toggles
            // on. Publish the raw rails FIRST — enrichment used to gate the
            // whole section behind up to hundreds of TMDB lookups, which
            // made add-on rails feel slower than the TMDB ones — then fill
            // the gaps from TMDB (disk+memory cached, keyed by the IMDB id
            // these results already carry) and republish when done.
            _addonResultGroups.value = groups

            try {
                val enriched = enrichAddonGroups(groups)
                if (enriched != groups) {
                    _addonResultGroups.value = enriched
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("KBStream", "Add-on enrichment failed", e)
            }
        }
    }

    /**
     * Fill missing year/rating display data on add-on search results from
     * the TMDB detail cache. Only metas with a blank releaseInfo or missing
     * rating trigger a lookup; hits come back as new group copies so the
     * rails recompose. Lookups are capped so a broad query never floods the
     * TMDB API, and failures leave the result untouched.
     */
    private suspend fun enrichAddonGroups(
        groups: List<AddonResultGroup>
    ): List<AddonResultGroup> = coroutineScope {
        val jobs = groups.mapIndexed { groupIndex, group ->
            async {
                val results = group.results
                // Collect which indices actually need enrichment.
                val needed = results.withIndex()
                    .filter { (_, r) ->
                        r.year == null || r.rating == null
                    }
                    .take(MAX_ENRICH_PER_GROUP)
                if (needed.isEmpty()) return@async groupIndex to group

                val enriched = results.toMutableList()
                needed.forEach { (index, result) ->
                    val imdbId = result.meta.id.takeIf { it.startsWith("tt") }
                        ?: return@forEach
                    val type = normalizedType(result.type) ?: return@forEach
                    val detail = runCatching {
                        tmdbRepository.fetchEnrichedMetaCached(imdbId, type)
                    }.getOrNull() ?: return@forEach
                    val year = result.year
                        ?: detail.releaseDate?.take(4)?.toIntOrNull()
                        ?: detail.firstAirDate?.take(4)?.toIntOrNull()
                    val rating = result.rating
                        ?: detail.voteAverage?.takeIf { it > 0.0 }
                    if (year != result.year || rating != result.rating) {
                        enriched[index] = result.copy(year = year, rating = rating)
                    }
                }
                groupIndex to group.copy(results = enriched)
            }
        }
        jobs.awaitAll().sortedBy { (index, _) -> index }
            .map { (_, group) -> group }
    }

    private suspend fun searchAddonCatalogs(
        query: String,
        tmdbKeysDeferred: Deferred<Set<String>>
    ): List<AddonResultGroup> = coroutineScope {
        val addons = addonManager.getInstalledAddons()

        // Every installed add-on is probed in parallel, and each add-on's
        // rails are published the moment that add-on answers (install order
        // preserved via indexed slots) instead of waiting for the slowest
        // one. A slow AI add-on therefore delays only its own rails, never
        // the whole add-on section. MAX_ADDON_GROUPS still caps the total,
        // and MAX_ADDON_GROUPS_PER_ADDON stops a catalog-heavy add-on from
        // crowding the others out of the cap.
        val slots = AtomicReferenceArray<List<AddonResultGroup>>(addons.size)

        addons.mapIndexed { index, addon ->
            launch {
                val groups = try {
                    collectAddonSearchGroup(addon, query, tmdbKeysDeferred)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }.take(MAX_ADDON_GROUPS_PER_ADDON)

                slots.set(index, groups)

                val snapshot = (0 until slots.length())
                    .mapNotNull { slots.get(it) }
                    .flatten()
                    .take(MAX_ADDON_GROUPS)
                if (snapshot.isNotEmpty()) {
                    _addonResultGroups.value = snapshot
                }
            }
        }.joinAll()

        return@coroutineScope (0 until slots.length())
            .mapNotNull { slots.get(it) }
            .flatten()
            .take(MAX_ADDON_GROUPS)
    }

    /**
     * Query one add-on for [query] and build its result rail(s).
     * The /search endpoint is probed first for addons declaring the search
     * resource; when it comes back empty (AIOMetadata, addons that don't
     * serve type "*"), every searchable catalog is probed separately so
     * rails like "AI Search" always render. Returns an empty list when the
     * add-on yields no rails so it drops out just like the old loop's
     * `continue`.
     */
    private suspend fun collectAddonSearchGroup(
        addon: InstalledAddon,
        query: String,
        tmdbKeysDeferred: Deferred<Set<String>>
    ): List<AddonResultGroup> = coroutineScope {
        val groups = mutableListOf<AddonResultGroup>()
        val baseUrl =
            addon.manifestUrl.removeSuffix("/manifest.json")

        val collected = mutableListOf<MetaPreview>()

        // Standard search resource (Cinemeta, ...): probe the /search
        // endpoint first. If it returns nothing — addons sometimes declare
        // the resource but don't actually serve type "*", and AIOMetadata
        // ships both the resource and per-catalog search catalogs — fall
        // through to the catalog probe instead of giving up on the addon,
        // which is exactly what made the "AI Search" rail disappear.
        if (addon.resources.contains("search")) {
            try {
                collected += repository.search(
                    baseUrl,
                    "*",
                    query
                )
            } catch (_: Exception) {
            }
        }

        if (collected.isEmpty()) {
            // Catalog-only addons (AIOMetadata, BingeCat, ...) implement
            // search through the catalog endpoint's `search=` extra — the
            // same endpoint the Discover rails use. AIOMetadata exposes
            // every enabled search as its own catalog (Movies, Series,
            // AI Search, People, Collections), so probe each catalog
            // separately and keep one rail per catalog instead of
            // merging everything into a single undifferentiated rail.
            // AI/search catalogs (AIOMetadata "AI Search", BingeCat's
            // search lists, People/Collections searches) are probed
            // FIRST so they can never be pushed out of the probe cap by
            // the regular movie/series catalogs, and their results are
            // exempt from the cross-rail dedup below — overlapping
            // titles is expected there, and an emptied rail is exactly
            // the "my AI search rail disappeared" bug.
            val searchableCatalogs = addon.catalogs
                .partition { it.isSearchStyleCatalog() }
                .let { (searchStyle, regular) -> searchStyle + regular }
                .take(MAX_ADDON_CATALOG_PROBES)

            if (searchableCatalogs.isNotEmpty()) {
                val perCatalog = coroutineScope {
                    searchableCatalogs.map { catalog ->
                        async {
                            catalog to runCatching {
                                repository.searchCatalog(
                                    baseUrl,
                                    catalog.type,
                                    catalog.id,
                                    query
                                )
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }

                // Regular catalogs: a title found by an earlier catalog (or
                // already present in the TMDB rails) is not repeated in the
                // same addon's later regular rails.
                val seen = mutableSetOf<String>()
                val tmdbKeys = tmdbKeysDeferred.await()
                for ((catalog, hits) in perCatalog) {
                    val searchStyle = catalog.isSearchStyleCatalog()
                    // A search that returns the SAME list as the catalog's
                    // unfiltered browse view means the catalog ignored the
                    // query — e.g. AIOStreams "Top 10" lists always echo
                    // their ranking regardless of the search term. Those
                    // hits are noise, not search matches, so the rail is
                    // dropped.
                    if (!searchStyle && catalogIgnoredQuery(baseUrl, catalog, hits)) continue
                    val items = hits
                        .asSequence()
                        .distinctBy { "${it.type}:${it.id}" }
                        .filter { meta ->
                            searchStyle ||
                                "${meta.type}:${meta.name.lowercase()}" !in tmdbKeys
                        }
                        .filter { meta ->
                            // Search-style rails never enter or consult the
                            // dedup set: they must always render.
                            searchStyle || seen.add("${meta.type}:${meta.id}")
                        }
                        .map { meta ->
                            SearchTitleResult(
                                id = meta.id,
                                type = meta.type,
                                name = meta.name,
                                poster = meta.poster,
                                year = meta.yearOrNull,
                                rating = meta.imdbRating?.toDoubleOrNull()
                                    ?.takeIf { it > 0.0 },
                                meta = meta
                            )
                        }
                        .take(MAX_ADDON_CATALOG_RESULTS)
                        .toList()
                    if (items.isEmpty()) continue
                    groups += AddonResultGroup(
                        addonName = addon.displayName,
                        railLabel = catalog.railLabel(addon.displayName),
                        results = items,
                        catalogType = catalog.type
                    )
                }
            }

            return@coroutineScope groups
        }

        // Await the de-dupe keys once, before filtering.
        val tmdbKeys = tmdbKeysDeferred.await()

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
                        year = meta.yearOrNull,
                        rating = meta.imdbRating?.toDoubleOrNull()
                            ?.takeIf { it > 0.0 },
                        meta = meta
                    )
                }
                    .take(MAX_ADDON_RESULTS_PER_ADDON)

        if (addonItems.isEmpty()) return@coroutineScope emptyList<AddonResultGroup>()

        groups += AddonResultGroup(
            // Standard search mixes movies + series in one rail.
            addonName = addon.displayName,
            railLabel = "All",
            results = addonItems,
            catalogType = null
        )

        return@coroutineScope groups
    }

    /**
     * Heuristic for catalogs that answer every request with their browse
     * list (Top-10/ranking lists): if a catalog's "search" response equals
     * its unfiltered response, the query was ignored and the rail is noise.
     * Cached per catalog so the probe runs at most once per session.
     */
    private val ignoredQueryCache =
        mutableMapOf<String, Boolean>()

    private suspend fun catalogIgnoredQuery(
        baseUrl: String,
        catalog: ManifestCatalog,
        hits: List<MetaPreview>
    ): Boolean {
        // Only worth probing when the catalog declares a rank-style id/name
        // (top10, trending, popular, ...) — the probe costs one extra HTTP
        // call and normal catalogs must never pay it.
        if (!catalog.isRankStyleCatalog()) return false
        val key = "${baseUrl}|${catalog.type}:${catalog.id}"
        synchronized(ignoredQueryCache) {
            ignoredQueryCache[key]?.let { return it }
        }
        val unfiltered = runCatching {
            repository.getCatalog(
                baseUrl = baseUrl,
                type = catalog.type,
                catalogId = catalog.id
            )
        }.getOrNull()
        val ignored = unfiltered != null &&
            unfiltered.map { "${it.type}:${it.id}" } ==
            hits.map { "${it.type}:${it.id}" }
        synchronized(ignoredQueryCache) {
            ignoredQueryCache[key] = ignored
        }
        return ignored
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

    // ------------------------------------------------------------------
    // Keyboard suggestions
    // ------------------------------------------------------------------

    private val _suggestions =
        MutableStateFlow<List<String>>(emptyList())

    val suggestions: StateFlow<List<String>> =
        _suggestions.asStateFlow()

    /**
     * Suggestion chips under the query field, best-first: recent searches
     * completing the prefix, then live result names from the streaming
     * search, then the curated keyword names. Capped so the row stays one
     * scroll-free line on TV.
     */
    private fun updateSuggestions(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        val lower = q.lowercase()

        val recents = _recentSearches.value.filter {
            it.lowercase().startsWith(lower) && !it.equals(q, ignoreCase = true)
        }
        val titles = _results.value
            .map { it.name }
            .filter {
                it.isNotBlank() &&
                    it.lowercase().startsWith(lower) &&
                    !it.equals(q, ignoreCase = true)
            }
            .distinctBy { it.lowercase() }
        val keywords = BROWSE_KEYWORD_NAMES
            .filter { it.startsWith(lower) && !it.equals(q, ignoreCase = true) }
            .map { it.replaceFirstChar { c -> c.uppercase() } }

        _suggestions.value = (recents + titles + keywords)
            .distinctBy { it.lowercase() }
            .take(MAX_SUGGESTIONS)
    }

    /** A suggestion chip press runs the full search and records it as recent. */
    fun onSuggestionClicked(suggestion: String) {
        _searchQuery.value = suggestion
        _suggestions.value = emptyList()
        search(suggestion)
        commitSearch(suggestion)
    }

    // ------------------------------------------------------------------
    // Search browse browser (blank-query / no-results browsing). Every
    // entry opens a dedicated discover screen; the browser itself only
    // shows the category strip and submenu chips.
    // ------------------------------------------------------------------

    private val _browseCategories =
        MutableStateFlow(BROWSE_CATEGORIES)

    val browseCategories: StateFlow<List<BrowseCategory>> =
        _browseCategories.asStateFlow()

    private val _browseSubmenuLoading =
        MutableStateFlow(false)

    val browseSubmenuLoading: StateFlow<Boolean> =
        _browseSubmenuLoading.asStateFlow()

    private var catalogResolveStarted = false

    /**
     * Sidebar selection: swap the submenu. Keyword/collection entries are
     * runtime-resolved ids, so picking those categories kicks the (cached,
     * once-per-session) resolution off.
     */
    fun selectBrowseCategory(key: String) {
        if (key == "keywords" || key == "collections") {
            _browseSubmenuLoading.value = !catalogResolveStarted
            if (!catalogResolveStarted) {
                viewModelScope.launch { resolveCatalogEntries() }
            }
        } else {
            _browseSubmenuLoading.value = false
        }
    }

    /** A submenu entry press: open its dedicated discover screen. */
    fun onBrowseEntryClicked(categoryKey: String, entry: BrowseEntry) {
        when (categoryKey) {
            "genres" -> onOpenTagScreen?.invoke(entry.id, entry.name, false, "movie")
            "keywords" -> onOpenTagScreen?.invoke(entry.id, entry.name, true, "movie")
            // Services carry their watch-provider id so their screen runs
            // movies + series rails; plain network entries have none and
            // keep the old network page. isNetwork follows the id space
            // (network unless the entry ids a company) so the header's
            // logo/detail lookups hit the right TMDB endpoint.
            "services" -> onOpenStudioScreen?.invoke(
                entry.id,
                entry.name,
                !entry.networkIsCompany,
                entry.providerId
            )
            "studios" -> onOpenStudioScreen?.invoke(entry.id, entry.name, false, null)
            "collections" -> onOpenCollectionScreen?.invoke(entry.id, entry.name)
            "decades" -> onOpenDecadeScreen?.invoke(entry.id, entry.name)
        }
    }

    /**
     * Resolves the runtime-id submenus once per session: keyword names via
     * /search/keyword, collection names via /search/collection. Hand-picked
     * ids would rot; a name lookup always returns TMDB's canonical id.
     */
    private suspend fun resolveCatalogEntries() {
        if (catalogResolveStarted) return
        catalogResolveStarted = true

        val keywordEntries = coroutineScope {
            BROWSE_KEYWORD_NAMES.map { name ->
                async {
                    runCatching {
                        tmdbRepository.searchKeywords(name)
                            .firstOrNull { it.name.equals(name, ignoreCase = true) }
                            ?.let { BrowseEntry(it.id, name) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val collectionEntries = coroutineScope {
            BROWSE_COLLECTION_NAMES.map { name ->
                async {
                    runCatching {
                        tmdbRepository.searchCollection(name)
                            .firstOrNull()
                            ?.let { BrowseEntry(it.id, name) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        _browseCategories.value = BROWSE_CATEGORIES.map { category ->
            when (category.key) {
                "keywords" -> category.copy(entries = keywordEntries)
                "collections" -> category.copy(entries = collectionEntries)
                else -> category
            }
        }
        _browseSubmenuLoading.value = false
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
        _suggestions.value = emptyList()
        _isLoading.value = false
    }

    /**
     * Called when the user leaves the Search screen (Back from Search).
     * Commits the current query to recent-search history (so it comes back
     * as a one-tap chip) and clears the session state, so re-entering
     * Search starts fresh instead of restoring stale results.
     */
    fun exitSearch() {
        commitSearch()
        resetSearchState()
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
        const val MAX_ADDON_GROUPS = 10

        /**
         * True for AIOMetadata "AI Search" / "People Search" /
         * "Collections Search"-style catalogs (matched on the catalog id or
         * name containing "search").
         */
        fun ManifestCatalog.isSearchStyleCatalog(): Boolean {
            val hay = "$id $name"
            return hay.contains("search", ignoreCase = true)
        }

        /**
         * True for rank/browse-style catalogs ("Top 10", "Trending",
         * "Popular", ...) whose content does not depend on a search query.
         * Used to skip the ignored-query probe's extra HTTP call on normal
         * catalogs, and as a fast pre-filter for dropping rank-list rails
         * that echo the same list for every query.
         */
        fun ManifestCatalog.isRankStyleCatalog(): Boolean {
            val hay = "$id $name"
            // "top" as a substring also covers "top10" / "top-10" variants.
            return listOf("top", "trending", "popular").any {
                hay.contains(it, ignoreCase = true)
            }
        }

        /**
         * Rail label for a catalog: search-style catalogs keep their own
         * (addon-prefix-stripped) name, e.g. "AI Search"; regular catalogs
         * get a plural type label ("Movies" / "Series" / "All" / ...).
         */
        fun ManifestCatalog.railLabel(addonName: String): String {
            val raw = customName?.trim()?.takeIf { it.isNotEmpty() } ?: name
            if (isSearchStyleCatalog()) {
                return raw.removePrefix(addonName).trim()
                    .ifEmpty { raw }
            }
            return when (type.lowercase()) {
                "movie" -> "Movies"
                "series" -> "Series"
                "all" -> "All"
                else -> when {
                    type.contains("person", true) || type.contains("people", true) -> "People"
                    type.contains("collection", true) -> "Collections"
                    else -> raw
                }
            }
        }

        /** Catalog probes per catalog-only addon when it has no search resource. */
        const val MAX_ADDON_CATALOG_PROBES = 12

        /** Rails per add-on, so one catalog-heavy addon can't crowd the rest. */
        const val MAX_ADDON_GROUPS_PER_ADDON = 5

        /** Per-rail cap on TMDB enrichments for missing year/rating. */
        const val MAX_ENRICH_PER_GROUP = 20

        /** Per-rail cap for a catalog-only addon's per-catalog search rail. */
        const val MAX_ADDON_CATALOG_RESULTS = 20
        const val MAX_SUGGESTIONS = 6
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
