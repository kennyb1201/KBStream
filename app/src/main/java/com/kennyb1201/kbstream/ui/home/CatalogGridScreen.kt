package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Full-catalog poster grid ("Open in Grid" on a Home rail's long-press
 * menu): the whole catalog browsable without endless horizontal scrolling.
 * Seeds with the rail's loaded items, then pages the rest in as the user
 * scrolls down — same 100-item batches as the rails.
 */
@Composable
fun CatalogGridScreen(
    title: String,
    addonName: String,
    viewModel: HomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(),
    onItemClick: (MetaPreview) -> Unit,
    onBack: () -> Unit
) {
    val grid by viewModel.catalogGrid.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()

    var menuTarget by remember { mutableStateOf<MetaPreview?>(null) }

    // Fresh screen instance (or restored route): (re)open the catalog from
    // its identity. No-op when the grid is already showing this catalog —
    // preserves loaded pages across recompositions. The DisposableEffect
    // clears the shared ViewModel state when the screen leaves composition
    // so a stale grid never leaks into later Home sessions.
    LaunchedEffect(title, addonName) {
        viewModel.openCatalogInGrid(title, addonName)
    }

    DisposableEffect(title, addonName) {
        onDispose {
            viewModel.closeCatalogGrid()
        }
    }

    val state = grid ?: run {
        // Grid closed (or never opened) — pop straight back to Home.
        LaunchedEffect(Unit) { onBack() }
        Box(modifier = Modifier.fillMaxSize().background(KBVoid))
        return
    }

    val gridState = rememberLazyGridState()

    // Infinite scroll: one screenful from the bottom, ask for the next page.
    LaunchedEffect(state.items.size, state.hasMore) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = state.items.size
                if (total > 0 && lastVisible >= total - 12) {
                    viewModel.loadMoreGridItems()
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = KBTextHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 18.dp,
                    bottom = 2.dp
                )
        )

        Text(
            text = state.addonName,
            style = MaterialTheme.typography.labelMedium,
            color = KBTextLo,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, bottom = 10.dp)
        )

        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = KBAccent,
                        strokeWidth = 3.dp
                    )
                }
            }

            state.error != null && state.items.isEmpty() -> {
                Text(
                    text = "Error: ${state.error}",
                    color = KBTextLo,
                    modifier = Modifier.padding(24.dp)
                )
            }

            state.items.isEmpty() -> {
                Text(
                    text = "Nothing to show here.",
                    color = KBTextLo,
                    modifier = Modifier.padding(24.dp)
                )
            }

            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 4.dp,
                        bottom = 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = state.items,
                        key = { "${it.type}:${it.id}" }
                    ) { meta ->
                        Column {
                            PosterCard(
                                posterUrl = meta.poster,
                                contentDescription = meta.name,
                                isWatched =
                                    viewModel.watchedKey(meta.id, meta.type) in
                                        watchedKeys,
                                onClick = {
                                    onItemClick(meta)
                                },
                                onLongClick = {
                                    menuTarget = meta
                                },
                                modifier = Modifier
                                    .size(
                                        width = 124.dp,
                                        height = 180.dp
                                    )
                            )
                            Text(
                                text = meta.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = KBTextLo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(124.dp)
                                    .padding(top = 2.dp)
                            )
                        }
                    }

                    if (state.isLoadingMore) {
                        item(
                            key = "grid_footer",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = KBAccent,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    menuTarget?.let { target ->
        val isWatched =
            viewModel.watchedKey(target.id, target.type) in watchedKeys

        PosterContextMenu(
            title = target.name,
            actions = listOf(
                PosterContextAction(
                    label = "Go to Details",
                    description = "Open this title's detail page"
                ) {
                    menuTarget = null
                    onItemClick(target)
                },
                PosterContextAction(
                    label = if (isWatched) {
                        "Mark as Unwatched"
                    } else {
                        "Mark as Watched"
                    },
                    description = if (isWatched) {
                        "Clear watched status on this device and Simkl"
                    } else {
                        "Show this title as watched"
                    }
                ) {
                    menuTarget = null
                    if (isWatched) {
                        viewModel.markUnwatched(target)
                    } else {
                        viewModel.markAsWatched(target)
                    }
                }
            ),
            onDismiss = {
                menuTarget = null
            }
        )
    }
}
