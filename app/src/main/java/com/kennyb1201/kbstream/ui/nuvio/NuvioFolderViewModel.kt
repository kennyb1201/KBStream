package com.kennyb1201.kbstream.ui.nuvio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.nuvio.NuvioContentItem
import com.kennyb1201.kbstream.data.nuvio.NuvioContentLoader
import com.kennyb1201.kbstream.data.nuvio.NuvioFolder
import com.kennyb1201.kbstream.data.nuvio.NuvioRail
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

/** Per-rail load-more state, mirroring StudioScreen's paging model. */
data class NuvioRailPagingState(
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

/**
 * Loads one Nuvio folder: every source becomes a rail; the hero shows the
 * folder's hosted backdrop art (falling back to the first rail's first
 * item's backdrop) exactly like Nuvio's "follow home layout".
 */
class NuvioFolderViewModel(application: Application) : AndroidViewModel(application) {

    private val contentLoader = NuvioContentLoader(application)
    private val tmdbRepository = TmdbRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)
    private val repository =
        com.kennyb1201.kbstream.data.nuvio.NuvioRepository(application)

    data class UiState(
        val folder: NuvioFolder? = null,
        val showAllTab: Boolean = false,
        val rails: List<NuvioRail> = emptyList(),
        val pagingStates: Map<String, NuvioRailPagingState> = emptyMap(),
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

    // Only TMDB-backed rails (discover / list) paginate; trakt and addon
    // sources render their first page.
    private val tmdbRailSources = mutableMapOf<String, TmdbRailPageSource>()
    private var currentFolderId: String? = null

    private data class TmdbRailPageSource(
        val kind: Kind,
        val tmdbId: Int?,
        val mediaType: String,
        val sortBy: String?,
        val filters: com.kennyb1201.kbstream.data.nuvio.NuvioFilters?,
        var nextPage: Int = 2
    ) {
        enum class Kind { DISCOVER, LIST }
    }

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, type: String): String =
        "${type.lowercase()}::$tmdbId"

    /** Hero item: the first item of the first non-empty rail (Nuvio behavior). */
    fun heroItem(folder: NuvioFolder?, rails: List<NuvioRail>): NuvioContentItem? =
        rails.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()

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

    fun load(folder: NuvioFolder, showAllTab: Boolean) {
        val folderKey = folder.id ?: folder.title
        val isSameRoute = currentFolderId == folderKey && _state.value.folder === folder
        if (isSameRoute && !_state.value.isLoading) return

        currentFolderId = folderKey
        tmdbRailSources.clear()
        _selectedSourceId.value = null
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
                        rail.sourceId to NuvioRailPagingState(
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
                Log.e("NUVIO_FOLDER_VM", "load failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Couldn't load this folder."
                )
            }
        }
    }

    private fun registerTmdbRailSources(folder: NuvioFolder, rails: List<NuvioRail>) {
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
                        sortBy = source.sortBy ?: "popularity.desc",
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
                    tmdbRepository.discoverNuvio(
                        mediaType = source.mediaType,
                        page = page,
                        sortBy = source.sortBy,
                        filters = source.filters
                    )?.map { it.toContentItem(source.mediaType) }

                TmdbRailPageSource.Kind.LIST ->
                    source.tmdbId?.let { listId ->
                        tmdbRepository.getNuvioListItems(listId, page)
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
                    (railId to NuvioRailPagingState(hasMore = hasMore))
            )

            resolveAndPreload(
                listOf(NuvioRail(railId, "", "", newItems))
            )
        }
    }

    private fun com.kennyb1201.kbstream.data.tmdb.TmdbDiscoverItem.toContentItem(
        mediaType: String?
    ): NuvioContentItem {
        val resolvedType = mediaType
            ?: if (firstAirDate != null) "series" else "movie"
        return NuvioContentItem(
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

    private suspend fun resolveAndPreload(rails: List<NuvioRail>) {
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
            Log.e("NUVIO_FOLDER_VM", "resolveAndPreload failed: ${e.message}", e)
        }
    }

    /** Long-press "Go to Details": resolve TMDB id -> imdb id, then navigate. */
    fun resolveAndNavigate(
        item: NuvioContentItem,
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

    fun markAsWatched(item: NuvioContentItem) {
        viewModelScope.launch {
            val normalized = normalizeType(item.type) ?: return@launch
            val imdbId = resolveForWatchAction(item, normalized) ?: return@launch
            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, normalized)
            }
        }
    }

    fun markUnwatched(item: NuvioContentItem) {
        viewModelScope.launch {
            val normalized = normalizeType(item.type) ?: return@launch
            val imdbId = resolveForWatchAction(item, normalized) ?: return@launch
            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalized)
            }
        }
    }

    private suspend fun resolveForWatchAction(
        item: NuvioContentItem,
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
    }
}
