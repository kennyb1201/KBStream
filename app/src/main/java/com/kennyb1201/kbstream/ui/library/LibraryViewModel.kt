package com.kennyb1201.kbstream.ui.library

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which flat view of the Library tab is showing.
 */
enum class LibraryFilter(val label: String) {
    MY_LIST("MY LIST"),
    WATCHLIST("WATCHLIST"),
    LISTS("PERSONAL LISTS")
}

/**
 * Everything the Library tab renders, grouped by source so the screen can
 * show origin tags while keeping the merged list ordering stable.
 */
data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.MY_LIST,
    val loading: Boolean = true,

    // MY LIST: local, profile-scoped, works with no account connected.
    val localItems: List<LibraryItem> = emptyList(),

    // WATCHLIST: Simkl plan-to-watch + MDBList watchlist merged.
    val watchlistItems: List<LibraryItem> = emptyList(),

    // PERSONAL LISTS: MDBList user lists (list picker) and their items.
    val lists: List<LibraryList> = emptyList(),
    val selectedList: LibraryList? = null,
    val selectedListItems: List<LibraryItem> = emptyList(),

    val simklConnected: Boolean = false,
    val mdbListConfigured: Boolean = false
)

/**
 * Library tab: personal lists and watchlists from local storage, Simkl and
 * MDBList in one screen. Every remote fetch fails soft — an unreachable
 * tracker keeps its section empty instead of breaking the whole tab.
 */
class LibraryViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val simklRepository =
        SimklRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** Bump to force a remote re-fetch (filter changes, pull-to-refresh). */
    private var requestVersion = 0

    init {
        refresh()
    }

    fun setFilter(filter: LibraryFilter) {
        val current = _uiState.value
        if (current.filter == filter) return
        _uiState.value = current.copy(filter = filter)
        when (filter) {
            LibraryFilter.MY_LIST -> {
                _uiState.value = _uiState.value.copy(
                    localItems = LocalLibraryStore.myList(getApplication())
                )
            }

            LibraryFilter.WATCHLIST -> refresh()

            LibraryFilter.LISTS -> {
                // Keep cached lists/items; refresh lists in background.
                refresh()
            }
        }
    }

    fun selectList(list: LibraryList?) {
        _uiState.value = _uiState.value.copy(selectedList = list)
        if (list != null) {
            loadListItems(list)
        }
    }

    fun refresh() {
        val version = ++requestVersion
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)

            val appContext = getApplication<Application>()

            // Local first: instant, always present.
            val localItems = LocalLibraryStore.myList(appContext)

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

            val watchlistMerged = mergeById(
                simklItems + mdbListWatchlist
            )

            val lists = if (mdbListConfigured) {
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

            _uiState.value = _uiState.value.copy(
                loading = false,
                localItems = localItems,
                watchlistItems = watchlistMerged,
                lists = lists,
                simklConnected = simklConnected,
                mdbListConfigured = mdbListConfigured
            )
        }
    }

    private fun loadListItems(list: LibraryList) {
        val version = requestVersion
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val entries = runCatching {
                MdbListClient.getListItems(appContext, list.id)
            }.onFailure {
                Log.w(TAG, "MDBList list items fetch failed: ${it.message}")
            }.getOrDefault(emptyList())

            val items = entries.map { entry ->
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

            if (version != requestVersion) return@launch
            _uiState.value = _uiState.value.copy(selectedListItems = items)
        }
    }

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

    companion object {
        private const val TAG = "LIBRARY"
    }
}
