package com.kennyb1201.kbstream.ui.kb

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.kb.KBContentItem
import com.kennyb1201.kbstream.data.kb.KBContentLoader
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.kb.KBFilters
import com.kennyb1201.kbstream.data.kb.KBRail
import com.kennyb1201.kbstream.data.kb.kbMostVotedSort
import com.kennyb1201.kbstream.data.tmdb.HeroArtwork
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDiscoverItem
import com.kennyb1201.kbstream.data.tmdb.TmdbHeroArtworkRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.alternatePosterPath
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.cardBackdropPath
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import com.kennyb1201.kbstream.data.tmdb.displayRating
import com.kennyb1201.kbstream.data.tmdb.displayRuntime
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Per-rail load-more state, mirroring StudioScreen's paging model. */
data class KBRailPagingState(
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

/**
 * Loads one KB folder: every source becomes a rail; the hero mirrors
 * Home's exactly (TMDB meta + clearlogo/backdrop artwork + inline trailer)
 * so a FOLLOW_LAYOUT folder is a true replica of the home screen.
 */
class KBFolderViewModel(application: Application) : AndroidViewModel(application) {

    private val contentLoader = KBContentLoader(application)
    private val tmdbRepository = TmdbRepository.getInstance(application)
    private val heroArtworkRepository = TmdbHeroArtworkRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)
    private val repository =
        com.kennyb1201.kbstream.data.kb.KBRepository.getInstance(application)

    data class UiState(
        val folder: KBFolder? = null,
        val showAllTab: Boolean = false,
        val rails: List<KBRail> = emptyList(),
        val pagingStates: Map<String, KBRailPagingState> = emptyMap(),
        val isLoading: Boolean = true,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Selected source tab: null = "All" (every rail) when showAllTab is on.
    private val _selectedSourceId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = _selectedSourceId.asStateFlow()

    // TMDB id -> imdb id, filled as rails load so watched badges and the
    // context menu key off the same imdb ids the rest of the app uses.
    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    // Reactive watched keys. TMDB items resolve through _resolvedIds; Stremio
    // addon items already carry imdb ids ("tt…") and key directly.
    val watchedKeys: StateFlow<Set<String>> = combine(
        _state,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { state: UiState, resolved: Map<String, String>, _: Long ->
        if (state.rails.isEmpty()) {
            emptySet()
        } else {
            val keys = mutableSetOf<String>()
            for (rail in state.rails) {
                for (item in rail.items) {
                    val normalized = normalizeType(item.type) ?: continue
                    val imdbId = item.tmdbId?.let { tmdbId ->
                        resolved[lookupKey(tmdbId, normalized)]
                    } ?: item.id.takeIf { it.startsWith("tt") }
                    if (imdbId != null &&
                        watchedStatusRepository.isWatchedCached(imdbId, normalized)
                    ) {
                        keys += watchedKey(imdbId, normalized)
                    }
                }
            }
            keys
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
        _state,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { state: UiState, resolved: Map<String, String>, _: Long ->
        if (state.rails.isEmpty()) {
            emptySet()
        } else {
            val keys = mutableSetOf<String>()
            for (rail in state.rails) {
                for (item in rail.items) {
                    val normalized = normalizeType(item.type) ?: continue
                    val imdbId = item.tmdbId?.let { tmdbId ->
                        resolved[lookupKey(tmdbId, normalized)]
                    } ?: item.id.takeIf { it.startsWith("tt") }
                    if (imdbId != null &&
                        watchedStatusRepository.isPartiallyWatchedCached(imdbId, normalized)
                    ) {
                        keys += watchedKey(imdbId, normalized)
                    }
                }
            }
            keys - watchedKeys.value
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    // ------------------------------------------------------------------
    // Home-identical hero: TMDB detail + hero artwork + trailer for the
    // focused item, mirroring HomeViewModel.resolveHeroMeta (250ms dwell +
    // cancel-on-refocus) so folder screens behave exactly like Home.
    // ------------------------------------------------------------------
    private var heroResolveJob: Job? = null

    // The focused item as a Home-style preview: published immediately on
    // focus so the hero shows the item's own art while TMDB enrichment runs.
    private val _heroPreview = MutableStateFlow<MetaPreview?>(null)
    val heroPreview: StateFlow<MetaPreview?> = _heroPreview.asStateFlow()

    private val _heroMeta = MutableStateFlow<Meta?>(null)
    val heroMeta: StateFlow<Meta?> = _heroMeta.asStateFlow()

    private val _heroTmdbDetail = MutableStateFlow<TmdbDetail?>(null)
    val heroTmdbDetail: StateFlow<TmdbDetail?> = _heroTmdbDetail.asStateFlow()

    private val _heroBackdropUrl = MutableStateFlow<String?>(null)
    val heroBackdropUrl: StateFlow<String?> = _heroBackdropUrl.asStateFlow()

    private val _heroLogoUrl = MutableStateFlow<String?>(null)
    val heroLogoUrl: StateFlow<String?> = _heroLogoUrl.asStateFlow()

    private val _heroTrailerKey = MutableStateFlow<String?>(null)
    val heroTrailerKey: StateFlow<String?> = _heroTrailerKey.asStateFlow()

    fun resolveHero(item: KBContentItem) {
        heroResolveJob?.cancel()

        val type = normalizeType(item.type) ?: "movie"
        _heroPreview.value = MetaPreview(
            id = item.id,
            type = type,
            name = item.title ?: "",
            poster = item.posterUrl,
            background = item.backdropUrl,
            description = item.overview
        )
        _heroMeta.value = null
        _heroTmdbDetail.value = null
        _heroBackdropUrl.value = null
        _heroLogoUrl.value = null
        _heroTrailerKey.value = null

        heroResolveJob = viewModelScope.launch {
            try {
                // Same 250ms dwell as Home: D-pad scrolling fires one focus
                // event per card, and a resolve per transitively-focused
                // title would starve the network. Focus that survives the
                // dwell is a deliberate stop — resolve it fully.
                delay(HERO_RESOLVE_DWELL_MS)

                coroutineScope {
                    val tmdbDetailDeferred = async {
                        runCatching {
                            when {
                                item.tmdbId != null && item.tmdbId > 0 ->
                                    tmdbRepository.getDetailByTmdbId(item.tmdbId, type)

                                item.id.startsWith("tt", ignoreCase = true) ->
                                    tmdbRepository.fetchEnrichedMetaCached(item.id, type)

                                else -> null
                            }
                        }.getOrNull()
                    }

                    val artworkDeferred = async {
                        val tmdbId = item.tmdbId?.takeIf { it > 0 }
                            ?: tmdbDetailDeferred.await()?.id
                        if (tmdbId == null || tmdbId <= 0) {
                            return@async null
                        }
                        runCatching {
                            heroArtworkRepository.resolve(
                                id = "tmdb:$tmdbId",
                                type = type,
                                tmdbId = tmdbId
                            )
                        }.getOrNull()
                    }

                    val detail = tmdbDetailDeferred.await()
                    val artwork = artworkDeferred.await()

                    val resolvedBackdrop =
                        artwork?.backdropUrl?.takeIf { it.isNotBlank() }
                            ?: detail?.backdropPath?.takeIf { it.isNotBlank() }
                                ?.let { TmdbRepository.BACKDROP_BASE + it }
                            ?: item.backdropUrl?.takeIf { it.isNotBlank() }
                            // Poster fallback for backdrop-less titles: prefer
                            // an ALTERNATE TMDB poster so the hero doesn't
                            // show the exact image the focused card shows.
                            ?: detail?.alternatePosterPath()?.takeIf { it.isNotBlank() }
                                ?.let { "https://image.tmdb.org/t/p/original$it" }
                            ?: item.posterUrl

                    val resolvedLogo =
                        artwork?.logoUrl?.takeIf { it.isNotBlank() }

                    _heroTmdbDetail.value = detail
                    _heroBackdropUrl.value = resolvedBackdrop
                    _heroLogoUrl.value = resolvedLogo
                    _heroMeta.value = detail?.let { tmdb ->
                        Meta(
                            id = item.id,
                            type = type,
                            name = tmdb.name?.trim()?.takeIf { it.isNotEmpty() }
                                ?: tmdb.title?.trim()?.takeIf { it.isNotEmpty() }
                                ?: item.title ?: "",
                            poster = tmdb.posterPath?.takeIf { it.isNotBlank() }
                                ?.let { TmdbRepository.POSTER_BASE + it }
                                ?: item.posterUrl,
                            background = resolvedBackdrop,
                            logo = resolvedLogo,
                            description = tmdb.displayDescription(),
                            releaseInfo = tmdb.releaseYear(),
                            imdbRating = tmdb.displayRating(),
                            runtime = tmdb.displayRuntime(),
                            genres = tmdb.genres
                                .map { it.name.trim() }
                                .filter { it.isNotEmpty() }
                                .takeIf { it.isNotEmpty() },
                            cast = tmdb.credits?.cast
                                ?.map { it.name.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?.distinct()
                                ?.take(12)
                                ?.takeIf { it.isNotEmpty() },
                            director = tmdb.credits?.director()
                                ?.name?.trim()?.takeIf { it.isNotEmpty() }
                                ?.let(::listOf)
                        )
                    }
                    _heroTrailerKey.value = detail?.videos?.results
                        ?.asSequence()
                        ?.filter { video ->
                            video.site.equals("YouTube", ignoreCase = true) &&
                                video.type.equals("Trailer", ignoreCase = true) &&
                                video.key.isNotBlank()
                        }
                        ?.firstOrNull()
                        ?.key
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected when focus moves before enrichment finishes; the
                // newly-focused item's own job takes over. Same as Home.
                throw e
            } catch (e: Exception) {
                Log.w("KB_FOLDER_HERO", "Hero enrichment failed: ${e.message}")
                _heroMeta.value = null
                _heroTmdbDetail.value = null
                _heroBackdropUrl.value = null
                _heroLogoUrl.value = null
                _heroTrailerKey.value = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Landscape-card artwork (backdrop + clearlogo) for folder rails while
    // the global "Landscape Cards" toggle is on — same model as Home.
    // ------------------------------------------------------------------
    private val landscapeArtSemaphore = Semaphore(permits = 6)
    private var lastLandscapeWanted: Boolean? = null

    private val _landscapeArt = MutableStateFlow<Map<String, HeroArtwork>>(emptyMap())
    val landscapeArt: StateFlow<Map<String, HeroArtwork>> = _landscapeArt.asStateFlow()

    /**
     * Resolve landscape art for the folder's TMDB-backed items while the
     * toggle is on. Safe to call on every rails change: already-resolved
     * items are skipped, so repeated calls only fetch newly appended pages
     * (or fetch nothing when nothing is new). Calling with `false` clears
     * the map.
     */
    fun ensureLandscapeArt(wanted: Boolean) {
        if (!wanted) {
            if (lastLandscapeWanted != false) {
                lastLandscapeWanted = false
                _landscapeArt.value = emptyMap()
            }
            return
        }

        if (lastLandscapeWanted != true) lastLandscapeWanted = true

        val alreadyResolved = _landscapeArt.value
        val items = _state.value.rails
            .flatMap { it.items }
            .distinctBy { "${it.type}:${it.id}" }
            .filterNot { item ->
                alreadyResolved.containsKey(
                    "${normalizeType(item.type) ?: "movie"}:${item.id}"
                )
            }
            .take(200)
        if (items.isEmpty()) return

        viewModelScope.launch {
            val resolved = supervisorScope {
                items.map { item ->
                    async {
                        val type = normalizeType(item.type) ?: return@async null

                        // Home's exact lookup path: numeric id = TMDB id,
                        // "tt…" = imdb id — so addon rails get TMDB art too.
                        val detail = runCatching {
                            landscapeArtSemaphore.withPermit {
                                tmdbRepository.fetchEnrichedMetaCached(
                                    item.id,
                                    type
                                )
                            }
                        }.getOrNull()

                        // Card backdrop prefers an ALTERNATE TMDB image so
                        // cards don't mirror the hero's primary backdrop;
                        // the item's own background (same image the hero
                        // shows) stays as fallback. The clearlogo comes
                        // from TMDB's best logo. Same merge rules as
                        // HomeViewModel.resolveLandscapeArt's regular
                        // (non-pinned) rails.
                        val tmdbBackdrop = detail?.cardBackdropPath()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.BACKDROP_BASE + it }
                        val tmdbLogo = detail?.bestLogoPath()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.LOGO_BASE + it }

                        // The entry always lands in the map (even all-null)
                        // so appended pages don't re-resolve; misses are
                        // cheap because fetchEnrichedMetaCached caches null
                        // details in memory.
                        "$type:${item.id}" to HeroArtwork(
                            backdropUrl = tmdbBackdrop ?: item.backdropUrl,
                            logoUrl = tmdbLogo
                        )
                    }
                }.awaitAll()
            }.filterNotNull()

            if (resolved.isNotEmpty()) {
                _landscapeArt.value = _landscapeArt.value + resolved.toMap()
            }
        }
    }

    // Only TMDB-backed rails (discover / list) paginate; trakt and addon
    // sources render their first page.
    private val tmdbRailSources = mutableMapOf<String, TmdbRailPageSource>()
    private var currentFolderId: String? = null

    private data class TmdbRailPageSource(
        val kind: Kind,
        val tmdbId: Int?,
        val mediaType: String,
        val sortBy: String?,
        val filters: KBFilters?,
        var nextPage: Int = 2
    ) {
        enum class Kind { DISCOVER, LIST }
    }

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, type: String): String =
        "${type.lowercase()}::$tmdbId"

    /**
     * Route entry: resolve the folder by id (survives process-death restore
     * where only the folder id is persisted), then load it.
     */
    fun loadById(folderId: String) {
        if (_state.value.folder?.id == folderId && !_state.value.isLoading) return
        viewModelScope.launch {
            val folder = runCatching { repository.findFolder(folderId) }.getOrNull()
            if (folder == null) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Couldn't find this collection."
                )
            } else {
                load(folder, folder.showAllTab)
            }
        }
    }

    fun load(folder: KBFolder, showAllTab: Boolean) {
        val folderKey = folder.id ?: folder.title
        val isSameRoute = currentFolderId == folderKey && _state.value.folder === folder
        if (isSameRoute && !_state.value.isLoading) return

        currentFolderId = folderKey
        tmdbRailSources.clear()
        _selectedSourceId.value = null
        _landscapeArt.value = emptyMap()
        lastLandscapeWanted = null
        _state.value = UiState(
            folder = folder,
            showAllTab = showAllTab,
            isLoading = true
        )

        viewModelScope.launch {
            try {
                val rails = contentLoader.loadFolderRails(folder)
                registerTmdbRailSources(folder, rails)
                _state.value = _state.value.copy(
                    rails = rails,
                    isLoading = false,
                    pagingStates = rails.associate { rail ->
                        rail.sourceId to KBRailPagingState(
                            hasMore = tmdbRailSources.containsKey(rail.sourceId) &&
                                rail.items.size >= PAGE_SIZE
                        )
                    },
                    error = if (rails.isEmpty()) {
                        "Nothing to show in \"${folder.title}\" yet."
                    } else {
                        null
                    }
                )
                resolveAndPreload(rails)
            } catch (e: Exception) {
                Log.e("KB_FOLDER_VM", "load failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Couldn't load this folder."
                )
            }
        }
    }

    private fun registerTmdbRailSources(folder: KBFolder, rails: List<KBRail>) {
        folder.sources.forEachIndexed { index, source ->
            val railId = source.id ?: "${folder.id}:$index"
            if (rails.none { it.sourceId == railId }) return@forEachIndexed
            val mediaType = when (source.provider?.lowercase()) {
                "tmdb" -> when (source.mediaType?.uppercase()) {
                    "MOVIE" -> "movie"
                    "TV" -> "tv"
                    else -> if (source.tmdbSourceType.equals("NETWORK", true)) "tv" else "movie"
                }
                else -> return@forEachIndexed
            }
            when {
                source.tmdbSourceType.equals("LIST", true) && source.tmdbId != null ->
                    tmdbRailSources[railId] = TmdbRailPageSource(
                        kind = TmdbRailPageSource.Kind.LIST,
                        tmdbId = source.tmdbId,
                        mediaType = mediaType,
                        sortBy = null,
                        filters = null
                    )

                source.tmdbSourceType.equals("COLLECTION", true) ||
                    source.tmdbSourceType.equals("COMPANY", true) ||
                    source.tmdbSourceType.equals("NETWORK", true) -> Unit // single-page sources

                else ->
                    tmdbRailSources[railId] = TmdbRailPageSource(
                        kind = TmdbRailPageSource.Kind.DISCOVER,
                        tmdbId = null,
                        mediaType = mediaType,
                        sortBy = kbMostVotedSort(source.sortBy) ?: "popularity.desc",
                        filters = source.filters
                    )
            }
        }
    }

    /** Load the next discover/list page for one rail. */
    fun loadMoreRail(railId: String) {
        val paging = _state.value.pagingStates[railId] ?: return
        val source = tmdbRailSources[railId] ?: return
        if (!paging.hasMore || paging.isLoadingMore) return

        _state.value = _state.value.copy(
            pagingStates = _state.value.pagingStates +
                (railId to paging.copy(isLoadingMore = true))
        )

        viewModelScope.launch {
            val page = source.nextPage
            val newItems = when (source.kind) {
                TmdbRailPageSource.Kind.DISCOVER ->
                    tmdbRepository.discoverKB(
                        mediaType = source.mediaType,
                        page = page,
                        sortBy = source.sortBy,
                        filters = source.filters
                    )?.map { it.toContentItem(source.mediaType) }

                TmdbRailPageSource.Kind.LIST ->
                    source.tmdbId?.let { listId ->
                        tmdbRepository.getKBListItems(listId, page)
                            ?.map { it.toContentItem(source.mediaType) }
                    }
            }.orEmpty()

            source.nextPage = page + 1
            val hasMore = newItems.size >= PAGE_SIZE

            _state.value = _state.value.copy(
                rails = _state.value.rails.map { rail ->
                    if (rail.sourceId == railId) {
                        rail.copy(items = rail.items + newItems)
                    } else {
                        rail
                    }
                },
                pagingStates = _state.value.pagingStates +
                    (railId to KBRailPagingState(hasMore = hasMore))
            )

            resolveAndPreload(
                listOf(KBRail(railId, "", "", newItems))
            )
        }
    }

    private fun TmdbDiscoverItem.toContentItem(
        mediaType: String?
    ): KBContentItem {
        // Same vocabulary as KBContentLoader: TMDB's "tv" becomes
        // "series" so appended pages match the initially loaded rails and
        // the rail-type suffix always displays "Series".
        val resolvedType = when (mediaType?.lowercase()) {
            "series", "show", "tv" -> "series"
            else -> mediaType?.lowercase()
                ?: if (firstAirDate != null) "series" else "movie"
        }
        return KBContentItem(
            id = id.toString(),
            type = resolvedType,
            title = title ?: name,
            posterUrl = posterPath?.takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = backdropPath?.takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w1280$it" },
            year = releaseDate?.take(4)?.takeIf { it.isNotBlank() }
                ?: firstAirDate?.take(4)?.takeIf { it.isNotBlank() },
            rating = voteAverage,
            tmdbId = id
        )
    }

    fun selectSource(sourceId: String?) {
        _selectedSourceId.value = sourceId
    }

    private suspend fun resolveAndPreload(rails: List<KBRail>) {
        val targets = rails
            .flatMap { it.items }
            .mapNotNull { item ->
                val normalized = normalizeType(item.type) ?: return@mapNotNull null
                item.tmdbId?.let { lookupKey(it, normalized) to normalized }
            }
            .distinctBy { it.first }
            .take(300)

        if (targets.isEmpty()) return

        val alreadyResolved = _resolvedIds.value
        val missing = targets.filterNot { alreadyResolved.containsKey(it.first) }
        if (missing.isEmpty()) return

        try {
            val resolvedPairs = supervisorScope {
                missing.map { (key, normalizedType) ->
                    async {
                        val tmdbId = key.substringAfter("::").toIntOrNull()
                        val imdbId = tmdbId?.let {
                            runCatching {
                                tmdbRepository.resolveImdbId(it, normalizedType)
                            }.getOrNull()
                        }
                        key to imdbId
                    }
                }.map { it.await() }
            }.filter { (_, imdbId) -> !imdbId.isNullOrBlank() }

            if (resolvedPairs.isEmpty()) return

            val resolved = resolvedPairs.filter { it.second != null }
                .associate { it.first to it.second!! }

            _resolvedIds.value = alreadyResolved + resolved

            watchedStatusRepository.preload(
                resolvedPairs.mapNotNull { (key, imdbId) ->
                    val type = missing.firstOrNull { it.first == key }?.second
                    if (imdbId != null && type != null) imdbId to type else null
                }.distinct()
            )
        } catch (e: Exception) {
            Log.e("KB_FOLDER_VM", "resolveAndPreload failed: ${e.message}", e)
        }
    }

    /** Long-press "Go to Details": resolve TMDB id -> imdb id, then navigate. */
    fun resolveAndNavigate(
        item: KBContentItem,
        onNavigateDetail: (String, String) -> Unit
    ) {
        val normalized = normalizeType(item.type) ?: return
        viewModelScope.launch {
            val imdbId = item.tmdbId?.let { tmdbId ->
                _resolvedIds.value[lookupKey(tmdbId, normalized)]
                    ?: runCatching {
                        tmdbRepository.resolveImdbId(tmdbId, normalized)
                    }.getOrNull()
            } ?: item.id.takeIf { it.startsWith("tt") }

            if (!imdbId.isNullOrBlank()) {
                onNavigateDetail(normalized, imdbId)
            }
        }
    }

    fun markAsWatched(item: KBContentItem) {
        viewModelScope.launch {
            val normalized = normalizeType(item.type) ?: return@launch
            val imdbId = resolveForWatchAction(item, normalized) ?: return@launch
            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, normalized)
            }
        }
    }

    fun markUnwatched(item: KBContentItem) {
        viewModelScope.launch {
            val normalized = normalizeType(item.type) ?: return@launch
            val imdbId = resolveForWatchAction(item, normalized) ?: return@launch
            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalized)
            }
        }
    }

    private suspend fun resolveForWatchAction(
        item: KBContentItem,
        normalizedType: String
    ): String? {
        val imdbId = item.tmdbId?.let { tmdbId ->
            _resolvedIds.value[lookupKey(tmdbId, normalizedType)]
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                ?.also { resolved ->
                    _resolvedIds.value =
                        _resolvedIds.value + (lookupKey(tmdbId, normalizedType) to resolved)
                }
        } ?: item.id.takeIf { it.startsWith("tt") }
        return imdbId
    }

    private fun normalizeType(raw: String?): String? =
        when (raw?.lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "series"
            else -> null
        }

    companion object {
        private const val PAGE_SIZE = 20

        // Same dwell as HomeViewModel before hero network work starts.
        private const val HERO_RESOLVE_DWELL_MS = 250L
    }
}
