package com.kennyb1201.kbstream.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextAlign
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
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
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

    // The row whose long-press menu is open (null = no menu). A long press
    // opens this menu instead of removing straight away, so removal is an
    // explicit choice rather than an accidental one.
    var menuItem by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(Unit) {
        topFocusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        // Header. Title on the left, connection health on the right — each on
        // ONE line. This used to be a single Row holding the title, nine chips,
        // a weight spacer AND the status text, so with nine chips the status
        // text was measured into whatever pixels were left over and wrapped one
        // letter per line ("S I M K L +"), while the leading chips were pushed
        // off the left edge.
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "LIBRARY",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = KBAccent
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = buildList {
                    if (state.simklConnected) add("SIMKL")
                    if (state.mdbListConfigured) add("MDBLIST")
                    add("LOCAL")
                }.joinToString(" + "),
                style = MaterialTheme.typography.labelMedium,
                color = KBTextLo,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Every control lives in ONE horizontally scrollable strip: focus walks
        // it left to right and the strip scrolls to follow, so no chip is ever
        // clipped at an edge and none has to wrap its label ("RATING" was
        // breaking into "RATI/NG" when the old fixed Row ran out of room). The
        // sort group is separated by a rule rather than pushed to its own line.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(LibraryFilter.entries, key = { "filter_${it.name}" }) { filter ->
                LibraryFilterChip(
                    label = filter.label,
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    modifier = if (filter == LibraryFilter.entries.first()) {
                        Modifier.focusRequester(topFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }

            // UNWATCHED toggle: hides rows already marked watched.
            item(key = "unwatched") {
                LibraryFilterChip(
                    label = if (state.hideWatched) "WATCHED HIDDEN" else "UNWATCHED",
                    selected = state.hideWatched,
                    onClick = { viewModel.toggleHideWatched() }
                )
            }

            item(key = "sort_rule") { ChipDivider() }

            // Sort chips: Added / Title / Date / Rating.
            items(LibrarySort.entries, key = { "sort_${it.name}" }) { sort ->
                LibraryFilterChip(
                    label = sort.label,
                    selected = state.sort == sort,
                    onClick = { viewModel.setSort(sort) }
                )
            }
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
                    onItemLongClick = { item -> menuItem = item },
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
                    onItemLongClick = { item -> menuItem = item },
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
                    onItemLongClick = { item -> menuItem = item },
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
                    onItemLongClick = { item -> menuItem = item },
                    ratings = state.ratings,
                    watchedKeys = state.watchedKeys
                )
            }
        }
    }

    // Long-press menu overlay. Rendered as a sibling of the content Column so
    // it dims and traps focus above the grid, exactly like the shared poster
    // menu on every other screen.
    menuItem?.let { item ->
        LibraryItemMenu(
            item = item,
            onDismiss = { menuItem = null },
            onOpenDetails = { id ->
                menuItem = null
                onItemClick(item.mediaType, id)
            },
            onRemove = {
                menuItem = null
                viewModel.removeItem(item)
            }
        )
    }
    }
}

/**
 * Long-press menu for one Library row, built on the app-wide
 * [PosterContextMenu] so a long press looks and behaves the same here as on
 * Home, Search and the detail rails. Simkl rows are add-only (the API has no
 * remove-from-watchlist endpoint), so they get an explanatory row instead of
 * a destructive one.
 */
@Composable
private fun LibraryItemMenu(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onRemove: () -> Unit
) {
    val subtitle = listOfNotNull(
        item.year?.toString(),
        item.source.label
    ).joinToString(" · ")

    PosterContextMenu(
        title = item.title,
        subtitle = subtitle,
        actions = buildList {
            item.navigationId?.let { id ->
                add(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        onOpenDetails(id)
                    }
                )
            }

            when (item.source) {
                LibrarySource.LOCAL,
                LibrarySource.MDBLIST_WATCHLIST -> add(
                    PosterContextAction(
                        label = "Remove from Library",
                        description = "Remove it here and on every connected tracker",
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                LibrarySource.LOCAL_LIST,
                LibrarySource.MDBLIST_LIST -> add(
                    PosterContextAction(
                        label = "Remove from this list",
                        description = item.listName?.let { "Take it out of \"$it\"" },
                        isDestructive = true
                    ) {
                        onRemove()
                    }
                )

                LibrarySource.SIMKL_WATCHLIST,
                LibrarySource.SIMKL_LIST -> add(
                    PosterContextAction(
                        label = "Managed on Simkl",
                        description = "Remove it from your watchlist on simkl.com"
                    ) {
                        onDismiss()
                    }
                )
            }
        },
        onDismiss = onDismiss
    )
}

/** Thin rule between the filter group and the sort group in the chip strip. */
@Composable
private fun ChipDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(24.dp)
            .background(KBTextLo.copy(alpha = 0.35f))
    )
}

/**
 * One pill in the Library's control strips.
 *
 * Built on the TV clickable Surface, NOT a hand-rolled
 * `.focusable().clickable()` box. Two stacked focus/click modifiers make two
 * targets: the D-pad press landed focus on the outer one and only the second
 * press reached the inner clickable — the "you have to double click chips"
 * behaviour. A Surface carries focus, activation and the focused colours as a
 * single target, so one press selects, like every other control in the app.
 */
@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected -> KBAccent.copy(alpha = 0.28f)
                focused -> KBSurfaceRaised
                else -> KBSurface
            },
            contentColor = when {
                selected -> KBAccent
                focused -> KBTextHi
                else -> KBTextLo
            }
        ),
        modifier = modifier
            .clip(shape)
            .border(
                1.dp,
                when {
                    selected -> KBAccent
                    focused -> KBTextHi
                    else -> KBTextLo.copy(alpha = 0.35f)
                },
                shape
            )
            .onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            // A pill is one line by definition.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
    watchedKeys: Set<String> = emptySet(),
    modifier: Modifier = Modifier.fillMaxSize()
) {
    if (items.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = KBTextLo,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
        }
        return
    }

    val posterSize = rememberPosterSize()

    // A real grid whose cells are sized from the Poster Size setting, instead
    // of hand-chunked rows of six: with six hard-coded tiles the row could be
    // wider than the pane (clipping the last poster) and the tile count never
    // adapted to the screen or the setting. Adaptive cells always fill the
    // pane, so every poster is fully visible and nothing is left half cut.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = posterSize.width),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        items(
            items = items,
            key = { item -> LocalLibraryStore.dedupeKey(item) }
        ) { item ->
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
            watchedKeys = watchedKeys,
            // Takes the remaining width beside the list rail; fillMaxSize
            // inside a Row measured against the whole row.
            modifier = Modifier.weight(1f)
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
    val posterSize = rememberPosterSize()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // Fills the grid cell (a grid picks the cell width), so the caption and
        // source line sit under the poster instead of being measured against a
        // width the cell may not have.
        modifier = Modifier.fillMaxWidth()
    ) {
        KBCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .width(posterSize.width)
                .height(posterSize.height)
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
