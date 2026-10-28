package com.kennyb1201.kbstream.ui.library

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.history.titleOwnedByAnotherProfile
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LibraryTombstones
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.library.TastePlacement
import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.data.library.placementOf
import com.kennyb1201.kbstream.data.library.traktListShelfId
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.trakt.TraktListEntry
import com.kennyb1201.kbstream.data.trakt.TraktListRef
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
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
    LISTS("PERSONAL LISTS"),

    /**
     * The year's episodes of every tracked show (see [LibraryCalendarPane]).
     *
     * Last, so it sits after PERSONAL LISTS in the strip the way the other
     * panes sit in it, and it is a PANE like LISTS rather than a grid: the
     * screen swaps to it, it owns its own ViewModel, and nothing in this one's
     * saved-title state changes when it is shown.
     */
    CALENDAR("CALENDAR")
}

/**
 * The account-level feeds a WATCHLIST read can fail on.
 *
 * Named apart from [LibrarySource] on purpose: those tag the ROWS a source
 * contributed, while this names a source whose read did not answer at all. The
 * empty state needs the second fact and cannot derive it from the first - a
 * tracker that failed contributes no rows, which is indistinguishable from a
 * watchlist that is genuinely empty (see [libraryWatchlistEmptyText]).
 */
enum class LibraryRemote(val label: String) {
    SIMKL("Simkl"),
    MDBLIST("MDBList"),
    TRAKT("Trakt")
}

/**
 * Which field the Library's sort chips order by. "Added" is the store's own
 * order (newest first); the others sort by the field each row carries.
 *
 * The enum says WHICH key only - the direction is a separate piece of state
 * ([LibraryUiState.sortAscending]), because the same chip sorts either way and
 * which way is the viewer's, not the field's (see [LibraryViewModel.setSort]).
 */
enum class LibrarySort(val label: String) {
    ADDED("ADDED"),
    TITLE("TITLE"),
    RELEASE_DATE("DATE"),
    RATING("RATING"),

    /**
     * Liked first, everything un-signalled in the middle, disliked last - the
     * viewer's own signals as a group sort.
     *
     * THE DIRECTION FLAG IS IGNORED FOR THIS CHIP, deliberately. Inverting the
     * groups would put the titles the viewer disliked FIRST, which is a footgun
     * with no reader who wants it (DATE and RATING are the chips whose two
     * directions both mean something). The chip still draws the shared
     * direction arrow, but tapping it twice yields the identical list - see
     * [sortLibraryItems]. It is listed LAST so the existing chip order (ADDED /
     * TITLE / DATE / RATING) is undisturbed and the new chip appears at the end
     * of the strip on both screens, which draw one chip per enum entry.
     */
    LIKED("LIKED")
}

/**
 * Everything the Library tab renders, grouped by source so the screen can
 * show origin tags while keeping the merged list ordering stable.
 */
data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.ADDED,
    /**
     * Which way [sort] runs. FALSE (descending) is the resting state every
     * Library view opened in and still does: "Added" reads newest-first, DATE
     * newest-year-first, RATING highest-first. The FIRST tap on any sort chip
     * turns it ascending and the next tap on that same chip flips it back (see
     * [LibraryViewModel.setSort]), so the direction is always the viewer's last
     * choice rather than something the chip's label decides for them.
     */
    val sortAscending: Boolean = false,
    val loading: Boolean = true,

    /**
     * True while [LibraryViewModel.refresh]'s REMOTE phase has not published.
     *
     * [loading] describes the local phase alone - it is cleared as soon as My
     * List is on screen, because that half is what the viewer is waiting for -
     * so the WATCHLIST view used to treat a still-in-flight Simkl/MDBList/Trakt
     * fetch as "nothing here" and flash "Watchlist is empty." before the rows
     * landed. The empty copy reads THIS flag (see [libraryWatchlistEmptyText]),
     * which is only cleared once the remote half has an answer.
     */
    val remoteLoading: Boolean = false,

    /**
     * The trackers whose last read FAILED, so the empty state can tell a
     * failure from an empty watchlist instead of saying "empty" about a fetch
     * that never answered.
     */
    val failedRemotes: Set<LibraryRemote> = emptySet(),

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

    // Release year per dedupe key, filled in during enrichment for rows whose
    // source carried none. The pinned "Top Today" catalogs are the case that
    // matters: their previews hold an IMDB id, a name and artwork and nothing
    // else - no `releaseInfo` - so anything saved from those rails used to be
    // the one row in the grid with no year under its poster. Applied by
    // [LibraryViewModel.pushDisplay] before sorting, so the DATE chip orders by
    // the real year too.
    val years: Map<String, Int> = emptyMap(),

    // Watched badges: set of "type::imdbId" keys, same convention the
    // genre/actor screens use.
    val watchedKeys: Set<String> = emptyList<String>().toSet(),

    // Started-but-unfinished badges (the eye), the same "type::imdbId" keys.
    // The checkmark always wins the corner, so a key in both is drawn as
    // watched (see GlobalPosterCard.isPartiallyWatched).
    val partiallyWatchedKeys: Set<String> = emptyList<String>().toSet(),

    val simklConnected: Boolean = false,
    val mdbListConfigured: Boolean = false,
    val traktConnected: Boolean = false
)

/**
 * Library tab: personal lists and watchlists from local storage, Simkl and
 * MDBList in one screen. Every remote fetch fails soft — an unreachable
 * tracker keeps its section empty instead of breaking the whole tab.
 * Remote rows pass through the Kids Mode ceiling filter; ratings, posters and
 * missing release years resolve through the TMDB detail cache so the sort +
 * captions match other screens.
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
    private val traktRepository =
        TraktRepository.getInstance(application)

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
                // Re-read the store, then run the SAME display pipeline every
                // other view uses (pushDisplay: year/poster backfill, sort,
                // UNWATCHED toggle). The old branch set localItems directly
                // from a bare sortItems(), which dropped the backfills (DATE
                // mis-ordered, a poster flashed empty) and ignored the
                // UNWATCHED toggle until the next full refresh.
                canonicalLocal = LocalLibraryStore.myList(getApplication())
                pushDisplay()
            }

            LibraryFilter.WATCHLIST -> refresh()

            LibraryFilter.LISTS -> {
                // Keep cached lists/items; refresh lists in background.
                refresh()
            }

            // The calendar pane reads its own sources (see
            // LibraryCalendarViewModel) and is composed only while it is on
            // screen, so there is nothing here to kick off: a refresh() against
            // the saved-title stores would be work the pane never reads.
            LibraryFilter.CALENDAR -> Unit
        }
    }

    /**
     * A sort chip was tapped: select it, or flip its direction if it already is
     * the selected one.
     *
     * The first tap on a chip sorts ASCENDING and the second DESCENDING - the
     * chip is a two-state control rather than a one-shot selector, which is why
     * tapping the already-selected chip must not be a no-op the way it used to
     * be. Switching to a DIFFERENT chip also starts ascending, so the first tap
     * on any of DATE / TITLE / RATING / ADDED is predictable (A→Z, oldest/newest
     * year, lowest/highest rating, oldest/newest added) and the second reverses
     * it. The screen draws the direction on the selected chip (see the sort
     * strip in LibraryScreen), so the state is never invisible.
     */
    fun setSort(sort: LibrarySort) {
        val state = _uiState.value
        _uiState.value = if (state.sort == sort) {
            state.copy(sortAscending = !state.sortAscending)
        } else {
            state.copy(sort = sort, sortAscending = true)
        }
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
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId
                    )
                    canonicalLocal = LocalLibraryStore.myList(appContext)
                    // The mirror drops the same title from the remote watchlist,
                    // so that canonical must lose it too: recomputeAll rebuilds
                    // from BOTH, and a stale one keeps the removed row on screen
                    // (LS-P2-12).
                    val removedKey = LocalLibraryStore.dedupeKey(item)
                    canonicalWatchlist = canonicalWatchlist.filter {
                        LocalLibraryStore.dedupeKey(it) != removedKey
                    }
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
                        mediaType = item.mediaType,
                        imdbId = item.imdbId,
                        tmdbId = item.tmdbId
                    )
                    val key = LocalLibraryStore.dedupeKey(item)
                    canonicalWatchlist = canonicalWatchlist.filter {
                        LocalLibraryStore.dedupeKey(it) != key
                    }
                    // removeFromLibrary also clears the LOCAL twin, so refresh
                    // that canonical too - otherwise the stale copy keeps the
                    // removed row visible (LS-P2-12).
                    canonicalLocal = LocalLibraryStore.myList(appContext)
                    recomputeAll()
                }

                LibrarySource.MDBLIST_LIST -> {
                    val listId = item.listId ?: return@launch
                    LibraryMirror.removeFromMdbList(
                        context = appContext,
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

                LibrarySource.TRAKT_WATCHLIST -> {
                    runCatchingCancellable {
                        traktRepository.removeFromWatchlist(
                            isMovie = LocalLibraryStore.normalizedType(item.mediaType) == "movie",
                            imdbId = item.imdbId,
                            tmdbId = item.tmdbId
                        )
                    }.onFailure {
                        Log.w(TAG, "Trakt watchlist removal failed: ${it.message}")
                    }
                    val key = LocalLibraryStore.dedupeKey(item)
                    canonicalWatchlist = canonicalWatchlist.filter {
                        LocalLibraryStore.dedupeKey(it) != key
                    }
                    recomputeAll()
                }

                LibrarySource.TRAKT_LIST -> {
                    val listKey = item.listRemoteId ?: return@launch
                    runCatchingCancellable {
                        traktRepository.removeFromList(
                            listId = listKey,
                            isMovie = LocalLibraryStore.normalizedType(item.mediaType) == "movie",
                            imdbId = item.imdbId,
                            tmdbId = item.tmdbId
                        )
                    }.onFailure {
                        Log.w(TAG, "Trakt list removal failed: ${it.message}")
                    }
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
            // The two badge sets are REBUILT by the enrichment pass at the end
            // of this function, one row at a time - each row's own keys are
            // dropped from the sets before this pass's answer is filed, so a
            // key that no longer holds (a title un-watched, or a title the
            // PREVIOUS profile watched) cannot survive. See the publish block
            // in enrich().
            //
            // They used to be additive in both directions and nothing anywhere
            // removed one, so a profile switch (which lands here through
            // observeProfileSwitches) carried profile A's checkmarks and eyes
            // onto profile B's Library, and the UNWATCHED toggle then hid B's
            // rows under A's keys.
            _uiState.value = _uiState.value.copy(
                loading = true,
                remoteLoading = true,
                failedRemotes = emptySet()
            )

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
                .filter { it.source.isLocalList }
                .flatMap { LocalLibraryStore.listItems(appContext, it.id) }
            localListFlatCache = localListItems
            canonicalLocal = localItems
            canonicalAll = mergeAllSources(
                listOf(localItems, canonicalWatchlist, localListItems)
            )
            _uiState.value = _uiState.value.copy(
                loading = false,
                lists = localLists + _uiState.value.lists.filter { !it.source.isLocalList }
            )
            pushDisplay()

            val simklConnected =
                simklRepository.isConfigured() && simklRepository.hasToken()
            val mdbListConfigured = MdbListClient.isConfigured(appContext)
            val traktConnected =
                traktRepository.isConfigured() && traktRepository.hasToken()

            // Which trackers failed to answer at all. A failed fetch is filed
            // as an empty list below (the tab must not break over one dead
            // tracker), and this set is what keeps that from being reported to
            // the viewer as "your watchlist is empty" (see
            // [libraryWatchlistEmptyText]).
            val failedRemotes = mutableSetOf<LibraryRemote>()

            // WATCHLIST merge: Simkl plan-to-watch + MDBList watchlist + Trakt
            // watchlist.
            val simklItems = if (simklConnected) {
                runCatchingCancellable { simklRepository.getWatchlistItems() }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Simkl watchlist fetch failed: ${it.message}"
                        )
                        failedRemotes += LibraryRemote.SIMKL
                    }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val mdbListWatchlist = if (mdbListConfigured) {
                // A null is an INCOMPLETE walk (see
                // MdbListClient.getWatchlist), which this row treats like a
                // failed fetch rather than rendering a truncated list as the
                // whole watchlist - including in the empty state, so the two
                // ways of not getting an answer report the same way.
                val entries = runCatchingCancellable {
                    MdbListClient.getWatchlist(appContext)
                }.onFailure {
                    Log.w(TAG, "MDBList watchlist fetch failed: ${it.message}")
                }.getOrNull()
                if (entries == null) failedRemotes += LibraryRemote.MDBLIST
                entries.orEmpty().map { entry ->
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
            } else {
                emptyList()
            }

            val traktWatchlist = if (traktConnected) {
                runCatchingCancellable { traktRepository.watchlist() }
                    .onFailure {
                        Log.w(TAG, "Trakt watchlist fetch failed: ${it.message}")
                        failedRemotes += LibraryRemote.TRAKT
                    }
                    .getOrDefault(emptyList())
                    .mapNotNull { entry ->
                        traktLibraryItem(entry, LibrarySource.TRAKT_WATCHLIST)
                    }
            } else {
                emptyList()
            }

            val mdbLists = if (mdbListConfigured) {
                runCatchingCancellable {
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

            val traktLists = if (traktConnected) {
                runCatchingCancellable {
                    traktRepository.userLists().mapNotNull { list ->
                        val traktId = list.ids?.trakt ?: 0
                        val remoteId = list.ids?.slug ?: traktId.takeIf { it > 0 }?.toString()
                        val name = list.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (remoteId.isNullOrBlank()) return@mapNotNull null
                        LibraryList(
                            id = traktListShelfId(traktId),
                            name = name,
                            itemCount = list.itemCount ?: 0,
                            source = LibrarySource.TRAKT_LIST,
                            remoteId = remoteId
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "Trakt lists fetch failed: ${it.message}")
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val lists = localLists + mdbLists + traktLists

            if (version != requestVersion) return@launch

            val watchlistMerged = kidsFiltered(
                // What My List was told to forget stays forgotten HERE too.
                //
                // The merge used to take every tracker row at face value, so a
                // title removed on purpose - by the stamped cleanup, by a
                // review confirmation, by the untick screen - came straight back
                // into the Library wearing its Simkl/MDBList/Trakt tag: the
                // local row was gone, the tracker copy was not, and nothing
                // compared the two. The tombstone record is that comparison,
                // and this is the one screen that makes it: the removal is the
                // viewer's decision, and it outranks a remote watchlist entry.
                //
                // Filtered AFTER mergeById so one pass covers all three
                // tracker sources, and scoped to this screen: personal lists,
                // Continue Watching / Up Next / Upcoming cards, Detail's
                // local-list state and every other profile's view are
                // untouched (their own records govern them).
                LibraryTombstones.removedFrom(
                    mergeById(simklItems + mdbListWatchlist + traktWatchlist),
                    LocalLibraryStore.tombstones(appContext)
                )
            )

            // Canonical rows: ALL merges My List + watchlists + every local
            // personal list, local rows winning over remote duplicates. The
            // local rows (and localListFlatCache) were already published
            // above; only the remote halves are new here.
            canonicalWatchlist = watchlistMerged
            // Keep the OPEN personal list's rows: refresh used to blank them
            // here and never reload, so the items pane went empty until the
            // list was reselected (see LS-P2-17). Re-derived on THIS refresh's
            // request version, so it cannot race the list loader.
            canonicalListItems = _uiState.value.selectedList
                ?.let { fetchListItems(it) }
                ?: emptyList()
            canonicalAll = kidsFiltered(
                mergeAllSources(listOf(localItems, watchlistMerged, localListItems))
            )

            _uiState.value = _uiState.value.copy(
                loading = false,
                // The remote half has answered (successfully or not), so the
                // empty state may now speak for it.
                remoteLoading = false,
                failedRemotes = failedRemotes,
                lists = lists,
                simklConnected = simklConnected,
                mdbListConfigured = mdbListConfigured,
                traktConnected = traktConnected
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
                runCatchingCancellable {
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
            val traktLists = if (
                traktRepository.isConfigured() && traktRepository.hasToken()
            ) {
                runCatchingCancellable {
                    traktRepository.userLists().mapNotNull { list ->
                        val traktId = list.ids?.trakt ?: 0
                        val remoteId = list.ids?.slug ?: traktId.takeIf { it > 0 }?.toString()
                        val name = list.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (remoteId.isNullOrBlank()) return@mapNotNull null
                        LibraryList(
                            id = traktListShelfId(traktId),
                            name = name,
                            itemCount = list.itemCount ?: 0,
                            source = LibrarySource.TRAKT_LIST,
                            remoteId = remoteId
                        )
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _uiState.value = _uiState.value.copy(lists = localLists + mdbLists + traktLists)
        }
    }

    private fun loadListItems(list: LibraryList) {
        // Increment, do not just read: switching lists A -> B quickly left both
        // loads on ONE version, so if A's slower fetch landed last it overwrote
        // B's pane (see LS-P2-1). refresh() bumps the version the same way.
        val version = ++requestVersion
        viewModelScope.launch {
            val items = fetchListItems(list)
            if (version != requestVersion) return@launch
            canonicalListItems = items
            pushDisplay()
            enrich(items, version)
        }
    }

    /**
     * The rows of [list]: local lists from disk, a personal MDBList list from
     * the tracker. Shared so a refresh can re-derive the open list on its own
     * request version instead of blanking it.
     */
    private suspend fun fetchListItems(list: LibraryList): List<LibraryItem> {
        val appContext = getApplication<Application>()
        if (list.source.isLocalList) return LocalLibraryStore.listItems(appContext, list.id)

        if (list.source == LibrarySource.TRAKT_LIST) {
            val remoteId = list.remoteId ?: return emptyList()
            val entries = runCatchingCancellable {
                traktRepository.listItems(TraktListRef(user = "me", listId = remoteId))
            }.onFailure {
                Log.w(TAG, "Trakt list items fetch failed: ${it.message}")
            }.getOrDefault(emptyList())
            return entries.mapNotNull { entry ->
                traktLibraryItem(
                    entry = entry,
                    source = LibrarySource.TRAKT_LIST,
                    listId = list.id,
                    listName = list.name,
                    listRemoteId = remoteId
                )
            }
        }

        val entries = runCatchingCancellable {
            MdbListClient.getListItems(appContext, list.id)
        }.onFailure {
            Log.w(TAG, "MDBList list items fetch failed: ${it.message}")
        }.getOrNull().orEmpty()
        return entries.map { entry ->
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

    /**
     * One Trakt read row as a Library row.
     *
     * Both Trakt sources come through here so the watchlist and a personal
     * list cannot drift: same title fallback, same artwork pick (`images` is
     * only present because the read asks for `extended=full,images`), and the
     * list context is stamped on when the row came from a list.
     *
     * A row with no title or no id Trakt can address is dropped: it could not
     * be opened, badged, or removed, so drawing it would only look broken.
     */
    private fun traktLibraryItem(
        entry: TraktListEntry,
        source: LibrarySource,
        listId: Int? = null,
        listName: String? = null,
        listRemoteId: String? = null
    ): LibraryItem? {
        val media = entry.media
        val title = media.title?.takeIf { it.isNotBlank() } ?: return null
        val ids = media.ids
        val imdbId = ids?.imdb?.takeIf { it.isNotBlank() }
        val tmdbId = ids?.tmdb?.takeIf { it > 0 }
        if (imdbId == null && tmdbId == null) return null
        return LibraryItem(
            source = source,
            mediaType = if (entry.isMovie) "movie" else "series",
            title = title,
            year = media.year?.takeIf { it > 0 },
            posterUrl = media.images?.poster
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() },
            imdbId = imdbId,
            tmdbId = tmdbId,
            listId = listId,
            listName = listName,
            listRemoteId = listRemoteId
        )
    }

    /**
     * Background enrichment for a batch of rows: resolves ratings through
     * the TMDB detail cache, backfills a missing poster and release year
     * from the same cached record, applies the Kids Mode ceiling to remote
     * rows, and collects watched badges.
     */
    private fun enrich(items: List<LibraryItem>, version: Int) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val appContext = getApplication<Application>()

            // Warm the watched cache for THIS batch before the per-row lookup:
            // isWatchedCached reads memory only, so a first Library open - before
            // Home or a detail screen had warmed it - reported every row as
            // unwatched and drew no badges (LS-P2-18).
            val watchedPreload = items
                .mapNotNull { item ->
                    val type = when (item.mediaType.lowercase()) {
                        "tv", "series" -> "series"
                        else -> "movie"
                    }
                    val id = item.imdbId ?: item.tmdbId?.let { "tmdb:$it" }
                    id?.let { it to type }
                }
                .distinct()
            if (watchedPreload.isNotEmpty()) {
                runCatchingCancellable {
                    watchedRepository.preloadAndGetWatchedKeys(watchedPreload)
                }
                runCatchingCancellable {
                    watchedRepository.preloadAndGetPartiallyWatchedKeys(watchedPreload)
                }
            }

            data class Resolved(
                val key: String,
                val rating: Double?,
                val watched: String?,
                val partiallyWatched: String?,
                val poster: String?,
                val year: Int?,
                /**
                 * Every badge key this row could carry, so publishing can drop
                 * the ones this pass did not confirm before it files the ones
                 * it did (see the badge publish below).
                 */
                val badgeKeys: List<String>
            )

            // The eye is a claim about THIS profile, so a title another profile
            // positively owns does not get one - the same rule the Home rails
            // apply to their own markers (see HomeViewModel.siblingOwnedRailItemIds)
            // and the tracker cards have always applied. The resolver behind the
            // badge is not per profile all the way down: the tracker feeds hold
            // one library per ACCOUNT, and the first profile adopts the pre-profile
            // history store. A kids title the kids profile started was therefore
            // drawing the eye on profile 1, which had never watched it.
            val badgeActiveProfileId =
                com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
                    ?.takeIf { it.isNotBlank() }
            val badgeTitleOwners =
                com.kennyb1201.kbstream.data.history.TitleProfileOwnership
                    .snapshot(appContext)

            suspend fun resolve(item: LibraryItem): Resolved {
                val normalizedType = when (item.mediaType.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }
                var imdbId = item.imdbId
                if (imdbId == null && item.tmdbId != null) {
                    imdbId = runCatchingCancellable {
                        tmdbRepository.resolveImdbId(item.tmdbId, normalizedType)
                    }.getOrNull()
                }

                var watched: String? = null

                if (imdbId != null) {
                    if (watchedRepository.isWatchedCached(imdbId, normalizedType)) {
                        watched = "$normalizedType::$imdbId"
                    }
                }

                // The second flavor this row can be watched under: its own
                // raw `tmdb:<n>` id, which is what the batch preload above
                // already warmed and what a row with no IMDB id (or one whose
                // resolution came back null) is keyed under. Strictly
                // additive - a row the IMDB check already matched keeps that
                // key (see [WatchedStatusKeys]).
                val tmdbWatchedId = item.tmdbId
                    ?.takeIf { it > 0 }
                    ?.let { WatchedStatusKeys.tmdbKeyId(it) }

                if (watched == null && tmdbWatchedId != null &&
                    watchedRepository.isWatchedCached(tmdbWatchedId, normalizedType)
                ) {
                    watched = "$normalizedType::$tmdbWatchedId"
                }

                // The eye: started but not finished. Skipped when the title is
                // already fully watched, because the checkmark owns the
                // corner and a row must never draw both.
                var partiallyWatched: String? = null

                if (imdbId != null && watched == null &&
                    watchedRepository.isPartiallyWatchedCached(imdbId, normalizedType)
                ) {
                    partiallyWatched = "$normalizedType::$imdbId"
                }

                if (partiallyWatched == null && watched == null &&
                    tmdbWatchedId != null &&
                    watchedRepository.isPartiallyWatchedCached(tmdbWatchedId, normalizedType)
                ) {
                    partiallyWatched = "$normalizedType::$tmdbWatchedId"
                }

                // BOTH badges are claims about THIS profile, so both take the
                // same ownership rule - dropped only on POSITIVE ownership: an
                // unattributed title, a device with no profiles or a title with
                // no name all keep their badge, so the cross-device marker a
                // profile earned on another device survives (see
                // [titleOwnedByAnotherProfile]).
                //
                // The checkmark used to have no filter at all, and its cache
                // cannot supply one: WatchedStatusRepository keys watched state
                // by type and id with no profile component, so the account-wide
                // tracker answer painted profile 1's Library with a title the
                // kids profile had finished - and the UNWATCHED toggle then hid
                // that row on profile 1. The eye was filtered here and the
                // completed twin was not, which is the same half-fix in the one
                // direction that matters most: a CHECKMARK is the stronger
                // claim. One rule for the pair, so they cannot drift again.
                if ((watched != null || partiallyWatched != null) &&
                    titleOwnedByAnotherProfile(
                        ownerByTitleKey = badgeTitleOwners,
                        type = normalizedType,
                        title = item.title,
                        activeProfileId = badgeActiveProfileId
                    )
                ) {
                    watched = null
                    partiallyWatched = null
                }

                // Ratings come from the shared TMDB detail cache
                // (disk + memory), so a title already enriched on
                // any screen costs nothing here.
                val detail = runCatchingCancellable {
                    tmdbRepository.fetchEnrichedMetaCached(
                        imdbId = imdbId ?: "tmdb:${item.tmdbId}",
                        type = normalizedType
                    )
                }.getOrNull()
                val rating = detail?.voteAverage?.takeIf { it > 0.0 }

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

                // Release-year backfill, off the same record: an
                // add-on catalog is not obliged to send one, and
                // the pinned "Top Today" rails send none at all, so
                // a title saved from them had no year under its
                // poster while every other row had one. A year the
                // row already carries always wins.
                val year = if (item.year == null) {
                    detail?.releaseYear()?.toIntOrNull()
                } else {
                    null
                }

                return Resolved(
                    key = LocalLibraryStore.dedupeKey(item),
                    rating = rating,
                    watched = watched,
                    partiallyWatched = partiallyWatched,
                    poster = poster,
                    year = year,
                    // The row's own candidate spellings, PLUS the IMDb id this
                    // pass resolved from its TMDB one: a badge can be filed
                    // under a key the row did not carry into the pass, and a
                    // key that is never named here is a key that can never be
                    // taken back off.
                    badgeKeys = item.watchedKeyCandidates() +
                        listOfNotNull(
                            imdbId?.takeIf { it.isNotBlank() }?.let { "$normalizedType::$it" }
                        )
                )
            }

            // Every row is enriched, in bounded-concurrency windows. The old
            // `.take(250)` left every title past the first 250 without a
            // rating/poster/year, and the unbounded `.map { async }` launched
            // one request per row at once - a thundering herd of TMDB calls on
            // a box this small the moment a large library was opened.
            val resolved = coroutineScope {
                val resolvedRows = mutableListOf<Resolved>()
                items.distinctBy { LocalLibraryStore.dedupeKey(it) }
                    .chunked(ENRICH_CONCURRENCY)
                    .forEach { chunk ->
                        // One window at a time. `.map { async }` started every
                        // chunk coroutine immediately, so only the INSIDE of a
                        // window was serial - the effective concurrency was the
                        // whole library again, which is the thundering herd the
                        // kdoc above says this replaced. Awaiting each window
                        // before opening the next is what actually bounds it.
                        resolvedRows += async { chunk.map { resolve(it) } }.await()
                    }
                resolvedRows
            }

            if (version != requestVersion) return@launch

            val ratings = _uiState.value.ratings.toMutableMap()
            val posters = _uiState.value.posters.toMutableMap()
            val years = _uiState.value.years.toMutableMap()
            resolved.forEach { r ->
                r.rating?.let { ratings[r.key] = it }
                r.poster?.let { posters[r.key] = it }
                r.year?.let { years[r.key] = it }
            }

            // Badges are REPLACED per row, never accumulated.
            //
            // Both sets were pure unions, so nothing ever removed a key: a
            // profile switch carried the outgoing profile's checkmarks and eyes
            // onto the incoming one (the state behind them is not per profile -
            // WatchedStatusRepository keys watched state by type and id), and
            // the UNWATCHED toggle then hid the incoming profile's rows under
            // the outgoing profile's keys. Un-watching a title had the same
            // shape: the key stayed, because no read ever took one away.
            //
            // So each row in this batch drops the keys it could carry and then
            // files the ones this pass confirmed. A row outside the batch keeps
            // its badge - a personal list being loaded on its own, say - which
            // is why this is done per row rather than by clearing the sets for
            // the pass: clearing would blank every checkmark on screen until the
            // last row resolved.
            val watchedKeys = _uiState.value.watchedKeys.toMutableSet()
            val partiallyWatchedKeys = _uiState.value.partiallyWatchedKeys.toMutableSet()
            resolved.forEach { row ->
                watchedKeys -= row.badgeKeys
                partiallyWatchedKeys -= row.badgeKeys
                row.watched?.let { watchedKeys += it }
                row.partiallyWatched?.let { partiallyWatchedKeys += it }
            }

            _uiState.value = _uiState.value.copy(
                ratings = ratings,
                posters = posters,
                years = years,
                watchedKeys = watchedKeys,
                partiallyWatchedKeys = partiallyWatchedKeys
            )
            pushDisplay()
        }
    }

    /**
     * Drops rows above the active kids profile's ceiling.
     *
     * EVERY source, local included (audit M8). Local rows used to be exempt on
     * the argument that the user (or parent) had saved them deliberately - but
     * a title saved while the ceiling was off, or before the profile became
     * kids-restricted, then rendered on the kids profile with its poster, title
     * and year. That is the one thing the ceiling exists to stop. It was also
     * internally inconsistent: personal LOCAL_LIST rows were filtered while
     * plain LOCAL rows were not. A local row whose TMDB id never resolved is
     * treated like any other unrated title, by [KidsMode]'s known-unknown rule
     * (kept on PG-13, dropped on the strict ceilings).
     */
    private suspend fun kidsFiltered(items: List<LibraryItem>): List<LibraryItem> {
        if (tmdbRepository.kidsMaxAge() == null || items.isEmpty()) return items
        return tmdbRepository.kidsFilter(items) { row ->
            val type = when (row.mediaType.lowercase()) {
                "tv", "series" -> "series"
                else -> "movie"
            }
            (row.tmdbId ?: -1) to type
        }
    }

    /**
     * Rebuilds every display list from the canonical rows: sort + kids
     * filter + UNWATCHED toggle in one place, so toggling filters never
     * loses rows and enrichment updates stay consistent everywhere.
     */
    private fun pushDisplay() {
        val state = _uiState.value
        val sort = state.sort
        val ascending = state.sortAscending
        val ratings = state.ratings
        val watched = state.watchedKeys
        val hide = state.hideWatched
        val posters = state.posters
        val years = state.years
        // The viewer's own signals, read once per rebuild and in memory from
        // there on: the LIKED chip's three groups, the same snapshot for all
        // four views, and an empty one leaves every sort exactly as it was.
        val taste = TasteSignals.snapshot(getApplication())
        // Years are applied BEFORE the sort: the DATE chip orders on the row's
        // own year, so a backfilled one has to be on the row the sort reads.
        _uiState.value = state.copy(
            allItems = applyUnwatched(
                applyPosters(
                    sortItems(applyYears(canonicalAll, years), sort, ratings, ascending, taste),
                    posters
                ),
                watched,
                hide
            ),
            localItems = applyUnwatched(
                applyPosters(
                    sortItems(applyYears(canonicalLocal, years), sort, ratings, ascending, taste),
                    posters
                ),
                watched,
                hide
            ),
            watchlistItems = applyUnwatched(
                applyPosters(
                    sortItems(applyYears(canonicalWatchlist, years), sort, ratings, ascending, taste),
                    posters
                ),
                watched,
                hide
            ),
            selectedListItems = applyUnwatched(
                applyPosters(
                    sortItems(applyYears(canonicalListItems, years), sort, ratings, ascending, taste),
                    posters
                ),
                watched,
                hide
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
            .filter { it.source.isLocalList }
            .flatMap { LocalLibraryStore.listItems(appContext, it.id) }
        canonicalAll = mergeAllSources(
            listOf(canonicalLocal, canonicalWatchlist, localListFlatCache)
        )
        pushDisplay()
    }

    /** ALL-view merge: de-duped across every source, input order kept. */
    private fun mergeAllSources(sources: List<List<LibraryItem>>): List<LibraryItem> =
        mergeById(sources.flatten())

    /**
     * Drops duplicate titles across trackers, Simkl winning over MDBList.
     *
     * Delegates to [mergeLibraryItemsById], which matches on title IDENTITY
     * rather than the exact id triple: two trackers can name the same title
     * with different id flavors (an IMDB id from Simkl, a TMDB id from
     * MDBList), which the exact triple would draw as two rows.
     */
    private fun mergeById(items: List<LibraryItem>): List<LibraryItem> =
        mergeLibraryItemsById(items)

    private fun sortItems(
        items: List<LibraryItem>,
        sort: LibrarySort,
        ratings: Map<String, Double>,
        ascending: Boolean,
        taste: TasteSignals.Snapshot
    ): List<LibraryItem> = sortLibraryItems(items, sort, ratings, ascending, taste)

    companion object {
        private const val TAG = "LIBRARY"

        /**
         * How many rows the enrichment resolves at once. Bounded so opening a
         * large library does not fire one TMDB request per title at the same
         * moment; the rest are processed in the next window.
         */
        private const val ENRICH_CONCURRENCY = 8
    }
}

/**
 * Pure ordering for the Library tab's sort chips — extracted from the
 * ViewModel so the JVM test suite can pin the contracts (stable ADDED
 * order, case-insensitive titles, missing years/ratings sinking to the
 * bottom) without an Android dependency.
 *
 * [ascending] is the chip's current direction. It DEFAULTS TO DESCENDING,
 * which is the resting order every Library view opened in and every caller
 * before the direction existed used verbatim - so a caller that does not care
 * about direction keeps the old reading (added newest-first, title Z-first,
 * year newest-first, rating highest-first), and the first tap on a chip is
 * what turns it round (see [LibraryViewModel.setSort]).
 *
 * A row whose sort key is UNKNOWN (no year, no resolved rating) sinks to the
 * bottom EITHER WAY. Unknown is not a small number: flipping a chip must
 * reorder the titles that have the field, not float the blanks to the top, and
 * that is what a plain `year ?: 0` did in ascending order.
 */
internal fun sortLibraryItems(
    items: List<LibraryItem>,
    sort: LibrarySort,
    ratings: Map<String, Double>,
    ascending: Boolean = false,
    taste: TasteSignals.Snapshot = TasteSignals.Snapshot()
): List<LibraryItem> = when (sort) {
    // The store's own order is newest-first (inserts go to index 0), so
    // ascending is the reverse of it.
    LibrarySort.ADDED -> if (ascending) items.reversed() else items
    LibrarySort.TITLE -> if (ascending) {
        items.sortedBy { it.title.lowercase() }
    } else {
        items.sortedByDescending { it.title.lowercase() }
    }
    LibrarySort.RELEASE_DATE -> {
        val (dated, undated) = items.partition { it.year != null }
        val ordered = if (ascending) {
            dated.sortedBy { it.year }
        } else {
            dated.sortedByDescending { it.year }
        }
        ordered + undated
    }
    LibrarySort.RATING -> {
        val keyOf = { item: LibraryItem -> ratings[LocalLibraryStore.dedupeKey(item)] }
        val (rated, unrated) = items.partition { keyOf(it) != null }
        val ordered = if (ascending) {
            rated.sortedBy { keyOf(it) }
        } else {
            rated.sortedByDescending { keyOf(it) }
        }
        ordered + unrated
    }
    // A GROUP sort, not a score: liked rows first, everything un-signalled in
    // the middle, disliked rows last. Each group keeps its input order - a
    // partition is stable - so the caller's own ordering (the store's
    // newest-first, or the source priority the merge set) survives inside a
    // group and there is no second key to argue about. `ascending` is
    // deliberately unread here; see [LibrarySort.LIKED].
    LibrarySort.LIKED -> {
        val (liked, others) = items.partition {
            libraryTastePlacement(it, taste) == TastePlacement.LIKED
        }
        val (disliked, unSignalled) = others.partition {
            libraryTastePlacement(it, taste) == TastePlacement.DISLIKED
        }
        liked + unSignalled + disliked
    }
}

/**
 * Where the viewer's taste puts one library row: liked first, un-signalled in
 * the middle, disliked last (the LIKED sort's three groups).
 *
 * The row's own id flavors are handed over, so a title signalled from a TMDB
 * rail and the same title signalled from an add-on rail answer the same way
 * wherever the row came from. A row with NEITHER id reads as un-signalled and
 * sinks into the middle group - it is never dropped and never floated, which is
 * the unknown-key rule the DATE and RATING chips already follow.
 */
internal fun libraryTastePlacement(
    item: LibraryItem,
    taste: TasteSignals.Snapshot
): TastePlacement = taste.placementOf(
    mediaType = item.mediaType,
    ids = listOf(item.imdbId, item.tmdbId?.let { WatchedStatusKeys.tmdbKeyId(it) })
)

/**
 * Cross-source merge for the ALL view: the first occurrence of a title wins
 * (input order is the source priority — local rows before remote ones, Simkl
 * before MDBList), de-duped by title IDENTITY rather than by the exact id
 * triple [LocalLibraryStore.dedupeKey] builds.
 *
 * The exact triple was the bug: two trackers can hold the SAME title with
 * different id flavors — Simkl resolves an IMDB id, MDBList a TMDB one — so
 * their keys differ and the title drew twice. Matching on EITHER shared id is
 * the rule the long-press menu already uses (see [LocalLibraryStore.matches]).
 * A twin's extra id is folded onto the kept row, so a third flavor still
 * resolves to it. Extracted alongside [sortLibraryItems] for testability.
 */
internal fun mergeLibraryItemsById(items: List<LibraryItem>): List<LibraryItem> {
    // The kept row by its own dedupe key, plus the two id indexes that point
    // back at it ("type:imdb" / "type:tmdb").
    val kept = LinkedHashMap<String, LibraryItem>()
    val byImdb = HashMap<String, String>()
    val byTmdb = HashMap<String, String>()
    items.forEach { item ->
        val type = LocalLibraryStore.normalizedType(item.mediaType)
        val imdb = item.imdbId
            ?.trim()
            ?.removePrefix("tmdb:")
            ?.takeIf { it.isNotBlank() }
        val tmdb = item.tmdbId?.takeIf { it > 0 }
        val imdbKey = imdb?.let { "$type:${it.lowercase()}" }
        val tmdbKey = tmdb?.let { "$type:$it" }
        val existingKey = imdbKey?.let { byImdb[it] } ?: tmdbKey?.let { byTmdb[it] }
        if (existingKey != null) {
            // Fold the ids this twin adds onto the kept row's indexes, so a
            // later single-flavor copy still resolves to it.
            imdbKey?.let { byImdb[it] = existingKey }
            tmdbKey?.let { byTmdb[it] = existingKey }
            return@forEach
        }
        val key = LocalLibraryStore.dedupeKey(item)
        if (kept.containsKey(key)) return@forEach
        kept[key] = item
        imdbKey?.let { byImdb[it] = key }
        tmdbKey?.let { byTmdb[it] = key }
    }
    return kept.values.toList()
}

/**
 * Fills in a missing release year from the resolved-year map (keyed by the
 * same dedupe rule the merge pipeline uses), leaving a row that already
 * carries one untouched — a tracker that sent a year knows better than a
 * backfill. Extracted alongside [sortLibraryItems] for the same testability
 * reasons, and applied before the sort so the DATE chip sees the real year.
 */
internal fun applyYears(
    items: List<LibraryItem>,
    years: Map<String, Int>
): List<LibraryItem> {
    if (years.isEmpty()) return items
    return items.map { item ->
        if (item.year != null) {
            item
        } else {
            years[LocalLibraryStore.dedupeKey(item)]
                ?.let { item.copy(year = it) }
                ?: item
        }
    }
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
        // A row with no key at all can never be hidden; one that matches
        // under EITHER flavor is watched and drops.
        item.watchedKeyCandidates().none { it in watchedKeys }
    }
}

/**
 * The WATCHLIST view's empty line, for both screens.
 *
 * Three things used to be reported as one. A still-arriving remote fetch drew
 * "Watchlist is empty." (the local half clears `loading` first, so the sentence
 * spoke for a half that had not answered); a tracker that FAILED drew the same
 * sentence as a tracker that was never connected, and - when all three were
 * connected and all three failed - as a watchlist that genuinely holds nothing.
 * Neither is what happened, and neither offers the viewer a reason to try
 * again.
 *
 * So: the waiting state names the wait ([remoteLoading]), a failure says the
 * read failed and which service could not be reached, and "empty" is reserved
 * for the case where every configured source answered. A service that was
 * never connected is still listed, because "empty — Trakt not connected" is
 * useful information about a watchlist the viewer may have expected to be
 * longer.
 *
 * One function rather than two strings: this copy lives on the TV pane and the
 * phone pane, and the two drifting apart is how the phone ended up without the
 * em-dash sentence the TV had.
 */
internal fun libraryWatchlistEmptyText(
    loading: Boolean,
    remoteLoading: Boolean,
    simklConnected: Boolean,
    mdbListConfigured: Boolean,
    traktConnected: Boolean,
    failed: Set<LibraryRemote>
): String {
    if (loading || remoteLoading) return "Loading watchlists…"

    val reasons = buildList {
        // Unreachable first: it is the one reason the viewer can act on by
        // retrying, and the one that used to be reported as "empty".
        LibraryRemote.entries.filter { it in failed }.forEach {
            add("${it.label} unreachable")
        }
        if (!simklConnected) add("Simkl not connected")
        if (!mdbListConfigured) add("MDBList key not set")
        if (!traktConnected) add("Trakt not connected")
    }

    val headline = if (failed.isEmpty()) {
        "Watchlist is empty"
    } else {
        "Watchlist couldn't be loaded"
    }
    return if (reasons.isEmpty()) {
        "$headline."
    } else {
        // An em dash, not the full stop this once used: the reasons belong to
        // the sentence rather than being stranded after it.
        "$headline — ${reasons.joinToString(" · ")}."
    }
}
