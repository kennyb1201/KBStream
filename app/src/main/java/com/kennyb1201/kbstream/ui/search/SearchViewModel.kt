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
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
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

class SearchViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repository = AddonRepository.getInstance()

    private val addonManager =
        AddonManager.getInstance(app)

    private val tmdbRepository =
        TmdbRepository.getInstance(app)

    private val watchedStatusRepository =
        WatchedStatusRepository(app)

    // Recent searches are per-profile (search history is part of a
    // profile's viewing footprint - same isolation as watch history).
    private val prefs
        get() = app.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                app, PREFS_NAME
            ),
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

    /*
     * Keys of shows started-but-not-finished (the eye badge). Filled by the
     * same preloads that fill watchedKeys; the completed checkmark wins when
     * a key is in both sets.
     */
    private val _partialWatchedKeys =
        MutableStateFlow<Set<String>>(
            emptySet()
        )

    val partialWatchedKeys: StateFlow<Set<String>> =
        _partialWatchedKeys.asStateFlow()

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
        ((id: Int, name: String, isNetwork: Boolean, providerId: Int?, networkOrCompanyId: Int?, networkIsCompany: Boolean, originalsCompanyId: Int?) -> Unit)? = null
    var onOpenCollectionScreen:
        ((id: Int, name: String) -> Unit)? = null
    var onOpenDecadeScreen:
        ((decadeStart: Int, name: String) -> Unit)? = null

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

            // Fill the watched checkmark + eye badge for the freshly
            // resolved tiles from the same cached Simkl state every other
            // screen preloads.
            preloadWatchedKeysFor(
                resolved
                    .map { (_, mediaType, imdbId) -> imdbId!! to mediaType }
                    .distinct()
            )
        }
    }

    /**
     * Preload the watched + partial (eye) state for a batch of already-
     * keyed IMDB items and merge both key sets into the exposed state.
     * Reads the repository's shared memory/disk caches, so repeat calls
     * (every search wave) cost no extra network traffic.
     */
    private fun preloadWatchedKeysFor(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return

        viewModelScope.launch {
            try {
                val watched =
                    watchedStatusRepository.preloadAndGetWatchedKeys(items)

                val partial =
                    watchedStatusRepository
                        .preloadAndGetPartiallyWatchedKeys(items) -
                        watched

                _watchedKeys.value = _watchedKeys.value + watched
                _partialWatchedKeys.value =
                    (_partialWatchedKeys.value + partial) - watched
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SEARCH_WATCHED", "watched preload failed: ${e.message}", e)
            }
        }
    }

    /** Simkl is signed in, so long-press adds will mirror there too. */
    fun simklConnectedForLibrary(): Boolean =
        LibraryMirror.simklConnected(getApplication())

    /** MDBList API key is set, so long-press adds will mirror there too. */
    fun mdbListConnectedForLibrary(): Boolean =
        LibraryMirror.mdbListConnected(getApplication())

    /**
     * True when the title is already on this profile's local My List.
     * Accepts either id form (imdb or tmdb) so the check matches however
     * the entry was saved.
     */
    fun isInLocalLibrary(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val appContext = getApplication<Application>()
        return (tmdbId != null &&
            LocalLibraryStore.isInMyList(appContext, mediaType, null, tmdbId)) ||
            (imdbId != null &&
                LocalLibraryStore.isInMyList(appContext, mediaType, imdbId, null))
    }

    /**
     * Long-press "Add to Library" on a search/trending tile: resolves the
     * TMDB id to its IMDB form when needed, saves to this profile's local
     * My List, then mirrors to the Simkl and/or MDBList watchlists when
     * connected (best-effort; local write always wins).
     */
    fun addToLibrary(result: SearchTitleResult) {
        viewModelScope.launch {
            val normalizedType = normalizedType(result.type) ?: return@launch
            val tmdbId = result.id.removePrefix("tmdb:").toIntOrNull()

            val imdbId: String? = if (tmdbId != null) {
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
            }

            LibraryMirror.addToLibrary(
                context = getApplication(),
                scope = viewModelScope,
                mediaType = normalizedType,
                imdbId = imdbId,
                tmdbId = tmdbId,
                title = result.name,
                year = result.year,
                posterUrl = result.poster
            )
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
                // Kids Mode additionally certification-checks every title
                // before it renders (search endpoints carry no rating),
                // reusing the shared detail cache so repeat searches are
                // cheap. The cap runs AFTER filtering so a strict profile
                // still sees a full page of allowed hits.
                val mergedTitles = mergeTitles(
                    query = normalized,
                    movies = tmdbMovies,
                    tv = tmdbTv
                )
                _results.value =
                    if (tmdbRepository.kidsMaxAge() == null) {
                        mergedTitles.take(MAX_TITLE_RESULTS)
                    } else {
                        tmdbRepository.kidsFilterMetas(mergedTitles.map { it.meta })
                            .toSet()
                            .let { allowed -> mergedTitles.filter { it.meta in allowed } }
                            .take(MAX_TITLE_RESULTS)
                    }
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
        // Kids Mode suppresses add-on rails entirely: third-party catalogs
        // (AIOMetadata, AIOStreams, ...) carry no certification data, so
        // their rails cannot be vetted against the profile's ceiling.
        if (tmdbRepository.kidsMaxAge() != null) {
            _addonResultGroups.value = emptyList()
            return
        }
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

            // Add-on results already carry IMDB ids: preload their watched /
            // eye-badge state so those rails read like every other screen.
            preloadWatchedKeysFor(
                groups
                    .flatMap { it.results }
                    .mapNotNull { r ->
                        val type = normalizedType(r.type) ?: return@mapNotNull null
                        val imdbId = r.meta.id.takeIf { it.startsWith("tt") }
                            ?: return@mapNotNull null
                        imdbId to type
                    }
                    .distinct()
            )

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
        val addons = addonManager.getEnabledAddons()

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
        val keywords = activeKeywordNames()
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

    /**
     * Browse chips this profile hid from the chip long-press menu, keyed by
     * [BrowseChipVisibility.key] ("category\u0001name").
     */
    private val _hiddenBrowseChips =
        MutableStateFlow<Set<String>>(emptySet())

    val hiddenBrowseChips: StateFlow<Set<String>> =
        _hiddenBrowseChips.asStateFlow()

    /**
     * The unfiltered sidebar as last built, so hiding or unhiding a chip
     * republishes instantly instead of re-resolving keywords and collections.
     */
    private var rawBrowseCategories: List<BrowseCategory> = BROWSE_CATEGORIES

    private fun loadHiddenBrowseChips() {
        _hiddenBrowseChips.value = BrowseChipVisibility.hiddenKeys(app)
    }

    /**
     * The single publish point for the sidebar. Every path that builds
     * categories goes through here: the raw list is remembered first (so
     * [hideBrowseChip] / [unhideAllBrowseChips] can republish it) and the
     * chips this profile hid are then dropped. Filtering here rather than
     * inside [baseBrowseCategories] keeps the raw list intact, so unhiding a
     * chip needs no second TMDB resolve or disk-cache read.
     */
    private fun publishBrowseCategories(categories: List<BrowseCategory>) {
        rawBrowseCategories = categories
        val hidden = _hiddenBrowseChips.value
        _browseCategories.value =
            if (hidden.isEmpty()) {
                categories
            } else {
                categories.map { category ->
                    category.copy(
                        entries = category.entries.filterNot { entry ->
                            BrowseChipVisibility.key(category.key, entry.name) in hidden
                        }
                    )
                }
            }
    }

    /** Long-press "Hide" on a browse chip. */
    fun hideBrowseChip(categoryKey: String, entry: BrowseEntry) {
        if (entry.name.isBlank()) return
        _hiddenBrowseChips.value =
            BrowseChipVisibility.hide(app, categoryKey, entry.name)
        publishBrowseCategories(rawBrowseCategories)
        // The chip that opened a discover screen may no longer exist, so an
        // armed return chip would focus nothing on the way back.
        browseReturnChip = null
    }

    /** Long-press on a category chip: bring back everything hidden in it. */
    fun unhideAllBrowseChips(categoryKey: String) {
        _hiddenBrowseChips.value =
            BrowseChipVisibility.unhideAll(app, categoryKey)
        publishBrowseCategories(rawBrowseCategories)
    }

    /**
     * True while the ACTIVE profile is a kids profile (kidsMaxAge set).
     * Drives the kid-focused browse chips, keyword suggestions, search
     * certification filtering, and add-on rail suppression.
     */
    @Volatile
    var isKidsMode: Boolean = false
        private set

    /** Chip name lists for the current mode (the resolver unions both). */
    private fun activeKeywordNames(): List<String> =
        if (isKidsMode) KIDS_KEYWORD_NAMES else BROWSE_KEYWORD_NAMES

    private fun activeCollectionNames(): List<String> =
        if (isKidsMode) {
            KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA
        } else {
            BROWSE_COLLECTION_NAMES
        }

    private fun baseBrowseCategories(): List<BrowseCategory> =
        if (isKidsMode) kidsBrowseCategories() else BROWSE_CATEGORIES

    /**
     * Kids sidebar with the overflow additions merged in (see
     * [KIDS_SERVICES_EXTRA] / [KIDS_STUDIOS_EXTRA]). The main catalog file
     * has grown past the size the editor rewrites in one pass, so new kids
     * services/studios live in SearchBrowseCatalogExtras and are appended
     * here; appending keeps the popular-first ordering the main list already
     * applied.
     */
    private fun kidsBrowseCategories(): List<BrowseCategory> =
        KIDS_BROWSE_CATEGORIES.map { category ->
            when (category.key) {
                "services" -> category.copy(
                    entries = category.entries + KIDS_SERVICES_EXTRA_ENTRIES
                )
                "studios" -> category.copy(
                    entries = category.entries + KIDS_STUDIOS_EXTRA
                )
                else -> category
            }
        }

    /**
     * Re-evaluate kids mode after a profile (or its kids setting) changed:
     * swap the browse chip base and re-apply the profile-scoped disk cache
     * onto it. Cached keyword/collection ids are keyed by NAME and one
     * resolve pass covers the union of both modes' lists, so a mode flip
     * never needs a second resolve pass.
     */
    private fun refreshKidsMode() {
        val kids = com.kennyb1201.kbstream.data.sync.ProfileManager
            .activeProfile.value?.kidsMaxAge != null
        if (kids == isKidsMode) return
        isKidsMode = kids

        // Drop any in-flight results first: adult content from the previous
        // profile must not survive into the kids view (or vice versa).
        searchJob?.cancel()
        addonSearchJob?.cancel()
        _searchQuery.value = ""
        _results.value = emptyList()
        _actorResults.value = emptyList()
        _studioResults.value = emptyList()
        _collectionResults.value = emptyList()
        _suggestions.value = emptyList()
        _addonResultGroups.value = emptyList()
        _isLoading.value = false
        browseReturnChip = null
        _selectedBrowseCategoryKey.value = null

        browseCacheFresh = false
        publishBrowseCategories(baseBrowseCategories())
        loadBrowseCatalogCache()

        val needsResolve = !catalogResolveStarted && !browseCacheFresh
        _browseSubmenuLoading.value = needsResolve
        if (needsResolve) {
            viewModelScope.launch { resolveCatalogEntries() }
        }
    }

    private val _browseSubmenuLoading =
        MutableStateFlow(false)

    val browseSubmenuLoading: StateFlow<Boolean> =
        _browseSubmenuLoading.asStateFlow()

    /**
     * Which Browse category is open in the browser strip. Lives in the
     * activity-scoped ViewModel (not the composable's remember{}) so
     * backing out of a discover screen restores the same open category
     * instead of collapsing the browser back to nothing.
     */
    private val _selectedBrowseCategoryKey = MutableStateFlow<String?>(null)

    val selectedBrowseCategoryKey: StateFlow<String?> =
        _selectedBrowseCategoryKey.asStateFlow()

    /**
     * The exact chip (category + index within its submenu) that launched the
     * current discover screen, so Back can re-focus that chip — the TV
     * convention of focus returning to the thing that opened the screen.
     */
    var browseReturnChip: Pair<String, Int>? = null

    private var catalogResolveStarted = false

    // Caps parallel TMDB name->id lookups in resolveCatalogEntries: the
    // 216-name keyword list fanned out unbounded and TMDB throttled the
    // burst, silently dropping chips (failed lookups are filtered out).
    private val catalogResolveSemaphore = Semaphore(permits = 8)

    /** True when the disk cache was applied AND is younger than the TTL. */
    private var browseCacheFresh = false

    // NOTE: this init block MUST sit below every property declaration in
    // this class. Kotlin initializes properties top-down, and a
    // Main.immediate-dispatched collector launched from init can execute
    // during the constructor itself — with init above late-declared
    // properties (e.g. _suggestions), refreshKidsMode() dereferenced an
    // uninitialized StateFlow and crashed at startup (Sentry ANDROID-9).
    init {
        loadRecentSearches()
        // Hidden chips load first: the sidebar publish below and the cache
        // merge that follows both filter against the hidden set.
        loadHiddenBrowseChips()
        publishBrowseCategories(baseBrowseCategories())
        // Restore the last-resolved keyword/collection ids from disk so the
        // browse submenu renders instantly; a background refresh then only
        // repairs gaps after the TTL.
        loadBrowseCatalogCache()

        // Kids Mode follows the ACTIVE profile: on every switch the browse
        // browser swaps to (or from) the kid-focused chip set and any adult
        // content still on screen is dropped, so a profile change can never
        // leave a child staring at the previous profile's results.
        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
            .onEach { refreshKidsMode() }
            .launchIn(viewModelScope)

        WatchStateBus.updates
            .onEach { update ->
                val current = _watchedKeys.value.toMutableSet()
                if (update.isWatched) {
                    current.add(update.key)
                } else {
                    current.remove(update.key)
                }
                _watchedKeys.value = current

                // The badge state as the write resolved it: a whole-title
                // mark resolves to the checkmark (no eye), while unmarking
                // part of a series resolves to the eye and has to show it
                // right away instead of leaving the tile bare until the next
                // marker preload.
                val partial = _partialWatchedKeys.value.toMutableSet()
                if (update.isPartiallyWatched) {
                    partial.add(update.key)
                } else {
                    partial.remove(update.key)
                }
                _partialWatchedKeys.value = partial
            }
            .launchIn(viewModelScope)
    }

    /**
     * Sidebar selection: swap the submenu. Keyword/collection entries are
     * runtime-resolved ids, so picking those categories kicks the (cached,
     * once-per-session) resolution off.
     */
    fun selectBrowseCategory(key: String) {
        // Manually switching categories invalidates any armed return chip
        // from another category — it could otherwise re-grab focus the
        // next time that submenu opens.
        if (browseReturnChip?.first != key) browseReturnChip = null
        _selectedBrowseCategoryKey.value = key
        if (key == "keywords" || key == "collections") {
            // Only show the resolving state when there is nothing to render:
            // with a disk-cache hit the chips are already in place, and a
            // background refresh (stale TTL) must not blank them out with a
            // spinner.
            val hasEntries = _browseCategories.value
                .firstOrNull { it.key == key }?.entries?.isNotEmpty() == true
            _browseSubmenuLoading.value = !hasEntries && !catalogResolveStarted
            // A FRESH disk cache is already correct (TMDB ids are stable),
            // so skip the ~300 lookups entirely; a stale or missing cache
            // kicks the rate-limited background resolve off once.
            if (!catalogResolveStarted && !browseCacheFresh) {
                viewModelScope.launch { resolveCatalogEntries() }
            }
        } else {
            _browseSubmenuLoading.value = false
        }
    }

    /** A submenu entry press: open its dedicated discover screen. */
    fun onBrowseEntryClicked(categoryKey: String, entry: BrowseEntry) {
        // Remember where we came from so Back re-focuses this chip.
        _selectedBrowseCategoryKey.value = categoryKey
        _browseCategories.value.firstOrNull { it.key == categoryKey }
            ?.entries?.indexOfFirst { it.id == entry.id && it.name == entry.name }
            ?.takeIf { it >= 0 }
            ?.let { browseReturnChip = categoryKey to it }
        when (categoryKey) {
            "genres" -> onOpenTagScreen?.invoke(entry.id, entry.name, false, "movie")
            "keywords" -> onOpenTagScreen?.invoke(entry.id, entry.name, true, "movie")
            // Services carry their watch-provider id so their screen runs
            // movies + series rails; a plain network entry has none, so it
            // runs the network page — series rails, plus MOVIES rails when
            // the entry also carries the brand's company id. isNetwork
            // follows the id space (network unless the entry ids a company)
            // so the header's logo/detail lookups hit the right endpoint.
            "services" -> onOpenStudioScreen?.invoke(
                entry.id,
                entry.name,
                !entry.networkIsCompany,
                entry.providerId,
                entry.networkOrCompanyId,
                entry.networkIsCompany,
                entry.originalsCompanyId
            )
            "studios" -> onOpenStudioScreen?.invoke(entry.id, entry.name, false, null, null, false, null)
            "collections" -> onOpenCollectionScreen?.invoke(entry.id, entry.name)
            "decades" -> onOpenDecadeScreen?.invoke(entry.id, entry.name)
        }
    }

    /**
     * Resolves the runtime-id submenus: keyword names via /search/keyword,
     * collection names via /search/collection. Hand-picked ids would rot; a
     * name lookup always returns TMDB's canonical id.
     *
     * Results are persisted to disk (see [loadBrowseCatalogCache]) so the
     * FIRST app run pays the lookup cost and every later run renders the
     * chips instantly. Lookups are capped by [catalogResolveSemaphore] —
     * the unbounded 216-name fan-out tripped TMDB throttling and silently
     * dropped chips — and each name gets one retry after a short backoff.
     */
    private suspend fun resolveCatalogEntries() {
        if (catalogResolveStarted) return
        catalogResolveStarted = true

        // Resolve the UNION of the standard and kids name lists. The kids
        // lists stopped being strict subsets when the second wave of
        // kids-only franchises/keywords landed (SpongeBob, PAW Patrol,
        // treehouse, spelling bee, ...), so a standard-only resolve would
        // leave those chips id-less and dead. Union resolve serves both
        // modes from one pass; the extra lookups are semaphore-capped.
        val keywordEntries = coroutineScope {
            (BROWSE_KEYWORD_NAMES + KIDS_KEYWORD_NAMES).distinct().map { name ->
                async {
                    catalogResolveSemaphore.withPermit {
                        resolveWithRetry(name) {
                            tmdbRepository.searchKeywords(name)
                                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                                ?.let { BrowseEntry(it.id, name) }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val collectionEntries = coroutineScope {
            (
                BROWSE_COLLECTION_NAMES +
                    KIDS_COLLECTION_NAMES +
                    KIDS_COLLECTION_NAMES_EXTRA
                ).distinct().map { name ->
                async {
                    catalogResolveSemaphore.withPermit {
                        resolveWithRetry(name) {
                            // Prefer the exact-name hit: TMDB's search ranking
                            // drifts over time and first-hit can resolve to an
                            // unrelated collection ("The Lord of the Rings
                            // Collection" once resolved to a making-of doc).
                            val results = tmdbRepository.searchCollection(name)
                            (results.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                ?: results.firstOrNull())
                                ?.let { BrowseEntry(it.id, name) }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        publishBrowseCategories(
            baseBrowseCategories().map { category ->
                when (category.key) {
                    "keywords" -> category.copy(entries = keywordEntries)
                    "collections" -> category.copy(entries = collectionEntries)
                    else -> category
                }
            }
        )
        _browseSubmenuLoading.value = false
        saveBrowseCatalogCache(keywordEntries, collectionEntries)
    }

    /**
     * One name lookup with a single throttled-retry: TMDB's search endpoint
     * occasionally 429s under bursts even below the rate cap, and a dropped
     * chip is invisible (the entry just never renders).
     */
    private suspend fun resolveWithRetry(
        name: String,
        lookup: suspend () -> BrowseEntry?
    ): BrowseEntry? {
        val first = runCatching { lookup() }.getOrNull()
        if (first != null) return first
        delay(BROWSE_RESOLVE_RETRY_DELAY_MS)
        return runCatching { lookup() }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Browse-catalog disk cache: resolved keyword/collection ids in
    // search_prefs as JSON. Makes the submenu open instantly on every run
    // after the first (the ~300 name lookups otherwise re-run each
    // session). Format per list: [["name", id], ...] plus a saved-at
    // timestamp; expired after [BROWSE_CACHE_TTL_MS] and refreshed in the
    // background on next open.
    // ------------------------------------------------------------------

    private fun loadBrowseCatalogCache() {
        runCatching {
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedAt = prefs.getLong(KEY_BROWSE_CATALOG_SAVED_AT, 0L)
            val keywordsJson = prefs.getString(KEY_BROWSE_KEYWORD_IDS, null)
            val collectionsJson = prefs.getString(KEY_BROWSE_COLLECTION_IDS, null)
            val hasData = !keywordsJson.isNullOrEmpty() || !collectionsJson.isNullOrEmpty()
            if (!hasData) return

            fun parse(json: String): List<BrowseEntry> {
                val arr = JSONArray(json)
                return (0 until arr.length()).mapNotNull { i ->
                    val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                    val id = pair.optInt(0, -1)
                    val name = pair.optString(1)
                    if (id > 0 && name.isNotBlank()) BrowseEntry(id, name) else null
                }
            }

            val keywords = keywordsJson?.let(::parse).orEmpty()
            val collections = collectionsJson?.let(::parse).orEmpty()
            if (keywords.isEmpty() && collections.isEmpty()) return

            // The cache is keyed by name and holds the union of both modes'
            // lists, so a mode flip just re-filters it onto the active chips.
            val activeKeywords = activeKeywordNames().toSet()
            val activeCollections = activeCollectionNames().toSet()
            val keywordsForMode = keywords.filter { it.name in activeKeywords }
            val collectionsForMode = collections.filter { it.name in activeCollections }

            // "Fresh" means recent AND substantially complete (>= 90% of the
            // name lists resolved): a cache written while TMDB was
            // throttling could be missing a chunk of chips, and trusting it
            // for a full TTL would pin that gap. A partial cache still
            // renders instantly below — it just doesn't skip the background
            // refresh that repairs it.
            //
            // Completeness is measured on the slice actually rendered. The
            // raw cache also holds the other mode's names and any name a
            // catalog edit dropped, so counting it whole called a cache
            // "complete" that was missing chips the active list asks for, and
            // a newly added collection stayed invisible for the whole TTL.
            val ageOk =
                savedAt > 0 && System.currentTimeMillis() - savedAt < BROWSE_CACHE_TTL_MS
            val keywordsComplete =
                keywordsForMode.size * 10 >= activeKeywordNames().size * 9
            val collectionsComplete =
                collectionsForMode.size * 10 >= activeCollectionNames().size * 9
            browseCacheFresh = ageOk && keywordsComplete && collectionsComplete

            publishBrowseCategories(
                baseBrowseCategories().map { category ->
                    when (category.key) {
                        "keywords" ->
                            category.copy(entries = keywordsForMode.ifEmpty { category.entries })
                        "collections" ->
                            category.copy(entries = collectionsForMode.ifEmpty { category.entries })
                        else -> category
                    }
                }
            )
        }.onFailure { Log.w(TAG, "browse catalog cache read failed", it) }
    }

    private fun saveBrowseCatalogCache(
        keywords: List<BrowseEntry>,
        collections: List<BrowseEntry>
    ) {
        runCatching {
            fun toJson(entries: List<BrowseEntry>): String {
                val arr = JSONArray()
                entries.forEach { entry ->
                    arr.put(JSONArray().put(entry.id).put(entry.name))
                }
                return arr.toString()
            }
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BROWSE_CATALOG_SAVED_AT, System.currentTimeMillis())
                .putString(KEY_BROWSE_KEYWORD_IDS, toJson(keywords))
                .putString(KEY_BROWSE_COLLECTION_IDS, toJson(collections))
                .apply()
        }.onFailure { Log.w(TAG, "browse catalog cache write failed", it) }
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
        // A return chip armed by a browse click that never got its Back
        // restore (user top-nav'd away from the discover screen) must not
        // steal focus the next time Search is entered.
        browseReturnChip = null
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
        const val TAG = "SearchViewModel"
        const val PREFS_NAME = "search_prefs"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val KEY_BROWSE_KEYWORD_IDS = "browse_keyword_ids"
        const val KEY_BROWSE_COLLECTION_IDS = "browse_collection_ids"
        const val KEY_BROWSE_CATALOG_SAVED_AT = "browse_catalog_saved_at"

        /**
         * How long the disk-cached keyword/collection ids are trusted. TMDB
         * ids are stable, so the TTL mostly guards against a cached resolve
         * that was itself incomplete (e.g. resolved while offline); a stale
         * cache renders instantly while a background refresh repairs it.
         */
        const val BROWSE_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L

        /** Backoff before the single retry of a throttled name lookup. */
        const val BROWSE_RESOLVE_RETRY_DELAY_MS = 750L
        const val RECENT_SEARCHES_SEPARATOR = "\u0001"
        const val MAX_RESOLUTION_BATCH = 300
        const val MAX_RECENT_SEARCHES = 10
        const val MAX_TITLE_RESULTS = 24
        const val MAX_PERSON_RESULTS = 12
        const val MAX_STUDIO_RESULTS = 8
        const val MAX_COLLECTION_RESULTS = 8

        const val MAX_ADDON_RESULTS_PER_ADDON = 40
        // Rails per add-on. Must exceed AIOMetadata's maximum search-catalog
        // set (Movies / Series / Anime x2 / Collections / People x2 / AI = 8):
        // search-style rails are ordered first per addon, and a tighter cap
        // silently dropped the tail of the user's search order — usually the
        // AI Search rail.
        const val MAX_ADDON_GROUPS_PER_ADDON = 8
        const val MAX_ADDON_GROUPS = 14

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
                // Manifests often name search catalogs "Addon - AI Search"
                // (or "Addon · AI Search"); stripping just the addon name
                // used to leave a dangling "- AI Search" rail label.
                return raw.removePrefix(addonName).trim()
                    .removePrefix("-").trim()
                    .removePrefix("\u00B7").trim()
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

        /** Per-rail cap on TMDB enrichments for missing year/rating. */
        const val MAX_ENRICH_PER_GROUP = 20

        /** Per-rail cap for a catalog-only addon's per-catalog search rail. */
        const val MAX_ADDON_CATALOG_RESULTS = 20
        const val MAX_SUGGESTIONS = 6
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
