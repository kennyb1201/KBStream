package com.kennyb1201.kbstream.ui.studio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.CrossBase
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TagRailPage
import com.kennyb1201.kbstream.data.tmdb.TmdbCompanyDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

data class StudioRailPagingState(
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository.getInstance(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _sections = MutableStateFlow<List<StudioSection>>(emptyList())
    val sections: StateFlow<List<StudioSection>> = _sections.asStateFlow()

    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Surfaced to the screen so a failed load reads as a failure instead of
    // an empty company (same contract as TagViewModel/ActorViewModel).
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pagingStates = MutableStateFlow<Map<String, StudioRailPagingState>>(emptyMap())
    val pagingStates: StateFlow<Map<String, StudioRailPagingState>> = _pagingStates.asStateFlow()

    private val _logoUrl = MutableStateFlow<String?>(null)
    val logoUrl: StateFlow<String?> = _logoUrl.asStateFlow()

    private val _companyInfo = MutableStateFlow<TmdbCompanyDetail?>(null)
    val companyInfo: StateFlow<TmdbCompanyDetail?> = _companyInfo.asStateFlow()

    // True when this screen is a streaming SERVICE page (watch-provider
    // rails for movies + series) rather than a plain network/company page.
    private val _isService = MutableStateFlow(false)
    val isService: StateFlow<Boolean> = _isService.asStateFlow()

    // Reactive watched keys pipeline combining resolved IDs with the repository's hot update flow
    val watchedKeys: StateFlow<Set<String>> = combine(
        _sections,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { sections: List<StudioSection>, resolvedMap: Map<String, String>, _ ->
        if (sections.isEmpty() || resolvedMap.isEmpty()) {
            emptySet()
        } else {
            sections.flatMap { it.items }
                .mapNotNull { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@mapNotNull null
                    val imdbId = resolvedMap[lookupKey(tmdbId, mediaType)] ?: return@mapNotNull null
                    if (watchedStatusRepository.isWatchedCached(imdbId, mediaType)) {
                        watchedKey(imdbId, mediaType)
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

    // Eye-badge twin of watchedKeys: shows started-but-not-finished, from
    // the same cached watch state. The completed checkmark wins when a key
    // resolves to both.
    val partialWatchedKeys: StateFlow<Set<String>> = combine(
        _sections,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { sections: List<StudioSection>, resolvedMap: Map<String, String>, _ ->
        if (sections.isEmpty() || resolvedMap.isEmpty()) {
            emptySet()
        } else {
            sections.flatMap { it.items }
                .mapNotNull { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@mapNotNull null
                    val imdbId = resolvedMap[lookupKey(tmdbId, mediaType)] ?: return@mapNotNull null
                    if (watchedStatusRepository.isPartiallyWatchedCached(imdbId, mediaType)) {
                        watchedKey(imdbId, mediaType)
                    } else {
                        null
                    }
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
    private var currentIsNetwork: Boolean = false
    private var currentProviderId: Int? = null
    private var currentNetworkOrCompanyId: Int? = null
    private var currentNetworkIsCompany: Boolean = false
    private var currentOriginalsCompanyId: Int? = null

    // Genre chip filter: when non-null, rails re-run with the selected
    // genre ANDed onto the screen's base dimension (provider / network /
    // company, whichever the browse entry carries).
    private var currentGenreId: Int? = null
    private val _browseGenres = MutableStateFlow<List<TmdbGenre>>(emptyList())
    val browseGenres: StateFlow<List<TmdbGenre>> = _browseGenres.asStateFlow()

    private val _selectedGenreId = MutableStateFlow<Int?>(null)
    val selectedGenreId: StateFlow<Int?> = _selectedGenreId.asStateFlow()

    /** Genre chips act on the loaded entity's base dimension. */
    fun crossGenreBase(): CrossBase? {
        val id = currentId ?: return null
        // A network dimension is TV-only in TMDB's discover, so the genre
        // rails need the brand's company id to keep the MOVIES rails alive
        // once a genre chip is active.
        val networkCompanyId = if (currentNetworkIsCompany) null else currentOriginalsCompanyId
        return when {
            // A watch-provider base needs the provider id; entries whose
            // header id is the company itself use the company kind.
            currentProviderId != null -> CrossBase("provider", currentProviderId!!)
            currentNetworkOrCompanyId != null -> CrossBase(
                if (currentNetworkIsCompany) "company" else "network",
                currentNetworkOrCompanyId!!,
                companyId = networkCompanyId
            )
            // Plain network/studio page: header id IS the dimension.
            else -> CrossBase(
                if (currentIsNetwork) "network" else "company",
                id,
                companyId = if (currentIsNetwork) currentOriginalsCompanyId else null
            )
        }
    }

    /** Select/clear the genre filter and reload the rails. */
    fun onGenreSelected(genreId: Int?) {
        val id = currentId ?: return
        if (genreId == currentGenreId) return
        load(
            id,
            currentIsNetwork,
            currentProviderId,
            currentNetworkOrCompanyId,
            currentNetworkIsCompany,
            currentOriginalsCompanyId,
            genreId
        )
    }

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    fun load(
        id: Int,
        isNetwork: Boolean,
        providerId: Int? = null,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        originalsCompanyId: Int? = null,
        genreId: Int? = null
    ) {
        val isSameRoute =
            currentId == id &&
            currentIsNetwork == isNetwork &&
            currentProviderId == providerId &&
            currentNetworkOrCompanyId == networkOrCompanyId &&
            currentOriginalsCompanyId == originalsCompanyId &&
            currentGenreId == genreId &&
            _sections.value.isNotEmpty()

        if (isSameRoute) return

        currentId = id
        currentIsNetwork = isNetwork
        currentProviderId = providerId
        currentNetworkOrCompanyId = networkOrCompanyId
        currentGenreId = genreId
        _selectedGenreId.value = genreId
        currentNetworkIsCompany = networkIsCompany
        currentOriginalsCompanyId = originalsCompanyId

        // The chip row was rendering "All" only: the flow existed but nothing
        // ever populated it. Load the TMDB genre list like DecadeViewModel
        // does so services/networks/studios actually get genre chips.
        viewModelScope.launch {
            runCatching { _browseGenres.value = tmdbRepository.getBrowseGenres() }
                .onFailure { Log.w("STUDIO_VM", "browse genres failed: ${it.message}") }
        }

        // Anything with a watch provider, or a company-identified brand
        // (niche streamers whose header id IS the company), runs the service
        // rails. A plain NETWORK entry keeps the network route even when it
        // carries a company id: the network page now renders its MOVIES
        // rails through that company (see TmdbRepository.getByNetwork), so
        // it no longer has to give up its series rails to see the movie
        // slate — which is what the old routing did.
        val serviceRoute = providerId != null ||
            (originalsCompanyId != null && !isNetwork)

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _sections.value = emptyList()
            _resolvedIds.value = emptyMap()
            _pagingStates.value = emptyMap()
            _logoUrl.value = null
            _companyInfo.value = null
            _isService.value = serviceRoute

            // Clear logo + blurb for the header. Networks use their own TMDB
            // endpoints (a network id is not a company id), so route by type.
            //
            // Both are fetched CONCURRENTLY with the rails below. They used
            // to be awaited one after the other before the six-rail discover
            // fan-out even started, so a network page waited on the sum of
            // three independent sets of round-trips (logo + detail + rails)
            // instead of the slowest one.
            val logoDeferred = async {
                try {
                    tmdbRepository.getEntityLogoUrl(id, isNetwork)
                } catch (e: Exception) {
                    Log.w("STUDIO_VM", "Logo lookup failed for id=$id", e)
                    null
                }
            }
            val detailDeferred = async {
                try {
                    tmdbRepository.getEntityDetail(id, isNetwork)
                } catch (e: Exception) {
                    Log.w("STUDIO_VM", "Entity detail failed for id=$id", e)
                    null
                }
            }

            try {
                val result = when {
                    // Genre chip active: genre ANDed onto the base dimension
                    // (provider rails skip originals, which are not the chip's
                    // target and would just repeat it).
                    currentGenreId != null && crossGenreBase() != null ->
                        tmdbRepository.getInitialCrossGenreSections(
                            base = crossGenreBase()!!,
                            genreId = currentGenreId!!
                        )
                    // Service pages discover through watch-provider rails
                    // (movies + series) instead of network/company rails.
                    // The ORIGINALS rails (network/company discover) ride
                    // along when the browse entry carries those ids.
                    serviceRoute ->
                        tmdbRepository.getInitialServiceSections(
                            providerId = providerId,
                            networkOrCompanyId = networkOrCompanyId,
                            networkIsCompany = networkIsCompany,
                            originalsCompanyId = originalsCompanyId
                        )
                    isNetwork ->
                        tmdbRepository.getByNetwork(id, companyId = originalsCompanyId)
                    else ->
                        tmdbRepository.getByCompany(id)
                }

                _sections.value = result
                _pagingStates.value = result.associate { section ->
                    section.title to StudioRailPagingState(
                        nextPage = 2,
                        hasMore = section.items.isNotEmpty(),
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load studio"
                Log.e("STUDIO_VM", "load failed for id=$id isNetwork=$isNetwork", e)
            }

            // Collected last: the rails are what the screen is for, so they
            // own the loading state, while the header art/blurb have been in
            // flight alongside them and land in the same frame.
            _logoUrl.value = logoDeferred.await()
            _companyInfo.value = detailDeferred.await()
            _isLoading.value = false

            val loadedSections = _sections.value
            if (loadedSections.isNotEmpty()) {
                launch {
                    delay(350)
                    resolveAndPreloadWatched(loadedSections)
                }
            }
        }
    }

    fun loadMoreSection(title: String) {
        val screenId = currentId ?: return
        var pageToLoad: Int? = null

        _pagingStates.value = _pagingStates.value.toMutableMap().apply {
            val current = this[title] ?: return
            if (current.isLoadingMore || !current.hasMore) return
            pageToLoad = current.nextPage
            this[title] = current.copy(isLoadingMore = true)
        }

        val pageNumber = pageToLoad ?: return

        viewModelScope.launch {
            try {
                val page: TagRailPage = when {
                    currentGenreId != null && crossGenreBase() != null ->
                        tmdbRepository.getCrossGenreRailPage(
                            base = crossGenreBase()!!,
                            genreId = currentGenreId!!,
                            title = title,
                            page = pageNumber
                        )
                    // Provider rails and company-identified brands first:
                    // service entries are `isNetwork` too, so the network
                    // branch below must not swallow them.
                    currentProviderId != null ||
                        (currentOriginalsCompanyId != null && !currentIsNetwork) ->
                        tmdbRepository.getServiceRailPage(
                            providerId = currentProviderId,
                            title = title,
                            page = pageNumber,
                            networkOrCompanyId = currentNetworkOrCompanyId,
                            networkIsCompany = currentNetworkIsCompany,
                            originalsCompanyId = currentOriginalsCompanyId
                        )
                    currentIsNetwork ->
                        tmdbRepository.getNetworkRailPage(
                            screenId,
                            title,
                            pageNumber,
                            companyId = currentOriginalsCompanyId
                        )
                    else ->
                        tmdbRepository.getCompanyRailPage(screenId, title, pageNumber)
                }

                val existingSection = _sections.value.firstOrNull { it.title == title }
                if (existingSection != null) {
                    val mergedItems = (existingSection.items + page.items)
                        .distinctBy { item -> item.item.id }

                    val updatedSections = _sections.value.map { section ->
                        if (section.title == title) {
                            section.copy(items = mergedItems)
                        } else {
                            section
                        }
                    }

                    _sections.value = updatedSections

                    if (page.items.isNotEmpty()) {
                        resolveAndPreloadWatched(updatedSections)
                    }
                }

                _pagingStates.value = _pagingStates.value.toMutableMap().apply {
                    this[title] = StudioRailPagingState(
                        // Resumes after whatever page deepening already merged
                        // for this rail (see TagRailPage.nextPage).
                        nextPage = page.nextPage,
                        hasMore = page.hasMore,
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                Log.e("STUDIO_VM", "loadMoreSection failed for $title", e)
                _pagingStates.value = _pagingStates.value.toMutableMap().apply {
                    val current = this[title] ?: return@apply
                    this[title] = current.copy(isLoadingMore = false)
                }
            }
        }
    }

    private suspend fun resolveAndPreloadWatched(sections: List<StudioSection>) {
        try {
            if (sections.isEmpty()) {
                _resolvedIds.value = emptyMap()
                return
            }

            val uniqueItems = sections
                .flatMap { it.items }
                .mapNotNull { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@mapNotNull null
                    if (tmdbId <= 0) null else tmdbId to mediaType
                }
                .distinct()
                .take(300)

            if (uniqueItems.isEmpty()) {
                _resolvedIds.value = emptyMap()
                return
            }

            val resolvedTriples = supervisorScope {
                uniqueItems.map { (tmdbId, mediaType) ->
                    async {
                        val imdbId = runCatching {
                            imdbResolveSemaphore.withPermit {
                                tmdbRepository.resolveImdbId(tmdbId, mediaType)
                            }
                        }.getOrNull()
                        Triple(tmdbId, mediaType, imdbId)
                    }
                }.map { it.await() }
            }

            val resolved = resolvedTriples.filter { (_, _, imdbId) ->
                !imdbId.isNullOrBlank()
            }

            if (resolved.isEmpty()) {
                _resolvedIds.value = emptyMap()
                return
            }

            _resolvedIds.value = resolved.associate { (tmdbId, mediaType, imdbId) ->
                lookupKey(tmdbId, mediaType) to imdbId!!
            }

            val preloadItems = resolved
                .map { (_, mediaType, imdbId) -> imdbId!! to mediaType }
                .distinct()

            watchedStatusRepository.preload(preloadItems)

            Log.d(
                "STUDIO_WATCHED",
                "resolveAndPreloadWatched done, items=${uniqueItems.size}, resolved=${resolved.size}"
            )
        } catch (e: Exception) {
            Log.e("STUDIO_WATCHED", "resolveAndPreloadWatched failed: ${e.message}", e)
            _resolvedIds.value = emptyMap()
        }
    }

    fun resolveAndNavigate(
        tmdbId: Int,
        mediaType: String,
        onNavigateDetail: (String, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val imdbId = _resolvedIds.value[lookupKey(tmdbId, normalizedType)]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (!imdbId.isNullOrBlank()) {
                onNavigateDetail(normalizedType, imdbId)
            }
        }
    }

    private fun normalizeMediaType(mediaType: String?): String? =
        when (mediaType?.lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "series"
            else -> null
        }

    /**
     * Long-press "Mark as Watched" on a rail poster: resolves the TMDB id
     * to an IMDB id, records the persistent local watched override
     * (mirrored to SIMKL when connected) and caches the resolution so the
     * rail badge updates reactively.
     */
    fun markAsWatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val lookup = lookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedIds.value[lookup]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("STUDIO_WATCHED", "markAsWatched failed tmdb=$tmdbId", e)
            }
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a rail poster: resolves the TMDB id
     * to an IMDB id, removes the persistent local watched override
     * (mirrored as a Simkl history delete when connected) and caches the
     * resolution so the rail badge updates reactively.
     */
    fun markUnwatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val lookup = lookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedIds.value[lookup]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("STUDIO_WATCHED", "markUnwatched failed tmdb=$tmdbId", e)
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, normalizeMediaType(type) ?: type)
}
