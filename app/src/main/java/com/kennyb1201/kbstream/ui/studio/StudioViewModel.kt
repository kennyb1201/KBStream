package com.kennyb1201.kbstream.ui.studio

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.StudioSection
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
import kotlinx.coroutines.supervisorScope

data class StudioRailPagingState(
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _sections = MutableStateFlow<List<StudioSection>>(emptyList())
    val sections: StateFlow<List<StudioSection>> = _sections.asStateFlow()

    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pagingStates = MutableStateFlow<Map<String, StudioRailPagingState>>(emptyMap())
    val pagingStates: StateFlow<Map<String, StudioRailPagingState>> = _pagingStates.asStateFlow()

    private val _logoUrl = MutableStateFlow<String?>(null)
    val logoUrl: StateFlow<String?> = _logoUrl.asStateFlow()

    private val _companyInfo = MutableStateFlow<TmdbCompanyDetail?>(null)
    val companyInfo: StateFlow<TmdbCompanyDetail?> = _companyInfo.asStateFlow()

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

    private var currentId: Int? = null
    private var currentIsNetwork: Boolean = false

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    fun load(id: Int, isNetwork: Boolean) {
        val isSameRoute =
            currentId == id &&
            currentIsNetwork == isNetwork &&
            _sections.value.isNotEmpty()

        if (isSameRoute) return

        currentId = id
        currentIsNetwork = isNetwork

        viewModelScope.launch {
            _isLoading.value = true
            _sections.value = emptyList()
            _resolvedIds.value = emptyMap()
            _pagingStates.value = emptyMap()
            _logoUrl.value = null
            _companyInfo.value = null

            // Clear logo + blurb for the header. Networks use their own TMDB
            // endpoints (a network id is not a company id), so route by type.
            try {
                _logoUrl.value = tmdbRepository.getEntityLogoUrl(id, isNetwork)
            } catch (e: Exception) {
                Log.w("STUDIO_VM", "Logo lookup failed for id=$id", e)
            }
            try {
                _companyInfo.value = tmdbRepository.getEntityDetail(id, isNetwork)
            } catch (e: Exception) {
                Log.w("STUDIO_VM", "Entity detail failed for id=$id", e)
            }

            try {
                val result = if (isNetwork) {
                    tmdbRepository.getByNetwork(id)
                } else {
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
                Log.e("STUDIO_VM", "load failed for id=$id isNetwork=$isNetwork", e)
            } finally {
                _isLoading.value = false
            }

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
                val page: TagRailPage = if (currentIsNetwork) {
                    tmdbRepository.getNetworkRailPage(screenId, title, pageNumber)
                } else {
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
                        nextPage = pageNumber + 1,
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
                            tmdbRepository.resolveImdbId(tmdbId, mediaType)
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
