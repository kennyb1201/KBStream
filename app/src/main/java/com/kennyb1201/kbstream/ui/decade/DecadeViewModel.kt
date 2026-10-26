package com.kennyb1201.kbstream.ui.decade

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.history.TitleProfileOwnership
import com.kennyb1201.kbstream.data.history.titleOwnedByAnotherProfile
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.CrossBase
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TagRailPage
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import com.kennyb1201.kbstream.ui.tag.RailPagingState
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap

/**
 * ViewModel for the Decade screen (Screen.Decade): TMDB discover rails for
 * one decade, movies and series in their own rails with Popular / Top Rated
 * sorts — the same structure as the genre/keyword screens but without the
 * RECENT rails (a decade is old by definition, "recent" adds nothing).
 * Shares [RailPagingState] with TagViewModel so the infinite-scroll rails
 * behave identically.
 */
class DecadeViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository.getInstance(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    private val _sections = MutableStateFlow<List<StudioSection>>(emptyList())
    val sections: StateFlow<List<StudioSection>> = _sections.asStateFlow()

    private val _resolvedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedIds: StateFlow<Map<String, String>> = _resolvedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Surfaced to the screen so a failed load reads as a failure instead of
    // an empty decade (same contract as TagViewModel/ActorViewModel).
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pagingStates = MutableStateFlow<Map<String, RailPagingState>>(emptyMap())
    val pagingStates: StateFlow<Map<String, RailPagingState>> = _pagingStates.asStateFlow()

    // Same reactive watched-keys pipeline as TagViewModel/StudioViewModel.
    val watchedKeys: StateFlow<Set<String>> = combine(
        _sections,
        _resolvedIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { sections: List<StudioSection>, resolvedMap: Map<String, String>, _ ->
        // No `resolvedMap.isEmpty()` guard: the badges no longer depend on the
        // resolution pass at all (see the tmdb-flavored candidate below), so a
        // load whose every resolution came back null still badges its rails.
        if (sections.isEmpty()) {
            emptySet()
        } else {
            val siblingOwned = siblingOwnedMarkerIdsFor(sections, resolvedMap)
            sections.flatMap { it.items }
                .flatMap { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@flatMap emptyList()
                    // The keys this title has: the resolved IMDB flavor first,
                    // the raw `tmdb:<n>` one second (see [WatchedStatusKeys],
                    // which owns the rule and is where the past-the-cap and
                    // no-resolution cases are tested). Both are emitted when both
                    // match, so the tile's own IMDB check still hits first.
                    WatchedStatusKeys.watchedKeysOf(
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        resolvedImdbId = resolvedMap[lookupKey(tmdbId, mediaType)],
                        matches = watchedStatusRepository::isWatchedCached
                    )
                }
                .toSet()
                // A title another profile positively owns draws no checkmark
                // here, however the marker resolved - the same filter Home's
                // and the actor page's sets publish through, which these were
                // missing (see [siblingOwnedMarkerIdsFor]).
                .filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) }
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
        // Same two candidates as watchedKeys, against the partially-watched
        // state, and the same dropped `resolvedMap.isEmpty()` guard.
        if (sections.isEmpty()) {
            emptySet()
        } else {
            val siblingOwned = siblingOwnedMarkerIdsFor(sections, resolvedMap)
            sections.flatMap { it.items }
                .flatMap { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@flatMap emptyList()
                    WatchedStatusKeys.watchedKeysOf(
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        resolvedImdbId = resolvedMap[lookupKey(tmdbId, mediaType)],
                        matches = watchedStatusRepository::isPartiallyWatchedCached
                    )
                }
                .toSet()
                // Same ownership filter as the checkmark set above, and the same
                // return to a Set before the subtraction.
                .filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) }
                .toSet() - watchedKeys.value
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    // Caps parallel TMDB imdb-id lookups (same rationale as TagViewModel).
    private val imdbResolveSemaphore = Semaphore(permits = 8)

    private var currentDecadeStart: Int? = null

    // Genre chip filter: when non-null, rails re-run with the selected
    /**
     * The rail load in flight, if any. A decade screen's rails stream in (see
     * `streamBrowseSections`), so a load outlives the call that started it -
     * a genre chip, or a second decade, replaces the one before it.
     */
    private var loadJob: Job? = null

    /**
     * Which load owns the state. Bumped by every [load], so rails arriving
     * from a replaced load are dropped instead of being written over the
     * newer load's.
     */
    private var loadGeneration = 0

    // genre ANDed onto the decade's year window.
    private var currentGenreId: Int? = null
    private val _browseGenres = MutableStateFlow<List<TmdbGenre>>(emptyList())
    val browseGenres: StateFlow<List<TmdbGenre>> = _browseGenres.asStateFlow()

    private val _selectedGenreId = MutableStateFlow<Int?>(null)
    val selectedGenreId: StateFlow<Int?> = _selectedGenreId.asStateFlow()

    /** Genre chips act on the loaded decade. */
    fun crossGenreBase(): CrossBase? {
        val start = currentDecadeStart ?: return null
        return CrossBase(kind = "decade", id = start)
    }

    /** Select/clear the genre filter and reload the rails. */
    fun onGenreSelected(genreId: Int?) {
        val start = currentDecadeStart ?: return
        if (genreId == currentGenreId) return
        load(start, genreId)
    }

    /**
     * The key a tile is compared under. Delegates to [WatchedStatusKeys.key],
     * so the keys the screen tests and the keys the sets above are built from
     * cannot describe the same title differently.
     */
    fun watchedKey(id: String, type: String): String = WatchedStatusKeys.key(id, type)

    fun lookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    /**
     * Ids (IMDb or `tmdb:<n>`) of this page's titles that ANOTHER profile
     * positively owns, so their badge is never painted here however the marker
     * resolved.
     *
     * The watched cache key is `"type::id"` with no profile component (see
     * WatchedStatusRepository.cacheKey) and the tracker feeds behind it are per
     * ACCOUNT - so a kids-profile completion can badge a title on this
     * profile's decade page, the same leak shape Home's and the actor page's
     * fixes closed (HomeViewModel.keyOwnedByAnotherProfile,
     * ActorViewModel.siblingOwnedMarkerIdsFor). These rails read the same cache
     * family through the same methods and skipped that filter.
     */
    private fun siblingOwnedMarkerIdsFor(
        sections: List<StudioSection>,
        resolvedMap: Map<String, String>
    ): Set<String> {
        val activeProfileId = ProfileManager.activeProfile.value?.id
            ?.takeIf { it.isNotBlank() }
            ?: return emptySet()

        val owners = TitleProfileOwnership.snapshot(getApplication())
        if (owners.isEmpty()) return emptySet()

        return sections
            .flatMap { it.items }
            .filter { studioItem ->
                titleOwnedByAnotherProfile(
                    ownerByTitleKey = owners,
                    type = studioItem.mediaType,
                    title = studioItem.item.title ?: studioItem.item.name,
                    activeProfileId = activeProfileId
                )
            }
            .flatMap { studioItem ->
                val mediaType = normalizeMediaType(studioItem.mediaType)
                listOfNotNull(
                    WatchedStatusKeys.tmdbKeyId(studioItem.item.id),
                    mediaType?.let { resolvedMap[lookupKey(studioItem.item.id, it)] }
                )
            }
            .toSet()
    }

    /** True when [key] (`type::id`) names a title another profile owns. */
    private fun keyOwnedByAnotherProfile(key: String, siblingOwned: Set<String>): Boolean =
        siblingOwned.any { id -> key.endsWith("::$id") }

    fun load(decadeStart: Int, genreId: Int? = null) {
        // Same-route guard so Back-restore doesn't reload and clear state.
        // The genre MUST be part of the comparison: a chip click re-enters
        // with the same decade but a new genre, and the old guard (decade
        // only) swallowed every click — the chips did nothing.
        if (currentDecadeStart == decadeStart &&
            currentGenreId == genreId &&
            _sections.value.isNotEmpty()
        ) return
        currentDecadeStart = decadeStart
        currentGenreId = genreId
        _selectedGenreId.value = genreId
        viewModelScope.launch {
            _browseGenres.value = tmdbRepository.getBrowseGenres()
        }

        loadJob?.cancel()
        val generation = ++loadGeneration

        // Rails are published by their index in the screen's rail order (see
        // streamBrowseSections), so the growing page is held as slots rather
        // than appended to as it arrives.
        val slots = ConcurrentHashMap<Int, StudioSection>()

        val onSection: suspend (Int, StudioSection) -> Unit = { index, section ->
            if (generation == loadGeneration) {
                slots[index] = section
                _sections.value = slots.entries.sortedBy { it.key }.map { it.value }
                // The skeleton goes as soon as there is a rail to look at: the
                // rest of the page keeps filling in behind the viewer.
                _isLoading.value = false
            }
        }

        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _sections.value = emptyList()
            _resolvedIds.value = emptyMap()
            _pagingStates.value = emptyMap()

            try {
                val genreId = currentGenreId
                val result = if (genreId != null) {
                    tmdbRepository.getInitialCrossGenreSections(
                        base = CrossBase("decade", decadeStart),
                        genreId = genreId,
                        onSection = onSection
                    )
                } else {
                    tmdbRepository.getInitialDecadeSections(decadeStart, onSection)
                }

                if (generation != loadGeneration) return@launch

                _sections.value = result
                _pagingStates.value = result.associate { section ->
                    section.title to RailPagingState(
                        nextPage = 2,
                        hasMore = section.items.isNotEmpty(),
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                if (generation == loadGeneration) {
                    _error.value = e.message ?: "Failed to load decade"
                }
                Log.e("DECADE_VM", "load failed for decade $decadeStart", e)
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }

            val loadedSections = _sections.value
            if (loadedSections.isNotEmpty()) {
                launch {
                    resolveAndPreloadWatched(loadedSections)
                }
            }
        }
    }

    fun loadMoreSection(title: String) {
        val decadeStart = currentDecadeStart ?: return
        // Everything this page is FOR, captured at the call: the decade, the
        // genre and the load that owns the state. Tag, Studio and the KB folder
        // page were all fixed this way; Decade was the sibling that was missed,
        // so a page landing after a decade/genre switch merged into the
        // identically-titled rail of the NEW page - corrupting its nextPage and
        // hasMore, and re-resolving its watched badges against the wrong list.
        val generation = loadGeneration
        val genreId = currentGenreId
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
                val page: TagRailPage =
                    if (genreId != null) {
                        tmdbRepository.getCrossGenreRailPage(
                            base = CrossBase("decade", decadeStart),
                            genreId = genreId,
                            title = title,
                            page = pageNumber
                        )
                    } else {
                        tmdbRepository.getDecadeRailPage(decadeStart, title, pageNumber)
                    }

                // A newer load owns the state now. Rails are matched by TITLE, so
                // this is not merely stale data - it is data from one decade's
                // list landing in another decade's rail of the same name.
                if (generation != loadGeneration) return@launch

                val existingSection = _sections.value.firstOrNull { it.title == title }
                if (existingSection != null && page.items.isNotEmpty()) {
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
                    resolveAndPreloadWatched(updatedSections)
                }

                _pagingStates.value = _pagingStates.value.toMutableMap().apply {
                    this[title] = RailPagingState(
                        // Resumes after whatever page deepening already merged
                        // for this rail (see TagRailPage.nextPage).
                        nextPage = page.nextPage,
                        hasMore = page.hasMore,
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                Log.e("DECADE_VM", "loadMoreSection failed for $title", e)
                if (generation != loadGeneration) return@launch
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

            // The badge preload, UNCAPPED and ahead of the resolution below:
            // this is the raw `tmdb:<n>` flavor the watched pipeline already
            // answers under, and it costs local DB plus the already-cached Simkl
            // snapshot - no network. It has to run even when every resolution
            // comes back null, which is exactly the case the `resolved.isEmpty()`
            // return below used to leave bare. HomeViewModel's watched refresh
            // preloads its whole rail list the same way, and for the same
            // reason: the old tt-only view of the pipeline silently dropped
            // every tmdb-keyed item.
            val tmdbPairs = sections.flatMap { it.items }
                .mapNotNull { studioItem ->
                    val tmdbId = studioItem.item.id
                    val mediaType = normalizeMediaType(studioItem.mediaType) ?: return@mapNotNull null
                    if (tmdbId <= 0) null else "tmdb:$tmdbId" to mediaType
                }
                .distinct()
            watchedStatusRepository.preload(tmdbPairs)

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
                        val imdbId = runCatchingCancellable {
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

            val newResolvedIds = resolved.mapNotNull { (tmdbId, mediaType, imdbId) ->
                imdbId?.let { lookupKey(tmdbId, mediaType) to it }
            }.toMap()
            _resolvedIds.value = _resolvedIds.value + newResolvedIds

            val preloadItems = resolved
                .mapNotNull { (_, mediaType, imdbId) -> imdbId?.let { it to mediaType } }
                .distinct()

            watchedStatusRepository.preload(preloadItems)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a failure: rethrow so the scope that was
            // canceled stops here instead of logging it and clearing state as
            // if the work had simply failed.
            throw e
        } catch (e: Exception) {
            Log.e("DECADE_VM", "resolveAndPreloadWatched failed", e)
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
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            // The Detail route resolves a TMDB id itself, so a title TMDB has
            // no IMDb id for - or one whose resolution failed offline - still
            // opens its page instead of dying silently on the press. The
            // actor page's cards have done this all along; the browse cards
            // used to navigate only on a non-blank IMDb id and swallow the
            // press otherwise.
            val routeId = imdbId?.takeIf { it.isNotBlank() }
                ?: WatchedStatusKeys.tmdbKeyId(tmdbId)
            onNavigateDetail(normalizedType, routeId)
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
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (imdbId.isNullOrBlank()) {
                // No IMDb id to key the mark under: write it under this title's
                // own `tmdb:<n>` flavor, which the rail badge already consults
                // (see [WatchedStatusKeys]). Returning here instead was a silent
                // no-op on the menu row the viewer had just pressed - the actor
                // page's marks fall back the same way.
                runCatchingCancellable {
                    watchedStatusRepository.markWatchedLocal(
                        WatchedStatusKeys.tmdbKeyId(tmdbId),
                        normalizedType
                    )
                }.onFailure { e ->
                    Log.e("DECADE_VM", "markAsWatched failed tmdb=$tmdbId", e)
                }
                return@launch
            }

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatchingCancellable {
                watchedStatusRepository.markWatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("DECADE_VM", "markAsWatched failed tmdb=$tmdbId", e)
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
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (imdbId.isNullOrBlank()) {
                // Same fallback as markAsWatched: clear under this title's own
                // `tmdb:<n>` flavor rather than leaving the press a no-op.
                runCatchingCancellable {
                    watchedStatusRepository.markUnwatchedLocal(
                        WatchedStatusKeys.tmdbKeyId(tmdbId),
                        normalizedType
                    )
                }.onFailure { e ->
                    Log.e("DECADE_VM", "markUnwatched failed tmdb=$tmdbId", e)
                }
                return@launch
            }

            if (_resolvedIds.value[lookup] == null) {
                _resolvedIds.value = _resolvedIds.value + (lookup to imdbId)
            }

            runCatchingCancellable {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("DECADE_VM", "markUnwatched failed tmdb=$tmdbId", e)
            }
        }
    }
}
