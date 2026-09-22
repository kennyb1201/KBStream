package com.kennyb1201.kbstream.ui.library

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which flat view of the Library tab is showing.
 */
enum class LibraryFilter(val label: String) {
    // ALL merges My List + watchlists + local personal lists into one
    // de-duplicated grid. Listed first so it is the default focus target.
    ALL("ALL"),
    MY_LIST("MY LIST"),
    WATCHLIST("WATCHLIST"),
    LISTS("PERSONAL LISTS")
}

/**
 * Sort order for every Library view. "Added" keeps natural order (newest
 * first); the others sort by the fields each row carries.
 */
enum class LibrarySort(val label: String) {
    ADDED("ADDED"),
    TITLE("TITLE"),
    RELEASE_DATE("DATE"),
    RATING("RATING")
}

/**
 * Everything the Library tab renders, grouped by source so the screen can
 * show origin tags while keeping the merged list ordering stable.
 */
data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.ADDED,
    val loading: Boolean = true,

    // MY LIST: local, profile-scoped, works with no account connected.
    val localItems: List<LibraryItem> = emptyList(),

    // ALL: My List + watchlists + local personal lists merged and
    // de-duplicated (local rows win over remote duplicates).
    val allItems: List<LibraryItem> = emptyList(),

    // When true, rows whose watchedKey is in watchedKeys are hidden.
    val hideWatched: Boolean = false,

    // WATCHLIST: Simkl plan-to-watch + MDBList watchlist merged.
    val watchlistItems: List<LibraryItem> = emptyList(),

    // PERSONAL LISTS: local + MDBList lists (list picker) and their items.
    val lists: List<LibraryList> = emptyList(),
    val selectedList: LibraryList? = null,
    val selectedListItems: List<LibraryItem> = emptyList(),

    // TMDB rating per (type::imdb-or-tmdb key) for the sort + captions.
    val ratings: Map<String, Double> = emptyMap(),

    // Resolved poster URL per dedupe key, filled in during enrichment for
    // rows whose tracker payload carried no artwork (MDBList watchlists and
    // personal lists often omit it). Applied by [LibraryViewModel.pushDisplay]
    // so those grids show real posters instead of title-only placeholders.
    val posters: Map<String, String> = emptyMap(),

    // Watched badges: set of "type::imdbId" keys, same convention the
    // genre/actor screens use.
    val watchedKeys: Set<String> = emptyList<String>().toSet(),

    val simklConnected: Boolean = false,
    val mdbListConfigured: Boolean = false
)

/**
 * Library tab: personal lists and watchlists from local storage, Simkl and
 * MDBList in one screen. Every remote fetch fails soft — an unreachable
 * tracker keeps its section empty instead of breaking the whole tab.
 * Remote rows pass through the Kids Mode ceiling filter; ratings resolve
 * through the TMDB detail cache so the sort + captions match other
 * screens.
 */
class LibraryViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val simklRepository =
        SimklRepository.getInstance(application)
    private val watchedRepository =
        WatchedStatusRepository(application)
    private val tmdbRepository =
        TmdbRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Canonical (unfiltered) rows backing each view. The UI state carries
    // the *display* versions — sorted + UNWATCHED-filtered by [pushDisplay]
    // — so toggling filters never loses rows.
    private var canonicalAll: List<LibraryItem> = emptyList()
    private var canonicalLocal: List<LibraryItem> = emptyList()
    private var canonicalWatchlist: List<LibraryItem> = emptyList()
    private var canonicalListItems: List<LibraryItem> = emptyList()

    // Flattened items from every local personal list; feeds the ALL merge
    // without needing a list to be selected.
    private var localListFlatCache: List<LibraryItem> = emptyList()

    /** Bump to force a remote re-fetch (filter changes, pull-to-refresh). */
    private var requestVersion = 0

    init {
        // No refresh here: the screen calls [refresh] every time it is
        // opened, which is the behavior that matters — this ViewModel is
        // activity-scoped, so an init-only snapshot went stale the moment
        // the user added a title from another screen and came back.
        observeProfileSwitches()
    }

    /**
     * Profile-switch reset: this ViewModel is activity-scoped and its
     * init-only refresh captured the previous profile's My List / Simkl /
     * MDBList snapshot. Reopening Library after a switch showed the other
     * profile's saved items. Re-read everything from the incoming
     * profile's scoped stores (refresh() is fully per-call scoped).
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect {
                    if (first) {
                        first = false
                        return@collect
                    }
                    refresh()
                }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        val current = _uiState.value
        if (current.filter == filter) return
        _uiState.value = current.copy(filter = filter)
        when (filter) {
            LibraryFilter.ALL -> {
                // Rebuild from cached rows; remote refresh only if empty.
                if (canonicalAll.isEmpty()) refresh() else pushDisplay()
            }

            LibraryFilter.MY_LIST -> {
                _uiState.value = _uiState.value.copy(
                    localItems = sortItems(
                        LocalLibraryStore.myList(getApplication()),
                        _uiState.value.sort,
                        _uiState.value.ratings
                    )
                )
            }

            LibraryFilter.WATCHLIST -> refresh()

            LibraryFilter.LISTS -> {
                // Keep cached lists/items; refresh lists in background.
                refresh()
            }
        }
    }

    fun setSort(sort: LibrarySort) {
        val state = _uiState.value
        if (state.sort == sort) return
        _uiState.value = state.copy(sort = sort)
        pushDisplay()
    }

    /** Toggles the UNWATCHED chip: hides rows already marked watched. */
    fun toggleHideWatched() {
        val state = _uiState.value
        _uiState.value = state.copy(hideWatched = !state.hideWatched)
        pushDisplay()
    }

    fun selectList(list: LibraryList?) {
        _uiState.value = _uiState.value.copy(selectedList = list)
        if (list != null) {
            loadListItems(list)
        }
    }

    /** Creates a local personal list and selects it. */
    fun createLocalList(name: String) {
        viewModelScope.launch {
            val created = LocalLibraryStore.createList(getApplication(), name)
            if (created != null) {
                _uiState.value = _uiState.value.copy(
                    lists = _uiState.value.lists + created
                )
                selectList(created)
            }
        }
    }

    /** Deletes a local personal list (MDBList lists are remote-managed). */
    fun deleteLocalList(listId: Int) {
        viewModelScope.launch {
            LocalLibraryStore.deleteList(getApplication(), listId)
            val state = _uiState.value
            _uiState.value = state.copy(
                lists = state.lists.filter { it.id != listId },
                selectedList = state.selectedList?.takeIf { it.id != listId }
            )
            if (state.selectedList?.id == listId) {
                canonicalListItems = emptyList()
            }
            recomputeAll()
        }
    }

    /**
     * Long-press remove on any Library row: local rows drop locally,
     * MDBList rows mirror the removal remotely (best-effort). Simkl rows
     * are add-only (Simkl exposes no remove-from-watchlist endpoint), so
     * they offer no remove.
     */
    fun removeItem(item: LibraryItem) {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            when (item.source) {
                LibrarySource.LOCAL -> {
                    LibraryMirror.removeFromLibrary(
                        context = appContext,
                        scope = viewModelScope,
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId
                    )
                    canonicalLocal = LocalLibraryStore.myList(appContext)
                    recomputeAll()
                }

                LibrarySource.LOCAL_LIST -> {
                    val listId = item.listId ?: return@launch
                    LibraryMirror.removeFromLocalList(
                        context = appContext,
                        listId = listId,
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId
                    )
                    canonicalListItems = LocalLibraryStore.listItems(appContext, listId)
                    recomputeAll()
                    refreshListsOnly()
                }

                LibrarySource.MDBLIST_WATCHLIST -> {
                    LibraryMirror.removeFromLibrary(
                        context = appContext,
                        scope = viewModelScope,
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId
                    )
                    val key = LocalLibraryStore.dedupeKey(item)
                    canonicalWatchlist = canonicalWatchlist.filter {
                        LocalLibraryStore.dedupeKey(it) != key
                    }
                    recomputeAll()
                }

                LibrarySource.MDBLIST_LIST -> {
                    val listId = item.listId ?: return@launch
                    LibraryMirror.removeFromMdbList(
                        context = appContext,
                        scope = viewModelScope,
                        listId = listId,
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId
                    )
                    val key = LocalLibraryStore.dedupeKey(item)
                    canonicalListItems = canonicalListItems.filter {
                        LocalLibraryStore.dedupeKey(it) != key
                    }
                    pushDisplay()
                }

                // Simkl: add-only; no remove path exists on the API.
                LibrarySource.SIMKL_WATCHLIST, LibrarySource.SIMKL_LIST -> Unit
            }
        }
    }

    fun refresh() {
        val version = ++requestVersion
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)

            val appContext = getApplication<Application>()

            // Local first: instant, always present. These rows are published
            // before the tracker fetches below, so a slow (or unreachable)
            // Simkl/MDBList can never leave this profile's own My List behind
            // a "Loading…" placeholder — which is also what made an
            // "Add to Library" look like it had done nothing when this tab
            // was opened right after adding.
            val localItems = LocalLibraryStore.myList(appContext)
            val localLists = LocalLibraryStore.userLists(appContext)
            val localListItems = localLists
                .filter { it.id < 0 }
                .flatMap { LocalLibraryStore.listItems(appContext, it.id) }
            localListFlatCache = localListItems
            canonicalLocal = localItems
            canonicalAll = mergeAllSources(
                listOf(localItems, canonicalWatchlist, localListItems)
            )
            _uiState.value = _uiState.value.copy(
                loading = false,
                lists = localLists + _uiState.value.lists.filter { it.id > 0 }
            )
            pushDisplay()

            val simklConnected =
                simklRepository.isConfigured() && simklRepository.hasToken()
            val mdbListConfigured = MdbListClient.isConfigured(appContext)

            // WATCHLIST merge: Simkl plan-to-watch + MDBList watchlist.
            val simklItems = if (simklConnected) {
                runCatching { simklRepository.getWatchlistItems() }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Simkl watchlist fetch failed: ${it.message}"
                        )
                    }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val mdbListWatchlist = if (mdbListConfigured) {
                runCatching {
                    MdbListClient.getWatchlist(appContext).map { entry ->
                        LibraryItem(
                            source = LibrarySource.MDBLIST_WATCHLIST,
                            mediaType = entry.mediaType,
                            title = entry.title ?: "Untitled",
                            year = entry.year,
                            posterUrl = entry.poster,
                            imdbId = entry.imdbId,
                            tmdbId = entry.tmdbId
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "MDBList watchlist fetch failed: ${it.message}")
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val lists = localLists + if (mdbListConfigured) {
                runCatching {
                    MdbListClient.getUserLists(appContext).map { list ->
                        LibraryList(
                            id = list.id,
                            name = list.name,
                            itemCount = list.itemCount,
                            source = LibrarySource.MDBLIST_LIST
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "MDBList lists fetch failed: ${it.message}")
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            if (version != requestVersion) return@launch

            val watchlistMerged = kidsFiltered(
                mergeById(simklItems + mdbListWatchlist)
            )

            // Canonical rows: ALL merges My List + watchlists + every local
            // personal list, local rows winning over remote duplicates. The
            // local rows (and localListFlatCache) were already published
            // above; only the remote halves are new here.
            canonicalWatchlist = watchlistMerged
            canonicalListItems = emptyList()
            canonicalAll = kidsFiltered(
                mergeAllSources(listOf(localItems, watchlistMerged, localListItems))
            )

            _uiState.value = _uiState.value.copy(
                loading = false,
                lists = lists,
                simklConnected = simklConnected,
                mdbListConfigured = mdbListConfigured
            )
            pushDisplay()

            // Enrichment pass: kids filter + ratings + watched badges.
            // canonicalAll is the superset (local + watchlist + local lists).
            enrich(canonicalAll, version)
        }
    }

    /** Re-fetches just the lists rail (after local-list mutations). */
    private fun refreshListsOnly() {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val localLists = LocalLibraryStore.userLists(appContext)
            val mdbListConfigured = MdbListClient.isConfigured(appContext)
            val mdbLists = if (mdbListConfigured) {
                runCatching {
                    MdbListClient.getUserLists(appContext).map { list ->
                        LibraryList(
                            id = list.id,
                            name = list.name,
                            itemCount = list.itemCount,
                            source = LibrarySource.MDBLIST_LIST
                        )
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _uiState.value = _uiState.value.copy(lists = localLists + mdbLists)
        }
    }

    private fun loadListItems(list: LibraryList) {
        val version = requestVersion
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val items: List<LibraryItem> = if (list.id < 0) {
                LocalLibraryStore.listItems(appContext, list.id)
            } else {
                val entries = runCatching {
                    MdbListClient.getListItems(appContext, list.id)
                }.onFailure {
                    Log.w(TAG, "MDBList list items fetch failed: ${it.message}")
                }.getOrDefault(emptyList())
                entries.map { entry ->
                    LibraryItem(
                        source = LibrarySource.MDBLIST_LIST,
                        mediaType = entry.mediaType,
                        title = entry.title ?: "Untitled",
                        year = entry.year,
                        posterUrl = entry.poster,
                        imdbId = entry.imdbId,
                        tmdbId = entry.tmdbId,
                        listId = list.id,
                        listName = list.name
                    )
                }
            }

            if (version != requestVersion) return@launch
            canonicalListItems = items
            pushDisplay()
            enrich(items, version)
        }
    }

    /**
     * Background enrichment for a batch of rows: resolves ratings through
     * the TMDB detail cache, applies the Kids Mode ceiling to remote
     * rows, and collects watched badges.
     */
    private fun enrich(items: List<LibraryItem>, version: Int) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val appContext = getApplication<Application>()

            data class Resolved(
                val key: String,
                val rating: Double?,
                val watched: String?,
                val poster: String?
            )

            val resolved = coroutineScope {
                items.distinctBy { LocalLibraryStore.dedupeKey(it) }
                    .take(250)
                    .map { item ->
                        async {
                            val normalizedType = when (item.mediaType.lowercase()) {
                                "tv", "series" -> "series"
                                else -> "movie"
                            }
                            var imdbId = item.imdbId
                            if (imdbId == null && item.tmdbId != null) {
                                imdbId = runCatching {
                                    tmdbRepository.resolveImdbId(item.tmdbId, normalizedType)
                                }.getOrNull()
                            }

                            var rating: Double? = null
                            var watched: String? = null

                            if (imdbId != null) {
                                if (watchedRepository.isWatchedCached(imdbId, normalizedType)) {
                                    watched = "$normalizedType::$imdbId"
                                }
                            }

                            // Ratings come from the shared TMDB detail cache
                            // (disk + memory), so a title already enriched on
                            // any screen costs nothing here.
                            val detail = runCatching {
                                tmdbRepository.fetchEnrichedMetaCached(
                                    imdbId = imdbId ?: "tmdb:${item.tmdbId}",
                                    type = normalizedType
                                )
                            }.getOrNull()
                            rating = detail?.voteAverage?.takeIf { it > 0.0 }

                            // Poster backfill: only needed when the tracker
                            // payload did not carry artwork. Reuses the same
                            // cached TMDB detail this block already fetched
                            // for the rating, so it costs no extra request.
                            val poster = if (item.posterUrl.isNullOrBlank()) {
                                detail?.posterPath?.takeIf { it.isNotBlank() }
                                    ?.let { TmdbRepository.POSTER_BASE + it }
                            } else {
                                null
                            }

                            Resolved(
                                key = LocalLibraryStore.dedupeKey(item),
                                rating = rating,
                                watched = watched,
                                poster = poster
                            )
                        }
                    }.awaitAll()
            }

            if (version != requestVersion) return@launch

            val ratings = _uiState.value.ratings.toMutableMap()
            val posters = _uiState.value.posters.toMutableMap()
            resolved.forEach { r ->
                r.rating?.let { ratings[r.key] = it }
                r.poster?.let { posters[r.key] = it }
            }

            _uiState.value = _uiState.value.copy(
                ratings = ratings,
                posters = posters,
                watchedKeys = _uiState.value.watchedKeys +
                    resolved.mapNotNull { it.watched }.toSet()
            )
            pushDisplay()
        }
    }

    /**
     * Drops rows above the active kids profile's ceiling. Local rows the
     * user (or parent) saved intentionally are kept; only remote rows are
     * filtered, matching how Home treats account-wide Simkl items.
     */
    private suspend fun kidsFiltered(items: List<LibraryItem>): List<LibraryItem> {
        if (tmdbRepository.kidsMaxAge() == null) return items
        val remote = items.filter { it.source != LibrarySource.LOCAL }
        if (remote.isEmpty()) return items
        val allowed = tmdbRepository.kidsFilter(remote) { row ->
            val type = when (row.mediaType.lowercase()) {
                "tv", "series" -> "series"
                else -> "movie"
            }
            (row.tmdbId ?: -1) to type
        }
        return items.filter { it.source == LibrarySource.LOCAL || allowed.contains(it) }
    }

    /**
     * Rebuilds every display list from the canonical rows: sort + kids
     * filter + UNWATCHED toggle in one place, so toggling filters never
     * loses rows and enrichment updates stay consistent everywhere.
     */
    private fun pushDisplay() {
        val state = _uiState.value
        val sort = state.sort
        val ratings = state.ratings
        val watched = state.watchedKeys
        val hide = state.hideWatched
        val posters = state.posters
        _uiState.value = state.copy(
            allItems = applyUnwatched(
                applyPosters(sortItems(canonicalAll, sort, ratings), posters), watched, hide
            ),
            localItems = applyUnwatched(
                applyPosters(sortItems(canonicalLocal, sort, ratings), posters), watched, hide
            ),
            watchlistItems = applyUnwatched(
                applyPosters(sortItems(canonicalWatchlist, sort, ratings), posters), watched, hide
            ),
            selectedListItems = applyUnwatched(
                applyPosters(sortItems(canonicalListItems, sort, ratings), posters), watched, hide
            )
        )
    }

    /**
     * Fills in missing poster URLs from the resolved-poster map (keyed by
     * dedupe key). Rows that already carry a poster are left untouched, so a
     * tracker-provided URL always wins over the TMDB fallback.
     */
    private fun applyPosters(
        items: List<LibraryItem>,
        posters: Map<String, String>
    ): List<LibraryItem> {
        if (posters.isEmpty()) return items
        return items.map { item ->
            if (!item.posterUrl.isNullOrBlank()) {
                item
            } else {
                posters[LocalLibraryStore.dedupeKey(item)]
                    ?.let { item.copy(posterUrl = it) }
                    ?: item
            }
        }
    }

    /**
     * Rebuilds just the ALL view (after local-list mutations) without a
     * full remote refresh.
     */
    private fun recomputeAll() {
        val appContext = getApplication<Application>()
        localListFlatCache = _uiState.value.lists
            .filter { it.id < 0 }
            .flatMap { LocalLibraryStore.listItems(appContext, it.id) }
        canonicalAll = mergeAllSources(
            listOf(canonicalLocal, canonicalWatchlist, localListFlatCache)
        )
        pushDisplay()
    }

    /** ALL-view merge: de-duped across every source, input order kept. */
    private fun mergeAllSources(sources: List<List<LibraryItem>>): List<LibraryItem> =
        mergeById(sources.flatten())

    /** Drops duplicate titles across trackers, Simkl winning over MDBList. */
    private fun mergeById(items: List<LibraryItem>): List<LibraryItem> {
        val seen = LinkedHashMap<String, LibraryItem>()
        items.forEach { item ->
            val key = LocalLibraryStore.dedupeKey(item)
            if (!seen.containsKey(key)) {
                seen[key] = item
            }
        }
        return seen.values.toList()
    }

    private fun sortItems(
        items: List<LibraryItem>,
        sort: LibrarySort,
        ratings: Map<String, Double>
    ): List<LibraryItem> = sortLibraryItems(items, sort, ratings)

    companion object {
        private const val TAG = "LIBRARY"
    }
}

/**
 * Pure ordering for the Library tab's sort chips — extracted from the
 * ViewModel so the JVM test suite can pin the contracts (stable ADDED
 * order, case-insensitive titles, missing years/ratings sinking to the
 * bottom) without an Android dependency.
 */
internal fun sortLibraryItems(
    items: List<LibraryItem>,
    sort: LibrarySort,
    ratings: Map<String, Double>
): List<LibraryItem> = when (sort) {
    LibrarySort.ADDED -> items
    LibrarySort.TITLE -> items.sortedBy { it.title.lowercase() }
    LibrarySort.RELEASE_DATE -> items.sortedWith(
        compareByDescending { it.year ?: 0 }
    )
    LibrarySort.RATING -> items.sortedWith(
        compareByDescending { ratings[LocalLibraryStore.dedupeKey(it)] ?: 0.0 }
    )
}

/**
 * Pure UNWATCHED filter: when [hide] is set, rows whose watchedKey is
 * present in [watchedKeys] are dropped (rows without a key — no IMDB id
 * resolved yet — always stay). Extracted alongside [sortLibraryItems]
 * for the same testability reasons.
 */
internal fun applyUnwatched(
    items: List<LibraryItem>,
    watchedKeys: Set<String>,
    hide: Boolean
): List<LibraryItem> {
    if (!hide || watchedKeys.isEmpty()) return items
    return items.filter { item ->
        val key = item.watchedKey()
        key == null || !watchedKeys.contains(key)
    }
}
