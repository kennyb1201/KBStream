package com.kennyb1201.kbstream.ui.nuvio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.nuvio.NuvioContentItem
import com.kennyb1201.kbstream.data.nuvio.NuvioFolder
import com.kennyb1201.kbstream.data.nuvio.NuvioRail
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.components.LandscapeCard
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

private val FolderPosterWidth = 124.dp
private val FolderPosterHeight = 180.dp
private val FolderLandscapeWidth = 210.dp
private val FolderLandscapeHeight = 118.dp
private val FolderRailGap = 12.dp
private val FolderHeroHeight = 280.dp
private val FolderSafeHorizontal = 12.dp

/** The three folder layout modes a Nuvio profile can request. */
object NuvioLayoutModes {
    enum class Mode { FOLLOW_LAYOUT, ROWS, GRID }

    fun fromViewMode(viewMode: String?): Mode = when (viewMode?.uppercase()) {
        "ROWS" -> Mode.ROWS
        "GRID" -> Mode.GRID
        else -> Mode.FOLLOW_LAYOUT
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun FolderTitleBlock(folder: NuvioFolder?) {
    if (folder == null) return
    val logoUrl = folder.titleLogoUrl?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = FolderSafeHorizontal,
                end = FolderSafeHorizontal,
                top = 16.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (logoUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(logoUrl).build(),
                contentDescription = folder.title,
                modifier = Modifier.height(36.dp)
            )
        } else {
            Text(
                text = folder.title,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SourceTabs(
    state: NuvioFolderViewModel.UiState,
    selectedSourceId: String?,
    onSelectSource: (String?) -> Unit
) {
    if (!state.showAllTab || state.rails.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolderSafeHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabChip(
            label = "All",
            selected = selectedSourceId == null,
            onClick = { onSelectSource(null) }
        )
        state.rails.forEach { rail ->
            TabChip(
                label = rail.title,
                selected = selectedSourceId == rail.sourceId,
                onClick = { onSelectSource(rail.sourceId) }
            )
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick) {
        Box(
            modifier = Modifier
                .background(
                    if (selected) KBAccent.copy(alpha = 0.22f) else KBSurface,
                    RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                color = if (selected) KBAccent else KBTextHi,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FolderLoading(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun FolderErrorMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun RailTitle(text: String) {
    Text(
        text = text,
        color = KBTextHi.copy(alpha = 0.94f),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            start = FolderSafeHorizontal,
            top = 4.dp,
            bottom = 2.dp
        )
    )
}

/**
 * Poster or landscape card for collection ITEMS. Honors the global
 * "Landscape Cards" toggle for Rows and Follow-Home layouts (grid always
 * keeps regular posters), plus the global Title/Year/Star-Rating caption
 * toggles via PosterCaptions.
 */
@Composable
private fun FolderItemCard(
    item: NuvioContentItem,
    tileShape: String?,
    isWatched: Boolean,
    showLandscapeCards: Boolean,
    showCaptions: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: (() -> Unit)? = null
) {
    // Folder tileShape still wins for per-folder landscape folders; the
    // global toggle switches poster->landscape for the item art itself in
    // Rows and Follow-Home (never in Grid — grid stays posters).
    val isLandscape = tileShape?.uppercase() == "LANDSCAPE" ||
        (showLandscapeCards && tileShape?.uppercase() != "POSTER")
    val width = if (isLandscape) FolderLandscapeWidth else FolderPosterWidth
    val height = if (isLandscape) FolderLandscapeHeight else FolderPosterHeight

    val focusModifier = if (onFocus != null) {
        Modifier.onFocusChanged { if (it.isFocused) onFocus() }
    } else {
        Modifier
    }

    Column {
        Box(
            modifier = Modifier
                .width(width)
                .height(height + 24.dp)
                .padding(end = FolderRailGap),
            contentAlignment = Alignment.Center
        ) {
            if (isLandscape) {
                LandscapeCard(
                    backdropUrl = item.backdropUrl ?: item.posterUrl,
                    logoUrl = null,
                    fallbackTitle = item.title,
                    contentDescription = item.title,
                    isWatched = isWatched,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = focusModifier
                )
            } else {
                PosterCard(
                    posterUrl = item.posterUrl,
                    contentDescription = item.title,
                    isWatched = isWatched,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = focusModifier
                )
            }
        }
        if (showCaptions) {
            PosterCaptions(
                title = item.title,
                year = item.year,
                rating = item.rating,
                modifier = Modifier.padding(top = 2.dp, start = FolderRailGap / 2)
            )
        }
    }
}

/** Watched lookup: addon items key on their imdb id directly; TMDB items
 *  on the imdb id resolved by the ViewModel. */
private fun itemWatched(
    item: NuvioContentItem,
    watchedKeys: Set<String>,
    resolvedIds: Map<String, String>
): Boolean {
    val normalized = when (item.type.lowercase()) {
        "series", "tv" -> "series"
        else -> "movie"
    }
    val imdbId = item.id.takeIf { it.startsWith("tt") }
        ?: item.tmdbId?.let { resolvedIds["$normalized::$it"] }
        ?: return false
    return "$normalized::$imdbId" in watchedKeys
}

// ---------------------------------------------------------------------------
// FOLLOW_LAYOUT — hero + rails, mirrors Home
// ---------------------------------------------------------------------------

@Composable
private fun FollowHomeLayout(
    state: NuvioFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    onOpenItem: (NuvioContentItem) -> Unit,
    onLongPressItem: (NuvioContentItem) -> Unit
) {
    val context = LocalContext.current
    var heroItem by remember { mutableStateOf<NuvioContentItem?>(null) }

    // Folder-hosted hero art: the folder's backdrop + logo/title image sit
    // behind the hero until the user focuses a title, then that title's art
    // takes over — Nuvio's "follow home layout" behavior.
    val hero = heroItem
        ?: state.rails.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
    val heroBackdrop = heroItem?.backdropUrl
        ?: state.folder?.heroBackdropUrl
        ?: state.rails.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()?.backdropUrl
    val heroLogo = state.folder?.titleLogoUrl

    val visibleRails = if (selectedSourceId != null) {
        state.rails.filter { it.sourceId == selectedSourceId }
    } else {
        state.rails
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FolderHeroHeight)
        ) {
            if (heroBackdrop != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(heroBackdrop).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.86f),
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = FolderSafeHorizontal,
                        end = FolderSafeHorizontal
                    )
            ) {
                if (heroLogo != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(heroLogo).build(),
                        contentDescription = null,
                        modifier = Modifier.height(48.dp)
                    )
                } else if (state.folder != null) {
                    Text(
                        text = state.folder.title,
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                hero?.let { item ->
                    Text(
                        text = listOfNotNull(
                            item.title,
                            item.year?.let { "($it)" }
                        ).joinToString(" "),
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        SourceTabs(state, selectedSourceId, onSelectSource)

        when {
            state.isLoading -> FolderLoading("Loading collection…")
            state.error != null -> FolderErrorMessage(state.error.orEmpty())
            visibleRails.isEmpty() -> FolderErrorMessage("Nothing to show here yet.")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    visibleRails.forEach { rail ->
                        item(key = "title:${rail.sourceId}") {
                            RailTitle(rail.title)
                        }
                        item(key = "rail:${rail.sourceId}") {
                            LazyRow(
                                contentPadding = PaddingValues(
                                    start = FolderSafeHorizontal,
                                    end = FolderSafeHorizontal
                                )
                            ) {
                                items(
                                    items = rail.items,
                                    key = { "${rail.sourceId}:${it.type}:${it.id}" }
                                ) { item ->
                                    FolderItemCard(
                                        item = item,
                                        tileShape = state.folder?.tileShape,
                                        isWatched = itemWatched(item, watchedKeys, resolvedIds),
                                        showLandscapeCards = AppPreferences.getHomeLandscapeCards(context),
                                        showCaptions = false,
                                        onClick = { onOpenItem(item) },
                                        onLongClick = { onLongPressItem(item) },
                                        onFocus = { heroItem = item }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ROWS — plain rail list, no hero
// ---------------------------------------------------------------------------

@Composable
private fun RowsLayout(
    state: NuvioFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    onOpenItem: (NuvioContentItem) -> Unit,
    onLongPressItem: (NuvioContentItem) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        FolderTitleBlock(state.folder)
        SourceTabs(state, selectedSourceId, onSelectSource)

        val visibleRails = if (selectedSourceId != null) {
            state.rails.filter { it.sourceId == selectedSourceId }
        } else {
            state.rails
        }

        when {
            state.isLoading -> FolderLoading("Loading collection…")
            state.error != null -> FolderErrorMessage(state.error.orEmpty())
            visibleRails.isEmpty() -> FolderErrorMessage("Nothing to show here yet.")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    visibleRails.forEach { rail ->
                        item(key = "title:${rail.sourceId}") {
                            RailTitle(rail.title)
                        }
                        item(key = "rail:${rail.sourceId}") {
                            LazyRow(
                                contentPadding = PaddingValues(
                                    start = FolderSafeHorizontal,
                                    end = FolderSafeHorizontal
                                )
                            ) {
                                items(
                                    items = rail.items,
                                    key = { "${rail.sourceId}:${it.type}:${it.id}" }
                                ) { item ->
                                    FolderItemCard(
                                        item = item,
                                        tileShape = state.folder?.tileShape,
                                        isWatched = itemWatched(item, watchedKeys, resolvedIds),
                                        showLandscapeCards = AppPreferences.getHomeLandscapeCards(context),
                                        showCaptions = true,
                                        onClick = { onOpenItem(item) },
                                        onLongClick = { onLongPressItem(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// GRID — poster grid of the merged items
// ---------------------------------------------------------------------------

@Composable
private fun GridLayout(
    state: NuvioFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    onOpenItem: (NuvioContentItem) -> Unit,
    onLongPressItem: (NuvioContentItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        FolderTitleBlock(state.folder)
        SourceTabs(state, selectedSourceId, onSelectSource)

        val gridItems = remember(state.rails, selectedSourceId) {
            val rails = if (selectedSourceId != null) {
                state.rails.filter { it.sourceId == selectedSourceId }
            } else {
                state.rails
            }
            val seen = mutableSetOf<String>()
            rails.flatMap { it.items }.filter { seen.add("${it.type}:${it.id}") }
        }

        when {
            state.isLoading -> FolderLoading("Loading collection…")
            state.error != null -> FolderErrorMessage(state.error.orEmpty())
            gridItems.isEmpty() -> FolderErrorMessage("Nothing to show here yet.")
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(
                        start = FolderSafeHorizontal,
                        end = FolderSafeHorizontal,
                        top = 8.dp,
                        bottom = 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = gridItems,
                        key = { "grid:${it.type}:${it.id}" }
                    ) { item ->
                        Column {
                            PosterCard(
                                posterUrl = item.posterUrl,
                                contentDescription = item.title,
                                isWatched = itemWatched(item, watchedKeys, resolvedIds),
                                onClick = { onOpenItem(item) },
                                onLongClick = { onLongPressItem(item) },
                                modifier = Modifier
                                    .width(FolderPosterWidth)
                                    .height(FolderPosterHeight)
                            )
                            // Grid always keeps regular posters, but its
                            // item captions honor the toggles like Rows.
                            PosterCaptions(
                                title = item.title,
                                year = item.year,
                                rating = item.rating,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Internal dispatch used by NuvioFolderScreen. */
@Composable
internal fun NuvioFolderBody(
    layoutMode: NuvioLayoutModes.Mode,
    state: NuvioFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    onOpenItem: (NuvioContentItem) -> Unit,
    onLongPressItem: (NuvioContentItem) -> Unit
) {
    when (layoutMode) {
        NuvioLayoutModes.Mode.FOLLOW_LAYOUT ->
            FollowHomeLayout(
                state, resolvedIds, selectedSourceId, watchedKeys,
                onSelectSource, onOpenItem, onLongPressItem
            )

        NuvioLayoutModes.Mode.ROWS ->
            RowsLayout(
                state, resolvedIds, selectedSourceId, watchedKeys,
                onSelectSource, onOpenItem, onLongPressItem
            )

        NuvioLayoutModes.Mode.GRID ->
            GridLayout(
                state, resolvedIds, selectedSourceId, watchedKeys,
                onSelectSource, onOpenItem, onLongPressItem
            )
    }
}
