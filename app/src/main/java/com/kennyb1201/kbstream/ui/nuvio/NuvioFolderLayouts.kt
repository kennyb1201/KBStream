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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.nuvio.NuvioContentItem
import com.kennyb1201.kbstream.data.nuvio.NuvioFolder
import com.kennyb1201.kbstream.data.nuvio.NuvioRail
import com.kennyb1201.kbstream.data.tmdb.HeroArtwork
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.LandscapeCard
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.home.HomeHeroArtwork
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private val FolderPosterWidth = 124.dp
private val FolderPosterHeight = 180.dp
private val FolderLandscapeWidth = 210.dp
private val FolderLandscapeHeight = 118.dp
private val FolderRailGap = 12.dp
private val FolderHeroHeight = 300.dp
private val FolderSafeHorizontal = 12.dp
private val FolderRailSectionGap = 20.dp

// Same 4s dwell as Home before the hero trailer starts playing.
private const val FolderHeroTrailerDwellMs = 4_000L

/** The three folder layout modes a Nuvio profile can request. */
object NuvioLayoutModes {
    enum class Mode { FOLLOW_LAYOUT, ROWS, GRID }

    fun fromViewMode(viewMode: String?): Mode = when (viewMode?.uppercase()) {
        "ROWS" -> Mode.ROWS
        "GRID" -> Mode.GRID
        // FOLLOW_LAYOUT is the default: folders render as hero + rails,
        // visually identical to Home, unless the profile asks otherwise.
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
                style = MaterialTheme.typography.titleMedium,
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

/**
 * Rail title in Home's exact format: "Catalog · Type" with the type suffix
 * gated by the global "Show Catalog Type" toggle (Settings > Interface),
 * e.g. "Trending · Movie". Mirrors HomeScreen.homeRailTitle.
 */
@Composable
private fun RailTitle(
    rail: NuvioRail,
    showType: Boolean
) {
    // A rail mixes provider kinds rarely; the dominant item type names the row.
    val type = rail.items
        .groupingBy { it.type }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        .orEmpty()

    val parts = buildList {
        add(rail.title)
        if (showType && type.isNotBlank()) {
            add(type.replaceFirstChar { it.uppercase() })
        }
    }

    Text(
        text = parts.joinToString(" · "),
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
 * Poster or landscape card for collection ITEMS. The manifest tileShape only
 * ever shapes the folder TILES on Home — it never reaches the rails inside
 * an opened folder. Item cards here follow the global Home "Landscape Cards"
 * toggle exactly (landscape there = landscape here, posters there = posters
 * here), matching Home's own rails.
 */
@Composable
private fun FolderItemCard(
    item: NuvioContentItem,
    isWatched: Boolean,
    showLandscapeCards: Boolean,
    showCaptions: Boolean,
    art: HeroArtwork?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: (() -> Unit)? = null
) {
    val isLandscape = showLandscapeCards
    val width = if (isLandscape) FolderLandscapeWidth else FolderPosterWidth
    val height = if (isLandscape) FolderLandscapeHeight else FolderPosterHeight

    val focusModifier = if (onFocus != null) {
        Modifier.onFocusChanged { if (it.isFocused) onFocus() }
    } else {
        Modifier
    }

    Column {
        // Home's exact rail geometry: the card is (width x height) centered
        // inside a (height + 24dp focus headroom) box whose end padding is
        // the inter-card gap.
        Box(
            modifier = Modifier
                .width(width)
                .height(height + 24.dp)
                .padding(end = FolderRailGap),
            contentAlignment = Alignment.Center
        ) {
            val cardModifier = focusModifier
                .offset(y = (-3).dp)
                .width(width)
                .height(height)
            if (isLandscape) {
                LandscapeCard(
                    backdropUrl = art?.backdropUrl ?: item.backdropUrl,
                    logoUrl = art?.logoUrl,
                    fallbackTitle = item.title,
                    contentDescription = item.title,
                    isWatched = isWatched,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = cardModifier
                )
            } else {
                PosterCard(
                    posterUrl = item.posterUrl,
                    contentDescription = item.title,
                    isWatched = isWatched,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = cardModifier
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

/**
 * The folder hero: delegates to Home's own HomeHero composable so a
 * FOLLOW_LAYOUT folder is a pixel-identical replica of the home hero —
 * same clearlogo, gradients, metadata line and inline trailer player.
 * Art is strictly the focused item's own (VM already falls back through
 * item backdrop → alternate poster), so the collection banner never
 * flashes over an item the way Home never shows another rail's art.
 */
@Composable
private fun FolderHero(
    viewModel: NuvioFolderViewModel,
    focusedItem: NuvioContentItem?,
    heroTrailerReady: Boolean
) {
    val context = LocalContext.current

    // Home's exact proportional hero sizing: the rails get 52% of the real
    // screen height and the hero takes the remainder (capped), which scales
    // to any TV density — a fixed height pushes the first rail's posters
    // off shorter panels, which is the bug this sizing exists to avoid.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val railsFraction = 0.52f
    val heroComputedHeight =
        (screenHeight * (1f - railsFraction)).coerceAtMost(FolderHeroHeight)
    val heroPreview by viewModel.heroPreview.collectAsStateWithLifecycle()
    val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle()
    val heroTmdbDetail by viewModel.heroTmdbDetail.collectAsStateWithLifecycle()
    val heroBackdropUrl by viewModel.heroBackdropUrl.collectAsStateWithLifecycle()
    val heroLogoUrl by viewModel.heroLogoUrl.collectAsStateWithLifecycle()
    val heroTrailerKey by viewModel.heroTrailerKey.collectAsStateWithLifecycle()

    val preview = heroPreview ?: focusedItem?.let { item ->
        MetaPreview(
            id = item.id,
            type = normalizeItemType(item),
            name = item.title ?: "",
            poster = item.posterUrl,
            background = item.backdropUrl,
            description = item.overview
        )
    } ?: return

    val autoPlay = heroTrailerReady &&
        AppPreferences.getHeroTrailerAutoplay(context)

    HomeHeroArtwork(
        preview = preview,
        meta = heroMeta,
        tmdbDetail = heroTmdbDetail,
        heroBackdropUrl = heroBackdropUrl,
        heroLogoUrl = heroLogoUrl,
        trailerKey = heroTrailerKey,
        autoPlayTrailer = autoPlay,
        muted = AppPreferences.getHeroTrailerMuted(context),
        heroHeight = heroComputedHeight
    )
}

/**
 * Home's InfiniteRailPageHandler pattern applied to a folder rail: when the
 * last visible card comes within 6 items of the end, ask the ViewModel for
 * the next TMDB page. [NuvioFolderViewModel.loadMoreRail] self-guards on
 * hasMore/isLoadingMore, so repeated emissions are no-ops.
 */
@Composable
private fun FolderRailPageHandler(
    rail: NuvioRail,
    railContext: RailCardContext,
    viewModel: NuvioFolderViewModel,
    onFocusItem: (NuvioContentItem) -> Unit
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(
            start = FolderSafeHorizontal,
            end = FolderSafeHorizontal,
            top = 4.dp,
            bottom = 12.dp
        )
    ) {
        items(
            items = rail.items,
            key = { "${rail.sourceId}:${it.type}:${it.id}" }
        ) { item ->
            FolderItemCard(
                item = item,
                isWatched = railContext.watchedLookup(item),
                showLandscapeCards = railContext.showLandscapeCards,
                // Home rails carry no captions; a "replica" folder follows
                // suit.
                showCaptions = false,
                art = railContext.landscapeArt[
                    "${normalizeItemType(item)}:${item.id}"
                ],
                onClick = { railContext.onOpenItem(item) },
                onLongClick = { railContext.onLongPressItem(item) },
                onFocus = { onFocusItem(item) }
            )
        }
    }

    LaunchedEffect(listState, rail.items.size, rail.sourceId) {
        snapshotFlow {
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to rail.items.size
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                val threshold = 6
                if (totalItems > 0 && lastVisibleIndex >= totalItems - threshold) {
                    viewModel.loadMoreRail(rail.sourceId)
                }
            }
    }
}

/** Everything a rail's cards need, bundled so FOLLOW_LAYOUT and ROWS share. */
private data class RailCardContext(
    val showLandscapeCards: Boolean,
    val landscapeArt: Map<String, HeroArtwork>,
    val watchedLookup: (NuvioContentItem) -> Boolean,
    val onOpenItem: (NuvioContentItem) -> Unit,
    val onLongPressItem: (NuvioContentItem) -> Unit,
    val onFocus: (NuvioContentItem) -> Unit = {}
)

// ---------------------------------------------------------------------------
// FOLLOW_LAYOUT — hero + rails, pixel-matched to Home
// ---------------------------------------------------------------------------

@Composable
private fun FollowHomeLayout(
    viewModel: NuvioFolderViewModel,
    state: NuvioFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    onOpenItem: (NuvioContentItem) -> Unit,
    onLongPressItem: (NuvioContentItem) -> Unit
) {
    val context = LocalContext.current
    val showLandscapeCards = remember {
        AppPreferences.getHomeLandscapeCards(context)
    }
    val showRailType = remember {
        AppPreferences.getHomeRailShowCatalogType(context)
    }

    val landscapeArt by viewModel.landscapeArt.collectAsStateWithLifecycle()

    val railCardContext = RailCardContext(
        showLandscapeCards = showLandscapeCards,
        landscapeArt = landscapeArt,
        watchedLookup = { item -> itemWatched(item, watchedKeys, resolvedIds) },
        onOpenItem = onOpenItem,
        onLongPressItem = onLongPressItem
    )

    // Keyed on the folder id so navigating folder A -> folder B reseeds the
    // hero instead of keeping A's focused item.
    var heroItem by remember(state.folder?.id) {
        mutableStateOf<NuvioContentItem?>(null)
    }
    var heroTrailerReady by remember { mutableStateOf(false) }

    // Landscape-card artwork resolves in the background while the global
    // toggle is on (and re-runs as infinite-scroll pages append; the VM only
    // fetches items it hasn't resolved yet).
    LaunchedEffect(showLandscapeCards, state.rails) {
        viewModel.ensureLandscapeArt(showLandscapeCards)
    }

    // Seed the hero with the first rail's first item (Nuvio behavior). Keyed
    // only on the rails list so a page append seeds nothing new — the focus
    // effect below owns re-resolution, and re-keying on rails would restart
    // the trailer dwell mid-watch every time infinite-scroll fires.
    LaunchedEffect(state.rails) {
        if (heroItem == null) {
            heroItem = state.rails
                .firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
        }
    }

    // Home's exact focus pipeline: resolve on stop, 4s dwell, then trailer.
    LaunchedEffect(heroItem?.id, heroItem?.type) {
        val target = heroItem ?: return@LaunchedEffect
        heroTrailerReady = false
        viewModel.resolveHero(target)
        delay(FolderHeroTrailerDwellMs)
        heroTrailerReady = true
    }

    val visibleRails = if (selectedSourceId != null) {
        state.rails.filter { it.sourceId == selectedSourceId }
    } else {
        state.rails
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FolderHero(
            viewModel = viewModel,
            focusedItem = heroItem,
            heroTrailerReady = heroTrailerReady
        )

        SourceTabs(state, selectedSourceId, onSelectSource)

        when {
            state.isLoading -> FolderLoading("Loading collection…")
            state.error != null -> FolderErrorMessage(state.error.orEmpty())
            visibleRails.isEmpty() -> FolderErrorMessage("Nothing to show here yet.")
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(FolderRailSectionGap)
                ) {
                    visibleRails.forEach { rail ->
                        item(key = "title:${rail.sourceId}") {
                            RailTitle(rail, showRailType)
                        }
                        item(key = "rail:${rail.sourceId}") {
                            FolderRailPageHandler(
                                rail = rail,
                                railContext = railCardContext,
                                viewModel = viewModel,
                                onFocusItem = { heroItem = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeItemType(item: NuvioContentItem): String =
    when (item.type.lowercase()) {
        "series", "tv" -> "series"
        else -> "movie"
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
    val showLandscapeCards = remember {
        AppPreferences.getHomeLandscapeCards(context)
    }
    val showRailType = remember {
        AppPreferences.getHomeRailShowCatalogType(context)
    }

    val visibleRails = if (selectedSourceId != null) {
        state.rails.filter { it.sourceId == selectedSourceId }
    } else {
        state.rails
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FolderTitleBlock(state.folder)
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
                            RailTitle(rail, showRailType)
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
                                        isWatched = itemWatched(item, watchedKeys, resolvedIds),
                                        showLandscapeCards = showLandscapeCards,
                                        showCaptions = true,
                                        art = null,
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
    viewModel: NuvioFolderViewModel,
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
                viewModel, state, resolvedIds, selectedSourceId, watchedKeys,
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
