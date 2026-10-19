package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.kb.KBContentItem
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.kbFolderBrowseScreenKey
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.PosterCard
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.kb.KBFolderViewModel
import com.kennyb1201.kbstream.ui.kb.kbFolderCardKey
import com.kennyb1201.kbstream.ui.kb.visibleFolderRails
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * One imported KB collection, for a finger.
 *
 * The TV page has three layouts (follow the collection's own hero+rails, plain
 * rows, or a grid) because a 10-foot screen can afford a hero and wants the D-pad
 * to walk rails. A phone gets the one layout that fits it: the collection's
 * merged titles as a poster grid, with the collection's source tabs above it.
 * The data is the same [KBFolderViewModel] and the same rules:
 *
 *  - the folder is loaded by id (`loadById(folderId)`), with its own RETRY on a
 *    failed load (`retry(folderId)`), so a transient failure does not lose the
 *    way back into the collection;
 *  - source tabs appear under the TV page's gate (`showAllTab` with at least two
 *    rails) and select through `selectSource`, All meaning every rail;
 *  - hidden titles are dropped by the TV page's own `visibleFolderRails`, with
 *    the same resolved-IMDB bridge, so a title hidden on Home or in the Library
 *    leaves this grid too;
 *  - cards are keyed the TV page keys them (`kbFolderCardKey`), which is also the
 *    identity a return out of Detail records, so backing out reopens the grid
 *    scrolled to the poster that was pressed;
 *  - a long press raises the touch poster menu with the TV rows that mean
 *    something here: Go to Details, the watched toggle, add to library and Hide.
 *    "Open in Grid" is deliberately absent - the phone page is already the grid.
 *
 * `resolveAndNavigate` is the TV call: it resolves the card's IMDb id (resolving
 * a TMDB id when the card carries one) before the detail page is opened, exactly
 * as the D-pad page does.
 */

/**
 * The watched keys a KB card can carry, in the TV page's candidate order:
 * an add-on item's own `tt` id, or for a TMDB item the resolved IMDb flavour
 * first and the raw `tmdb:<n>` one second.
 *
 * A separate copy would be a second reading of the watched-key rule, so this
 * mirrors the rail's inline check (see `KBFolderScreen`'s menu arithmetic) and
 * is pinned by a test.
 */
internal fun mobileKbWatchedKeys(
    item: KBContentItem,
    resolvedIds: Map<String, String>
): List<String> {
    val type = when (item.type.lowercase()) {
        "series", "tv" -> "series"
        else -> "movie"
    }
    val ids = if (item.id.startsWith("tt")) {
        listOf(item.id)
    } else {
        listOfNotNull(
            item.tmdbId?.let { resolvedIds["$type::$it"] },
            item.tmdbId?.let { WatchedStatusKeys.tmdbKeyId(it) }
        )
    }
    return ids.distinct().map { WatchedStatusKeys.key(it, type) }
}

@Composable
fun KBFolderScreenMobile(
    folderId: String,
    onItemClick: (
        type: String,
        imdbId: String,
        poster: String?,
        backdrop: String?,
        title: String?
    ) -> Unit,
    viewModel: KBFolderViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()

    var menuTarget by remember { mutableStateOf<KBContentItem?>(null) }
    var titleLogoFailed by remember { mutableStateOf(false) }

    val gridState = remember(folderId) { LazyGridState() }
    val returnKey = remember(folderId) { kbFolderBrowseScreenKey(folderId) }
    var returnTarget by remember(folderId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }

    LaunchedEffect(folderId) {
        viewModel.loadById(folderId)
    }

    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val items = remember(state.rails, selectedSourceId, hiddenTitleKeys, resolvedIds) {
        val rails = if (selectedSourceId != null) {
            state.rails.filter { it.sourceId == selectedSourceId }
        } else {
            state.rails
        }
        val seen = mutableSetOf<String>()
        visibleFolderRails(rails, hiddenTitleKeys, resolvedIds)
            .flatMap { rail -> rail.items }
            .filter { item -> seen.add(kbFolderCardKey(item)) }
    }

    // The page a Detail visit came back for: read once the folder's load has
    // settled, so it is not consumed against an empty list and then lost.
    LaunchedEffect(state.isLoading, folderId) {
        if (state.isLoading || returnTarget != null) return@LaunchedEffect
        returnTarget = BrowseReturnFocus.consume(returnKey)
    }
    LaunchedEffect(returnTarget, items) {
        val target = returnTarget ?: return@LaunchedEffect
        val index = items.indexOfFirst { kbFolderCardKey(it) == target.itemId }
            .takeIf { it >= 0 }
            ?: target.position.takeIf { it in items.indices }
            ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        returnTarget = null
    }

    MobileTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FolderHeaderMobile(
                    title = state.folder?.title?.takeIf { it.isNotBlank() } ?: "Collection",
                    logoUrl = state.folder?.titleLogoUrl?.takeIf { it.isNotBlank() },
                    logoFailed = titleLogoFailed,
                    onLogoError = { titleLogoFailed = true },
                    count = items.size
                )

                if (state.showAllTab && state.rails.size >= 2) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item(key = "all") {
                            FolderSourceChip(
                                label = "All",
                                selected = selectedSourceId == null,
                                onClick = { viewModel.selectSource(null) }
                            )
                        }
                        items(items = state.rails, key = { it.sourceId }) { rail ->
                            FolderSourceChip(
                                label = rail.title,
                                selected = selectedSourceId == rail.sourceId,
                                onClick = { viewModel.selectSource(rail.sourceId) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                when {
                    state.isLoading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = KBAccent)
                    }

                    state.error != null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.error.orEmpty(),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        KBButton(
                            label = "RETRY",
                            onClick = { viewModel.retry(folderId) }
                        )
                    }

                    items.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Nothing to show here yet.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = 28.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> kbFolderCardKey(item) }
                        ) { index, item ->
                            PosterCard(
                                posterUrl = item.posterUrl,
                                contentDescription = item.title,
                                isWatched = mobileKbWatchedKeys(item, resolvedIds)
                                    .any { it in watchedKeys },
                                isPartiallyWatched = mobileKbWatchedKeys(item, resolvedIds)
                                    .any { it in partialWatchedKeys },
                                onClick = {
                                    // Remember this card, so backing out of its
                                    // Detail page reopens the folder ON it.
                                    BrowseReturnFocus.record(
                                        BrowseReturnTarget(
                                            screenKey = returnKey,
                                            itemId = kbFolderCardKey(item),
                                            position = index
                                        )
                                    )
                                    viewModel.resolveAndNavigate(item) { type, imdbId ->
                                        onItemClick(
                                            type,
                                            imdbId,
                                            item.posterUrl,
                                            item.backdropUrl,
                                            item.title
                                        )
                                    }
                                },
                                onLongClick = { menuTarget = item },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                            )
                        }
                    }
                }
            }
        }

        menuTarget?.let { item ->
            val normalizedType = when (item.type.lowercase()) {
                "series", "tv" -> "series"
                else -> "movie"
            }
            val menuIds = LibraryIds.split(item.id)
            val menuWatched = mobileKbWatchedKeys(item, resolvedIds)
                .any { it in watchedKeys }
            PosterContextMenu(
                title = item.title ?: "Untitled",
                libraryTarget = LibraryAddTarget(
                    mediaType = normalizedType,
                    imdbId = menuIds.imdbId
                        ?: item.tmdbId?.let { resolvedIds["$normalizedType::$it"] },
                    tmdbId = menuIds.tmdbId ?: item.tmdbId,
                    title = item.title ?: "Untitled",
                    year = item.year?.take(4)?.toIntOrNull(),
                    posterUrl = item.posterUrl
                ),
                hideTarget = hideTarget(
                    item.title ?: "Untitled",
                    item.type,
                    item.posterUrl,
                    listOf(item.id, item.tmdbId?.toString())
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        menuTarget = null
                        BrowseReturnFocus.record(
                            BrowseReturnTarget(
                                screenKey = returnKey,
                                itemId = kbFolderCardKey(item),
                                position = items.indexOf(item)
                            )
                        )
                        viewModel.resolveAndNavigate(item) { type, imdbId ->
                            onItemClick(
                                type,
                                imdbId,
                                item.posterUrl,
                                item.backdropUrl,
                                item.title
                            )
                        }
                    },
                    PosterContextAction(
                        label = watchedMenuLabel(
                            isWatched = menuWatched,
                            mediaType = item.type
                        ),
                        description = watchedMenuDescription(
                            isWatched = menuWatched,
                            mediaType = item.type
                        )
                    ) {
                        if (menuWatched) {
                            viewModel.markUnwatched(item)
                        } else {
                            viewModel.markAsWatched(item)
                        }
                    }
                ),
                onDismiss = { menuTarget = null }
            )
        }
    }
}

@Composable
private fun FolderHeaderMobile(
    title: String,
    logoUrl: String?,
    logoFailed: Boolean,
    onLogoError: () -> Unit,
    count: Int
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (logoUrl != null && !logoFailed) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                onError = { onLogoError() },
                modifier = Modifier.height(32.dp)
            )
        } else {
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = when (count) {
                0 -> "No titles"
                1 -> "1 title"
                else -> "$count titles"
            },
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun FolderSourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        },
        shape = KBShapeCard,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = KBSurface,
            labelColor = KBTextLo,
            selectedContainerColor = KBAccent.copy(alpha = 0.28f),
            selectedLabelColor = KBAccent
        )
    )
}
