package com.kennyb1201.kbstream.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Library tab: the user's personal lists and watchlists in one screen —
 * the local profile "My List", the Simkl Plan-to-Watch list, the MDBList
 * watchlist, and MDBList personal lists. Rows carry a source tag so it's
 * always clear which tracker an entry lives on.
 */
@Composable
fun LibraryScreen(
    onItemClick: (mediaType: String, id: String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: LibraryViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val topFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        topFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        // Header + filter chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "LIBRARY",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = KBAccent
            )

            Spacer(modifier = Modifier.width(18.dp))

            LibraryFilter.entries.forEach { filter ->
                LibraryFilterChip(
                    label = filter.label,
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    modifier = if (
                        filter == LibraryFilter.entries.first()
                    ) {
                        Modifier
                            .focusRequester(topFocusRequester)
                            .padding(end = 8.dp)
                    } else {
                        Modifier.padding(end = 8.dp)
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // UNWATCHED toggle: hides rows already marked watched.
            LibraryFilterChip(
                label = if (state.hideWatched) "WATCHED HIDDEN" else "UNWATCHED",
                selected = state.hideWatched,
                onClick = { viewModel.toggleHideWatched() },
                modifier = Modifier.padding(end = 8.dp)
            )

            // Sort chips: Added / Title / Date / Rating.
            LibrarySort.entries.forEach { sort ->
                LibraryFilterChip(
                    label = sort.label,
                    selected = state.sort == sort,
                    onClick = { viewModel.setSort(sort) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Connection status line.
            val connections = buildList {
                if (state.simklConnected) add("SIMKL")
                if (state.mdbListConfigured) add("MDBLIST")
                add("LOCAL")
            }
            Text(
                text = connections.joinToString(" + "),
                style = MaterialTheme.typography.labelMedium,
                color = KBTextLo
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (state.filter) {
            LibraryFilter.ALL -> {
                ItemGrid(
                    items = state.allItems,
                    emptyText = if (state.loading) {
                        "Loading…"
                    } else {
                        "Nothing here yet. Long-press any poster and choose " +
                            "\"Add to list…\" — My List, watchlists and personal " +
                            "lists all land in this merged view."
                    },
                    sourceLabel = { it.source.label },
                    onItemClick = onItemClick,
                    onItemLongClick = { item ->
                        // Simkl rows are add-only (no API to remove).
                        if (item.source != LibrarySource.SIMKL_WATCHLIST) {
                            viewModel.removeItem(item)
                        }
                    },
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys
                )
            }

            LibraryFilter.MY_LIST -> {
                ItemGrid(
                    items = state.localItems,
                    emptyText = if (state.loading) {
                        "Loading…"
                    } else {
                        "Your list is empty. Long-press any poster on a " +
                            "detail page and choose \"Add to Library\"."
                    },
                    sourceLabel = { it.source.label },
                    onItemClick = onItemClick,
                    onItemLongClick = { item ->
                        viewModel.removeItem(item)
                    },
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys
                )
            }

            LibraryFilter.WATCHLIST -> {
                ItemGrid(
                    items = state.watchlistItems,
                    emptyText = if (state.loading) {
                        "Loading watchlists…"
                    } else {
                        val sources = mutableListOf<String>()
                        if (!state.simklConnected) sources.add("Simkl not connected")
                        if (!state.mdbListConfigured) sources.add("MDBList key not set")
                        if (sources.isEmpty()) {
                            "Watchlist is empty."
                        } else {
                            "Watchlist is empty. " + sources.joinToString(" · ")
                        }
                    },
                    sourceLabel = { it.source.label },
                    onItemClick = onItemClick,
                    onItemLongClick = { item ->
                        // Simkl rows are add-only (no API to remove).
                        if (item.source != LibrarySource.SIMKL_WATCHLIST) {
                            viewModel.removeItem(item)
                        }
                    },
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys
                )
            }

            LibraryFilter.LISTS -> {
                ListsPane(
                    lists = state.lists,
                    selectedList = state.selectedList,
                    listItems = state.selectedListItems,
                    loading = state.loading,
                    onListSelect = { viewModel.selectList(it) },
                    onListLongPress = { list ->
                        // Local lists can be deleted; MDBList lists are
                        // managed on MDBList.
                        if (list.id < 0) {
                            viewModel.deleteLocalList(list.id)
                        }
                    },
                    onCreateList = { viewModel.createLocalList(it) },
                    onItemClick = onItemClick,
                    onItemLongClick = { item -> viewModel.removeItem(item) },
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> KBAccent.copy(alpha = 0.28f)
                    focused -> KBSurfaceRaised
                    else -> KBSurface
                }
            )
            .border(
                1.dp,
                when {
                    selected -> KBAccent
                    focused -> KBTextHi
                    else -> KBTextLo.copy(alpha = 0.35f)
                },
                RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) KBAccent else KBTextLo
        )
    }
}

/**
 * Poster grid shared by MY LIST and WATCHLIST. Long-press removes a local
 * row when [onItemLongClick] is provided (the KBCard long-press fires on
 * the same menu-button/D-pad-hold gesture the rest of the app uses).
 */
@Composable
private fun ItemGrid(
    items: List<LibraryItem>,
    emptyText: String,
    sourceLabel: (LibraryItem) -> String,
    onItemClick: (String, String) -> Unit,
    onItemLongClick: ((LibraryItem) -> Unit)?,
    ratings: Map<String, Double> = emptyMap(),
    watchedKeys: Set<String> = emptySet()
) {
    if (items.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = KBTextLo
            )
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items.chunked(6)) { rowItems ->
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rowItems) { item ->
                    LibraryPosterCard(
                        item = item,
                        sourceLabel = sourceLabel(item),
                        rating = ratings[LocalLibraryStore.dedupeKey(item)],
                        isWatched = item.watchedKey() in watchedKeys,
                        onClick = {
                            item.navigationId?.let { id ->
                                onItemClick(item.mediaType, id)
                            }
                        },
                        onLongClick = onItemLongClick?.let { handler ->
                            { handler(item) }
                        }
                    )
                }
            }
        }
    }
}

/**
 * PERSONAL LISTS: left rail of local + MDBList lists (plus a create-list
 * row), right pane of the selected list's items.
 */
@Composable
private fun ListsPane(
    lists: List<LibraryList>,
    selectedList: LibraryList?,
    listItems: List<LibraryItem>,
    loading: Boolean,
    onListSelect: (LibraryList) -> Unit,
    onListLongPress: (LibraryList) -> Unit,
    onCreateList: (String) -> Unit,
    onItemClick: (String, String) -> Unit,
    onItemLongClick: (LibraryItem) -> Unit,
    ratings: Map<String, Double>,
    watchedKeys: Set<String>
) {
    var showCreateField by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .width(280.dp)
                .fillMaxSize()
        ) {
            items(lists, key = { list -> list.id }) { list ->
                var focused by remember { mutableStateOf(false) }
                val selected = selectedList?.id == list.id
                val sourceLabel = if (list.id < 0) "This device" else "MDBList"
                Surface(
                    onClick = { onListSelect(list) },
                    shape = ClickableSurfaceDefaults.shape(
                        shape = RoundedCornerShape(12.dp)
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = when {
                            selected -> KBAccent.copy(alpha = 0.22f)
                            focused -> KBSurfaceRaised
                            else -> KBSurface
                        },
                        contentColor = KBTextHi
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = list.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) KBAccent else KBTextHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${list.itemCount} titles · $sourceLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = KBTextLo
                        )
                    }
                }
            }

            // Create-list affordance at the bottom of the rail.
            item(key = "create_list") {
                if (showCreateField) {
                    Column {
                        KBTextField(
                            value = newListName,
                            onValueChange = { newListName = it },
                            placeholder = "New list name",
                            modifier = Modifier.fillMaxWidth(),
                            onDone = {
                                if (newListName.isNotBlank()) {
                                    onCreateList(newListName)
                                }
                                newListName = ""
                                showCreateField = false
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LibraryFilterChip(
                            label = "CREATE",
                            selected = false,
                            onClick = {
                                if (newListName.isNotBlank()) {
                                    onCreateList(newListName)
                                }
                                newListName = ""
                                showCreateField = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    LibraryFilterChip(
                        label = "+ NEW LIST",
                        selected = false,
                        onClick = { showCreateField = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        ItemGrid(
            items = listItems,
            emptyText = when {
                loading && selectedList == null -> "Loading lists…"
                selectedList == null -> "Select a list on the left."
                else -> "This list is empty. Long-press any poster app-wide " +
                    "and choose \"Add to list…\" to fill it."
            },
            sourceLabel = { it.source.label },
            onItemClick = onItemClick,
            onItemLongClick = onItemLongClick,
            ratings = ratings,
            watchedKeys = watchedKeys
        )
    }
}

@Composable
private fun LibraryPosterCard(
    item: LibraryItem,
    sourceLabel: String,
    rating: Double?,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(118.dp)
    ) {
        KBCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .width(118.dp)
                .aspectRatio(2f / 3f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isWatched) {
                    // Same checkmark badge language the other poster grids
                    // use for fully-watched titles.
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.labelLarge,
                        color = KBAccent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(
                                Color.Black.copy(alpha = 0.65f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.posterUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(KBSurfaceRaised)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = KBTextLo,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        }

        // Captions go through the shared PosterCaptions block so the
        // Settings toggles (poster titles / years / star ratings) behave
        // here exactly like on every other screen. The source tag rides
        // below, un-gated, so tracker origin stays visible.
        PosterCaptions(
            title = item.title,
            year = item.year?.toString(),
            rating = rating,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
