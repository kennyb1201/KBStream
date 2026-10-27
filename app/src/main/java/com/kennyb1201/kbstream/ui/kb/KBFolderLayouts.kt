package com.kennyb1201.kbstream.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.kennyb1201.kbstream.data.kb.KBContentItem
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.kb.KBRail
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.HeroArtwork
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBSkeletonGrid
import com.kennyb1201.kbstream.ui.components.KBSkeletonRailStack
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.InfiniteScrollEffect
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.LandscapeCard
import com.kennyb1201.kbstream.ui.components.landscapeArtKey
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.imageOverrideCardBackdropUrl
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.home.HomeHeroArtwork
import com.kennyb1201.kbstream.ui.search.successorIndexAfter
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import kotlinx.coroutines.delay

private val FolderPosterWidth = 124.dp
private val FolderPosterHeight = 180.dp
private val FolderLandscapeWidth = 210.dp
private val FolderLandscapeHeight = 118.dp
private val FolderRailGap = 12.dp
private val FolderHeroHeight = 300.dp
private val FolderSafeHorizontal = 12.dp
private val FolderRailSectionGap = 20.dp

/** The three folder layout modes a KB profile can request. */
object KBLayoutModes {
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
private fun FolderTitleBlock(folder: KBFolder?) {
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
    state: KBFolderViewModel.UiState,
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
                    KBShapeCard
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

/**
 * Error / empty state for a KB collection, as the app's shared
 * [KBStatusMessage] card.
 *
 * These were two functions with identical bodies ([FolderLoading] and
 * [FolderErrorMessage]) wrapping a plain centered line of text, so a
 * collection that failed to load looked exactly like one that was
 * legitimately empty. Loading is no longer handled here: it draws skeleton
 * rails/grid instead, matching every other browse surface — a skeleton says
 * "this is coming", the card says "there is nothing here".
 *
 * The `weight` is what makes the card center in the space the rails would have
 * used — KBStatusMessage itself defaults to fillMaxSize, and inside a Column
 * that would run past the bottom of the screen instead of filling what is
 * left below the hero and source tabs.
 */
@Composable
private fun ColumnScope.FolderStatus(
    message: String,
    loading: Boolean = false,
    icon: String = "⚠️",
    // A failed load offers RETRY, like every other browse screen's card: the
    // folder is the one surface where a transient failure left the viewer with
    // nothing but Back, and leaving the folder loses their place in it.
    onRetry: (() -> Unit)? = null,
    // What that action says. Retry is the common case; the all-hidden state
    // below offers UNHIDE ALL, the same action Home's and the browse pages'
    // cards offer for the same fact.
    actionLabel: String = "RETRY"
) {
    KBStatusMessage(
        message = message,
        loading = loading,
        icon = icon,
        onRetry = onRetry,
        actionLabel = actionLabel,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    )
}

/**
 * Rail title in Home's exact format: "Catalog · Type" with the type suffix
 * gated by the global "Show Catalog Type" toggle (Settings > Interface),
 * e.g. "Trending · Movie". Mirrors HomeScreen.homeRailTitle.
 */
@Composable
private fun RailTitle(
    rail: KBRail,
    showType: Boolean
) {
    // A rail mixes provider kinds rarely; the dominant item type names the
    // row. Normalized before counting so "tv" (TMDB discover rows) and
    // "series" (addon rows) read identically — the type suffix only
    // ever displays "Movie" or "Series", matching the rest of the app.
    val type = rail.items
        .groupingBy { normalizeItemType(it) }
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
    item: KBContentItem,
    isWatched: Boolean,
    showLandscapeCards: Boolean,
    showCaptions: Boolean,
    art: HeroArtwork?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: (() -> Unit)? = null,
    isPartiallyWatched: Boolean = false,
    // The key this card is registered and returned to under, the map the
    // screen's long-press menu reads its requester back out of, and the one
    // card a Back out of Detail is coming back for (see [BrowseReturnFocus]).
    cardKey: String,
    focusRegistry: MutableMap<String, FocusRequester>,
    isReturnItem: Boolean = false,
    returnFocusRequester: FocusRequester? = null,
    // The page's OWN first card: where an ordinary open puts the D-pad. One card
    // per page carries it, and only while its rail is the first one drawn (see
    // the screen's entry landing).
    isEntryItem: Boolean = false,
    entryFocusRequester: FocusRequester? = null
) {
    val isLandscape = showLandscapeCards
    val width = if (isLandscape) FolderLandscapeWidth else FolderPosterWidth
    val height = if (isLandscape) FolderLandscapeHeight else FolderPosterHeight
    // The viewer's image choices, read as state so a pick repaints this card on
    // the next frame (see ImageOverrides). These cards draw their own artwork
    // rather than going through GlobalPosterCard - the rail geometry is Home's -
    // so the two choices have to be applied here or a KB folder is the one rail
    // that keeps TMDB's art after a title's has been chosen.
    val overridePaths = rememberImageOverridePaths()
    // The title's TMDB id when the source knew one, its raw id otherwise
    // ([ImageOverrides.tmdbIdOf] reads a bare number, and ignores an add-on id
    // it cannot bridge without a network lookup).
    val artId = item.tmdbId?.toString() ?: item.id

    // This card's own node, published so the screen's long-press menu can ask
    // for it by key. The map is never pruned: the entry a Hide lands on is the
    // SUCCESSOR card's, which is still on the rail and must still be found.
    val cardFocusRequester = remember(cardKey) { FocusRequester() }
    focusRegistry[cardKey] = cardFocusRequester

    // The tile's own focus drives the caption marquee below as well as the
    // caller's hook (Home keeps its header in step with the focused rail).
    var focused by remember { mutableStateOf(false) }
    val focusModifier = Modifier.onFocusChanged {
        focused = it.hasFocus
        if (it.isFocused) onFocus?.invoke()
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
                .focusRequester(cardFocusRequester)
                .then(
                    when {
                        // The card a return out of Detail is coming back for takes
                        // the page's return requester; an ordinary open asks the
                        // page's own first card (they can never be the same card,
                        // since a return is not an ordinary open).
                        isReturnItem && returnFocusRequester != null ->
                            Modifier.focusRequester(returnFocusRequester)

                        isEntryItem && entryFocusRequester != null ->
                            Modifier.focusRequester(entryFocusRequester)

                        else -> Modifier
                    }
                )
                .offset(y = (-3).dp)
                .width(width)
                .height(height)
            if (isLandscape) {
                LandscapeCard(
                    // The resolver's answer already carries a chosen still when
                    // the title has one (see LandscapeArt); this is the case
                    // where it has none, which the choice still covers.
                    backdropUrl = art?.backdropUrl ?: imageOverrideCardBackdropUrl(
                        paths = overridePaths,
                        rawId = artId,
                        fallbackUrl = item.backdropUrl
                    ),
                    logoUrl = art?.logoUrl,
                    fallbackTitle = item.title,
                    contentDescription = item.title,
                    isWatched = isWatched,
                    isPartiallyWatched = isPartiallyWatched,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = cardModifier
                )
            } else {
                PosterCard(
                    posterUrl = imageOverridePosterUrl(
                        paths = overridePaths,
                        rawId = artId,
                        fallbackUrl = item.posterUrl
                    ),
                    contentDescription = item.title,
                    isWatched = isWatched,
                    isPartiallyWatched = isPartiallyWatched,
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
                // 5dp, the beat the actor, collection and search rails use
                // under their posters (the Library's is 6): the folder's own
                // captions sat 2dp under the tile, which read as touching it.
                modifier = Modifier.padding(top = 5.dp, start = FolderRailGap / 2),
                focused = focused
            )
        }
    }
}

/** Watched lookup: addon items key on their imdb id directly; TMDB items
 *  on the imdb id resolved by the ViewModel, or - when that resolution never
 *  ran or answered nothing - on the item's own `tmdb:<n>` id, which needs no
 *  network (see [WatchedStatusKeys]). */
private fun itemWatched(
    item: KBContentItem,
    watchedKeys: Set<String>,
    resolvedIds: Map<String, String>
): Boolean {
    val normalized = when (item.type.lowercase()) {
        "series", "tv" -> "series"
        else -> "movie"
    }
    val imdbId = item.id.takeIf { it.startsWith("tt") }
    if (imdbId != null) return "$normalized::$imdbId" in watchedKeys

    val tmdbId = item.tmdbId ?: return false
    resolvedIds["$normalized::$tmdbId"]?.let { resolved ->
        if ("$normalized::$resolved" in watchedKeys) return true
    }
    return "$normalized::${WatchedStatusKeys.tmdbKeyId(tmdbId)}" in watchedKeys
}

/** Eye-badge twin of [itemWatched]: same id resolution against the
 *  partially-watched key set. Only rendered when the checkmark is absent. */
private fun itemWatchedPartially(
    item: KBContentItem,
    partialWatchedKeys: Set<String>,
    resolvedIds: Map<String, String>
): Boolean {
    val normalized = when (item.type.lowercase()) {
        "series", "tv" -> "series"
        else -> "movie"
    }
    val imdbId = item.id.takeIf { it.startsWith("tt") }
    if (imdbId != null) return "$normalized::$imdbId" in partialWatchedKeys

    val tmdbId = item.tmdbId ?: return false
    resolvedIds["$normalized::$tmdbId"]?.let { resolved ->
        if ("$normalized::$resolved" in partialWatchedKeys) return true
    }
    return "$normalized::${WatchedStatusKeys.tmdbKeyId(tmdbId)}" in partialWatchedKeys
}

/**
 * The key a folder card is recorded under for a return out of Detail, and
 * registered under for a long-press hand-back (see [BrowseReturnFocus]).
 *
 * Keyed on type AND id because a folder's items arrive from several sources at
 * once and the same number means a different title in each of them.
 */
internal fun kbFolderCardKey(item: KBContentItem): String = "${item.type}:${item.id}"

/**
 * A folder's rails with hidden titles dropped as they are read.
 *
 * The folder's own Hide row wrote its record and left the card sitting on the
 * rail - the one surface where hiding did nothing visible, because these rails
 * were built straight from the ViewModel. Read here, a long-press Hide takes
 * the tile it was pressed on off the rail, exactly as the same action does on
 * Decade, Studio, Tag, Collection and the Library; a rail it empties is
 * dropped with it, which is what the elsewhere-shared rails already do.
 */
internal fun visibleFolderRails(
    rails: List<KBRail>,
    hidden: Set<String>,
    resolvedIds: Map<String, String>
): List<KBRail> = rails.mapNotNull { rail ->
    val visible = rail.items.filterNot { item ->
        val type = normalizeItemType(item)
        HiddenTitles.hides(
            hidden,
            type,
            item.title,
            item.year?.take(4)?.toIntOrNull(),
            item.id,
            item.tmdbId?.toString(),
            // A record written from a surface that knew the IMDB id (Home, the
            // Library) is keyed on that id, so the TMDB half has to be bridged
            // here or hiding from those screens leaves the folder's copy up.
            item.tmdbId?.let { resolvedIds["$type::$it"] }
        )
    }
    rail.takeIf { visible.isNotEmpty() }?.copy(items = visible)
}

/**
 * The rails one source selection draws: the selected source's own rail, or
 * every rail for "All".
 */
internal fun folderSelectedRails(
    rails: List<KBRail>,
    selectedSourceId: String?
): List<KBRail> =
    if (selectedSourceId != null) rails.filter { it.sourceId == selectedSourceId } else rails

/**
 * True when this page served titles at all but hiding left nothing to draw.
 *
 * Both halves are required. Without the served half a folder that legitimately
 * holds nothing would read as "everything here is hidden" - the trap the browse
 * pages' own all-hidden branches document (see TagScreen). Without the visible
 * half the ordinary populated folder would claim the same thing, because a
 * selection with titles in it is non-empty whether or not they are all hidden.
 * [visible] is the selection AFTER [visibleFolderRails] has dropped the hidden
 * titles, and a rail the filter emptied is dropped with them - so an empty
 * visible list over a non-empty selection means every title was hidden.
 */
internal fun folderAllHidden(
    rails: List<KBRail>,
    selectedSourceId: String?,
    visible: List<KBRail>
): Boolean =
    visible.isEmpty() &&
        folderSelectedRails(rails, selectedSourceId).any { it.items.isNotEmpty() }

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
    viewModel: KBFolderViewModel,
    focusedItem: KBContentItem?,
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

    // Both hero-trailer controls are SYNCED prefs, so the hero reads them
    // through the sync revision rather than bare: a mute (or autoplay) flipped
    // on the other TV has to reach a folder that is already open, not wait for
    // the next visit. Read unconditionally - a `remember` behind `&&` is a
    // CONDITIONAL call, and Compose's slot table is positional, so the skipped
    // call would shift every slot after it (see [PrefsPayloadApplier.revision]).
    val syncedRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val trailerAutoPlay = remember(syncedRevision) {
        AppPreferences.getHeroTrailerAutoplay(context)
    }
    val trailerMuted = remember(syncedRevision) {
        AppPreferences.getHeroTrailerMuted(context)
    }
    val autoPlay = heroTrailerReady && trailerAutoPlay

    HomeHeroArtwork(
        preview = preview,
        meta = heroMeta,
        tmdbDetail = heroTmdbDetail,
        heroBackdropUrl = heroBackdropUrl,
        heroLogoUrl = heroLogoUrl,
        trailerKey = heroTrailerKey,
        autoPlayTrailer = autoPlay,
        muted = trailerMuted,
        heroHeight = heroComputedHeight
    )
}

/**
 * Home's InfiniteRailPageHandler pattern applied to a folder rail: when the
 * last visible card comes within 6 items of the end, ask the ViewModel for
 * the next TMDB page. [KBFolderViewModel.loadMoreRail] self-guards on
 * hasMore/isLoadingMore, so repeated emissions are no-ops.
 */
@Composable
private fun FolderRailPageHandler(
    rail: KBRail,
    // Where this rail's row sits in the page's own list: part of the record a
    // card press raises, so a return can put the rail back on screen (see
    // [BrowseReturnFocus]).
    railIndex: Int,
    // Whether this is the first rail drawn on the page - its first card is where
    // an ordinary open lands the D-pad.
    isFirstRail: Boolean,
    railContext: RailCardContext,
    viewModel: KBFolderViewModel,
    onFocusItem: (KBContentItem) -> Unit
) {
    val listState = rememberLazyListState()

    // Where a return out of Detail lands in this row: the card the record
    // names, by its own key, or the position it was pressed at for a card the
    // record cannot name. A row the viewer had scrolled twenty cards into has
    // not composed that card, so it has no focus node until this scroll puts
    // one there.
    val returnIndex = railContext.returnTarget
        ?.takeIf { it.railKey == "rail:${rail.sourceId}" }
        ?.let { target ->
            rail.items
                .indexOfFirst { kbFolderCardKey(it) == target.itemId }
                .takeIf { it >= 0 }
                ?: target.position.takeIf { it in rail.items.indices }
        }
    LaunchedEffect(returnIndex) {
        returnIndex?.let { index ->
            runCatching { listState.scrollToItem(index) }
        }
    }
    LazyRow(
        state = listState,
        // Vertical room for the focus glow, inside the row's own clips-to-
        // bounds viewport (see [KBFocusRowInset]): 4dp at the top sliced the
        // halo off the first tile of every rail flat.
        contentPadding = PaddingValues(
            start = FolderSafeHorizontal,
            end = FolderSafeHorizontal,
            top = KBFocusRowInset,
            bottom = KBFocusRowInset
        )
    ) {
        itemsIndexed(
            items = rail.items,
            key = { _, item -> "${rail.sourceId}:${item.type}:${item.id}" }
        ) { itemIndex, item ->
            val cardKey = kbFolderCardKey(item)
            // Where a press on this card comes back to, raised by the screen
            // before it navigates - and by the menu's own "Go to Details" too
            // (see [BrowseReturnFocus]).
            val target = BrowseReturnTarget(
                screenKey = railContext.browseReturnKey,
                itemId = cardKey,
                position = itemIndex,
                railKey = "rail:${rail.sourceId}",
                railIndex = railIndex
            )
            FolderItemCard(
                item = item,
                isWatched = railContext.watchedLookup(item),
                isPartiallyWatched = railContext.partiallyWatchedLookup(item),
                showLandscapeCards = railContext.showLandscapeCards,
                // Home rails carry no captions; a "replica" folder follows
                // suit.
                showCaptions = false,
                art = railContext.landscapeArt[
                    landscapeArtKey(item.type, item.id)
                ],
                onClick = { railContext.onOpenItem(item, target) },
                onLongClick = {
                    railContext.onLongPressItem(
                        item,
                        successorIndexAfter(rail.items.size, itemIndex)
                            ?.let { kbFolderCardKey(rail.items[it]) },
                        target
                    )
                },
                onFocus = { onFocusItem(item) },
                cardKey = cardKey,
                focusRegistry = railContext.focusRegistry,
                isReturnItem = itemIndex == returnIndex,
                returnFocusRequester = railContext.returnFocusRequester,
                isEntryItem = isFirstRail && itemIndex == 0,
                entryFocusRequester = railContext.entryFocusRequester
            )
        }
    }

    // The paging state the sentinel's own throttling depends on. Left at its
    // defaults (hasMore = true, isLoadingMore = false), the runway fired
    // onLoadMore on EVERY scroll delta - including after the ViewModel had
    // marked the rail exhausted, where the only thing absorbing the calls was
    // the VM's own guard. Tag/Studio/Decade pass both through their
    // Infinite*RailHandler wrappers; this is the same call.
    val paging = viewModel.state.collectAsStateWithLifecycle().value
        .pagingStates[rail.sourceId]
    InfiniteScrollEffect(
        listState = listState,
        itemCount = rail.items.size,
        hasMore = paging?.hasMore ?: true,
        isLoadingMore = paging?.isLoadingMore == true,
        onLoadMore = { viewModel.loadMoreRail(rail.sourceId) }
    )
}

/** Everything a rail's cards need, bundled so FOLLOW_LAYOUT and ROWS share. */
private data class RailCardContext(
    val showLandscapeCards: Boolean,
    val landscapeArt: Map<String, HeroArtwork>,
    val watchedLookup: (KBContentItem) -> Boolean,
    val partiallyWatchedLookup: (KBContentItem) -> Boolean,
    // The target is built by the rail, which is the only place that knows the
    // card's position in it and the rail's OWN place on the page; the press
    // records it before navigating (see [BrowseReturnFocus]).
    val onOpenItem: (KBContentItem, BrowseReturnTarget) -> Unit,
    // Carries the key of the card that takes this one's place when a Hide
    // takes it off the rail - worked out before the removal happens, because
    // afterwards the pressed card's node is gone - plus the record the menu's
    // own "Go to Details" raises.
    val onLongPressItem: (KBContentItem, String?, BrowseReturnTarget) -> Unit,
    val onFocus: (KBContentItem) -> Unit = {},
    // This page's return key, the screen's card registry, and the card a return
    // from Detail is coming back for (see [BrowseReturnFocus]).
    val browseReturnKey: String,
    val focusRegistry: MutableMap<String, FocusRequester>,
    val returnTarget: BrowseReturnTarget?,
    val returnFocusRequester: FocusRequester,
    // The page's own first card, where an ordinary open puts the D-pad.
    val entryFocusRequester: FocusRequester
)

// ---------------------------------------------------------------------------
// FOLLOW_LAYOUT — hero + rails, pixel-matched to Home
// ---------------------------------------------------------------------------

@Composable
private fun FollowHomeLayout(
    viewModel: KBFolderViewModel,
    state: KBFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    // The page's own list state, its return key, the card registry a long-press
    // menu hands the D-pad back through, and the card a return out of Detail is
    // coming back for - all owned by the SCREEN, because a Back out of Detail
    // disposes everything below this point (see [BrowseReturnFocus]).
    browseReturnKey: String,
    listState: LazyListState,
    focusRegistry: MutableMap<String, FocusRequester>,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onOpenItem: (KBContentItem, BrowseReturnTarget) -> Unit,
    // Carries the key of the card that takes the pressed one's place when a
    // Hide removes it (null when it was the rail's last card), plus the record
    // the menu's own "Go to Details" raises.
    onLongPressItem: (KBContentItem, String?, BrowseReturnTarget) -> Unit,
    // Re-runs this folder's load, for the error card's own RETRY (see
    // [FolderStatus]). Threaded from the screen because the grid layout has no
    // ViewModel of its own to ask.
    onRetryFolder: () -> Unit,
    // Where an ordinary open lands the D-pad: this page's own first card. Every
    // sibling browse page pins one (see TagScreen.focusFirstCard) - without it
    // the folder's hero and its source chips are as reachable by the framework's
    // own traversal as the first poster is.
    entryFocusRequester: FocusRequester
) {
    val context = LocalContext.current
    // Both of these are SYNCED prefs, so the read is keyed on the sync revision:
    // a change that arrives from the account reaches an open folder instead of
    // waiting for it to be left and re-entered (see PrefsPayloadApplier.revision).
    val syncRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val showLandscapeCards = remember(syncRevision) {
        AppPreferences.homeLandscapeActive(context)
    }
    val showRailType = remember(syncRevision) {
        AppPreferences.getHomeRailShowCatalogType(context)
    }

    val landscapeArt by viewModel.landscapeArt.collectAsStateWithLifecycle()

    val railCardContext = RailCardContext(
        showLandscapeCards = showLandscapeCards,
        landscapeArt = landscapeArt,
        watchedLookup = { item -> itemWatched(item, watchedKeys, resolvedIds) },
        partiallyWatchedLookup = { item ->
            itemWatchedPartially(item, partialWatchedKeys, resolvedIds)
        },
        onOpenItem = onOpenItem,
        onLongPressItem = onLongPressItem,
        browseReturnKey = browseReturnKey,
        focusRegistry = focusRegistry,
        returnTarget = returnTarget,
        returnFocusRequester = returnFocusRequester,
        entryFocusRequester = entryFocusRequester
    )

    // Keyed on the folder id so navigating folder A -> folder B reseeds the
    // hero instead of keeping A's focused item.
    var heroItem by remember(state.folder?.id) {
        mutableStateOf<KBContentItem?>(null)
    }
    var heroTrailerReady by remember { mutableStateOf(false) }

    // Landscape-card artwork resolves in the background while the global
    // toggle is on (and re-runs as infinite-scroll pages append; the VM only
    // fetches items it hasn't resolved yet).
    LaunchedEffect(showLandscapeCards, state.rails) {
        viewModel.ensureLandscapeArt(showLandscapeCards)
    }

    // Seed the hero with the first rail's first item (KB behavior). Keyed
    // only on the rails list so a page append seeds nothing new — the focus
    // effect below owns re-resolution, and re-keying on rails would restart
    // the trailer dwell mid-watch every time infinite-scroll fires.
    LaunchedEffect(state.rails) {
        if (heroItem == null) {
            heroItem = state.rails
                .firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
        }
    }

    // Home's exact focus pipeline: resolve on stop, the viewer's dwell, then
    // the trailer. Same setting for both heroes (Settings → Hero Trailer
    // Delay), so the two cannot drift apart.
    //
    // The dwell is the same synced pref as the hero's autoplay, so it is read
    // through the revision the layout already collects rather than bare. It is
    // deliberately NOT an effect key: the revision ticks for every change to the
    // profile, so keying on it would restart the dwell - and cancel a trailer
    // mid-play - because a poster size changed on another TV. A new dwell value
    // applies to the next dwell, which is what the row promises.
    val heroTrailerDelayMs = remember(syncRevision) {
        AppPreferences.getHeroTrailerDelayMs(context)
    }
    LaunchedEffect(heroItem?.id, heroItem?.type) {
        val target = heroItem ?: return@LaunchedEffect
        heroTrailerReady = false
        viewModel.resolveHero(target)
        delay(heroTrailerDelayMs)
        heroTrailerReady = true
    }

    // Hidden titles are dropped as the rails are read, so the long-press Hide
    // takes the tile it was pressed on off the rail (see [visibleFolderRails]).
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val visibleRails = remember(
        state.rails,
        selectedSourceId,
        hiddenTitleKeys,
        resolvedIds
    ) {
        val rails = if (selectedSourceId != null) {
            state.rails.filter { it.sourceId == selectedSourceId }
        } else {
            state.rails
        }
        visibleFolderRails(rails, hiddenTitleKeys, resolvedIds)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FolderHero(
            viewModel = viewModel,
            focusedItem = heroItem,
            heroTrailerReady = heroTrailerReady
        )

        SourceTabs(state, selectedSourceId, onSelectSource)

        when {
            state.isLoading ->
                KBSkeletonRailStack(
                    posterWidth = if (showLandscapeCards) FolderLandscapeWidth
                    else FolderPosterWidth,
                    posterHeight = if (showLandscapeCards) FolderLandscapeHeight
                    else FolderPosterHeight,
                    horizontalPadding = FolderSafeHorizontal,
                    shape = posterEdgeShape()
                )
            state.error != null ->
                FolderStatus(state.error.orEmpty(), onRetry = onRetryFolder)
            // Every title this page served is hidden, so there is nothing to
            // draw - but the branch below would say the folder HAS nothing,
            // which is a different fact, and it would leave the viewer with no
            // way back from here (the same gap Home's all-hidden card and the
            // browse pages' own branch close).
            folderAllHidden(state.rails, selectedSourceId, visibleRails) ->
                FolderStatus(
                    "Every title here is hidden.",
                    icon = KB_STATUS_ICON_EMPTY,
                    onRetry = { HiddenTitles.unhideAll(context) },
                    actionLabel = "UNHIDE ALL"
                )
            visibleRails.isEmpty() ->
                FolderStatus("Nothing to show here yet.", icon = KB_STATUS_ICON_EMPTY)
            else -> {
                LazyColumn(
                    // The screen's state, not one re-created here: a Back out of
                    // Detail disposes this page and a return has a state to
                    // scroll (see [BrowseReturnFocus]).
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Home's exact rail-column paddings: 2dp below the hero
                    // (its hero_spacer), 16dp at the very bottom, sections
                    // separated by the same 20dp gap.
                    contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(FolderRailSectionGap)
                ) {
                    visibleRails.forEachIndexed { railIndex, rail ->
                        item(key = "title:${rail.sourceId}") {
                            RailTitle(rail, showRailType)
                        }
                        item(key = "rail:${rail.sourceId}") {
                            // Home wraps every rail (title + row) in a Column
                            // with 8dp bottom padding; matching it keeps the
                            // inter-rail rhythm identical.
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                FolderRailPageHandler(
                                    rail = rail,
                                    // The rail item's own index on the page: title,
                                    // then rail, per rail above it.
                                    railIndex = railIndex * 2 + 1,
                                    // The page's first rail: its first card is where an
                                    // ordinary open lands the D-pad.
                                    isFirstRail = railIndex == 0,
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
}

private fun normalizeItemType(item: KBContentItem): String =
    when (item.type.lowercase()) {
        "series", "tv" -> "series"
        else -> "movie"
    }

// ---------------------------------------------------------------------------
// ROWS — plain rail list, no hero
// ---------------------------------------------------------------------------

@Composable
private fun RowsLayout(
    viewModel: KBFolderViewModel,
    state: KBFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    // The page's own list state, its return key, the card registry a long-press
    // menu hands the D-pad back through, and the card a return out of Detail is
    // coming back for - all owned by the SCREEN, because a Back out of Detail
    // disposes everything below this point (see [BrowseReturnFocus]).
    browseReturnKey: String,
    listState: LazyListState,
    focusRegistry: MutableMap<String, FocusRequester>,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onOpenItem: (KBContentItem, BrowseReturnTarget) -> Unit,
    // Carries the key of the card that takes the pressed one's place when a
    // Hide removes it (null when it was the rail's last card), plus the record
    // the menu's own "Go to Details" raises.
    onLongPressItem: (KBContentItem, String?, BrowseReturnTarget) -> Unit,
    // Re-runs this folder's load, for the error card's own RETRY (see
    // [FolderStatus]). Threaded from the screen because the grid layout has no
    // ViewModel of its own to ask.
    onRetryFolder: () -> Unit,
    // Where an ordinary open lands the D-pad: this page's own first card. Every
    // sibling browse page pins one (see TagScreen.focusFirstCard) - without it
    // the folder's hero and its source chips are as reachable by the framework's
    // own traversal as the first poster is.
    entryFocusRequester: FocusRequester
) {
    val context = LocalContext.current
    // Both of these are SYNCED prefs, so the read is keyed on the sync revision:
    // a change that arrives from the account reaches an open folder instead of
    // waiting for it to be left and re-entered (see PrefsPayloadApplier.revision).
    val syncRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val showLandscapeCards = remember(syncRevision) {
        AppPreferences.homeLandscapeActive(context)
    }
    val showRailType = remember(syncRevision) {
        AppPreferences.getHomeRailShowCatalogType(context)
    }

    // Landscape art (alternate backdrop + clearlogo) resolves exactly like
    // FOLLOW_LAYOUT while the toggle is on.
    val landscapeArt by viewModel.landscapeArt.collectAsStateWithLifecycle()
    LaunchedEffect(showLandscapeCards, state.rails) {
        viewModel.ensureLandscapeArt(showLandscapeCards)
    }

    // Hidden titles are dropped as the rails are read, so the long-press Hide
    // takes the tile it was pressed on off the rail (see [visibleFolderRails]).
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val visibleRails = remember(
        state.rails,
        selectedSourceId,
        hiddenTitleKeys,
        resolvedIds
    ) {
        val rails = if (selectedSourceId != null) {
            state.rails.filter { it.sourceId == selectedSourceId }
        } else {
            state.rails
        }
        visibleFolderRails(rails, hiddenTitleKeys, resolvedIds)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FolderTitleBlock(state.folder)
        SourceTabs(state, selectedSourceId, onSelectSource)

        when {
            state.isLoading ->
                KBSkeletonRailStack(
                    posterWidth = if (showLandscapeCards) FolderLandscapeWidth
                    else FolderPosterWidth,
                    posterHeight = if (showLandscapeCards) FolderLandscapeHeight
                    else FolderPosterHeight,
                    horizontalPadding = FolderSafeHorizontal,
                    shape = posterEdgeShape()
                )
            state.error != null ->
                FolderStatus(state.error.orEmpty(), onRetry = onRetryFolder)
            // Every title this page served is hidden, so there is nothing to
            // draw - but the branch below would say the folder HAS nothing,
            // which is a different fact, and it would leave the viewer with no
            // way back from here (the same gap Home's all-hidden card and the
            // browse pages' own branch close).
            folderAllHidden(state.rails, selectedSourceId, visibleRails) ->
                FolderStatus(
                    "Every title here is hidden.",
                    icon = KB_STATUS_ICON_EMPTY,
                    onRetry = { HiddenTitles.unhideAll(context) },
                    actionLabel = "UNHIDE ALL"
                )
            visibleRails.isEmpty() ->
                FolderStatus("Nothing to show here yet.", icon = KB_STATUS_ICON_EMPTY)
            else -> {
                LazyColumn(
                    // The screen's state: a Back out of Detail disposes this page
                    // and a return scrolls this list (see [BrowseReturnFocus]).
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    visibleRails.forEachIndexed { railIndex, rail ->
                        item(key = "title:${rail.sourceId}") {
                            RailTitle(rail, showRailType)
                        }
                        item(key = "rail:${rail.sourceId}") {
                            // The row's own state, owned here rather than inside
                            // the LazyRow: a return out of Detail has to scroll it
                            // onto the pressed card (the same move
                            // [FolderRailPageHandler] makes for the hero layout's
                            // paged rails).
                            val rowState = rememberLazyListState()
                            val returnIndex = returnTarget
                                ?.takeIf { it.railKey == "rail:${rail.sourceId}" }
                                ?.let { target ->
                                    rail.items
                                        .indexOfFirst {
                                            kbFolderCardKey(it) == target.itemId
                                        }
                                        .takeIf { it >= 0 }
                                        ?: target.position.takeIf {
                                            it in rail.items.indices
                                        }
                                }
                            LaunchedEffect(returnIndex) {
                                returnIndex?.let { index ->
                                    runCatching { rowState.scrollToItem(index) }
                                }
                            }
                            LazyRow(
                                state = rowState,
                                // Same vertical room as the paged rail above:
                                // a row that clips to its own bounds cut the
                                // focused tile's glow off flat (see
                                // [KBFocusRowInset]).
                                contentPadding = PaddingValues(
                                    start = FolderSafeHorizontal,
                                    end = FolderSafeHorizontal,
                                    top = KBFocusRowInset,
                                    bottom = KBFocusRowInset
                                )
                            ) {
                                itemsIndexed(
                                    items = rail.items,
                                    key = { _, item ->
                                        "${rail.sourceId}:${item.type}:${item.id}"
                                    }
                                ) { itemIndex, item ->
                                    val cardKey = kbFolderCardKey(item)
                                    val target = BrowseReturnTarget(
                                        screenKey = browseReturnKey,
                                        itemId = cardKey,
                                        position = itemIndex,
                                        railKey = "rail:${rail.sourceId}",
                                        railIndex = railIndex * 2 + 1
                                    )
                                    FolderItemCard(
                                        item = item,
                                        isWatched = itemWatched(item, watchedKeys, resolvedIds),
                                        isPartiallyWatched = itemWatchedPartially(
                                            item,
                                            partialWatchedKeys,
                                            resolvedIds
                                        ),
                                        showLandscapeCards = showLandscapeCards,
                                        showCaptions = true,
                                        art = landscapeArt[
                                            landscapeArtKey(item.type, item.id)
                                        ],
                                        onClick = { onOpenItem(item, target) },
                                        onLongClick = {
                                            onLongPressItem(
                                                item,
                                                successorIndexAfter(
                                                    rail.items.size,
                                                    itemIndex
                                                )?.let { kbFolderCardKey(rail.items[it]) },
                                                target
                                            )
                                        },
                                        cardKey = cardKey,
                                        focusRegistry = focusRegistry,
                                        isReturnItem = itemIndex == returnIndex,
                                        returnFocusRequester = returnFocusRequester,
                                        isEntryItem = railIndex == 0 && itemIndex == 0,
                                        entryFocusRequester = entryFocusRequester
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
    state: KBFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    // Same four the rails take: the page's return key, the grid state a return
    // scrolls, the card registry for a long-press hand-back, and the card a
    // return is coming back for (see [BrowseReturnFocus]).
    browseReturnKey: String,
    gridState: LazyGridState,
    focusRegistry: MutableMap<String, FocusRequester>,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onOpenItem: (KBContentItem, BrowseReturnTarget) -> Unit,
    // Carries the key of the card that takes the pressed one's place when a
    // Hide removes it (null when it was the grid's last poster), plus the
    // record the menu's own "Go to Details" raises.
    onLongPressItem: (KBContentItem, String?, BrowseReturnTarget) -> Unit,
    // Re-runs this folder's load, for the error card's own RETRY (see
    // [FolderStatus]). Threaded from the screen because the grid layout has no
    // ViewModel of its own to ask.
    onRetryFolder: () -> Unit,
    // Where an ordinary open lands the D-pad: this page's own first card. Every
    // sibling browse page pins one (see TagScreen.focusFirstCard) - without it
    // the folder's hero and its source chips are as reachable by the framework's
    // own traversal as the first poster is.
    entryFocusRequester: FocusRequester
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        FolderTitleBlock(state.folder)
        SourceTabs(state, selectedSourceId, onSelectSource)

        // Hidden titles are dropped as the grid is read (see
        // [visibleFolderRails]), so the long-press Hide takes the poster it was
        // pressed on off the grid.
        val hiddenTitleKeys = rememberHiddenTitleKeys()
        // The VISIBLE rails first, and their flattened items from that: the
        // all-hidden branch below needs the rails themselves (a selection whose
        // every title is hidden flattens to an empty list exactly like a folder
        // with nothing in it), and the grid needs the items.
        val gridRails = remember(
            state.rails,
            selectedSourceId,
            hiddenTitleKeys,
            resolvedIds
        ) {
            val rails = if (selectedSourceId != null) {
                state.rails.filter { it.sourceId == selectedSourceId }
            } else {
                state.rails
            }
            visibleFolderRails(rails, hiddenTitleKeys, resolvedIds)
        }
        val gridItems = remember(gridRails) {
            val seen = mutableSetOf<String>()
            gridRails.flatMap { it.items }.filter { seen.add(kbFolderCardKey(it)) }
        }

        // The poster/landscape choice is a SYNCED pref, so the grid's read goes
        // through the sync revision: a "Landscape Posters" toggle flipped on the
        // other TV has to reshape the posters already on screen. Every other
        // folder layout gates on the revision; this grid read it bare
        // (see [PrefsPayloadApplier.revision]).
        val syncedRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
            .revision.collectAsStateWithLifecycle()
        val gridLandscape = remember(syncedRevision) {
            AppPreferences.getLandscapePosters(context)
        }

        // Puts the grid ON the card a return out of Detail is coming back for:
        // the card by its own key, or its recorded position for one this grid
        // cannot name. A poster the scroll left behind has not composed at all,
        // so it has no focus node until this runs; the focus itself belongs to
        // the screen, which retries while this scroll settles.
        //
        // ONE index for both halves of the return, the way the rails above do
        // it (see [FolderRailPageHandler]'s returnIndex): the card the record
        // NAMES, and only for a card the record cannot name the position it was
        // pressed at. The scroll below and the cell that publishes the
        // return-focus requester used to compute this separately - the scroll
        // key-first, the cell an AND of position and key - so a grid that
        // re-ordered while the viewer was in Detail scrolled to the right card
        // and then focused NOTHING: the requester is attached by the cell this
        // index names, and the cell the AND named was a different one (or none).
        val gridReturnIndex = returnTarget?.let { target ->
            gridItems
                .indexOfFirst { kbFolderCardKey(it) == target.itemId }
                .takeIf { it >= 0 }
                ?: target.position.takeIf { it in gridItems.indices }
        }
        LaunchedEffect(gridReturnIndex, gridItems) {
            gridReturnIndex?.let { index ->
                runCatching { gridState.scrollToItem(index) }
            }
        }

        when {
            state.isLoading ->
                KBSkeletonGrid(
                    cellWidth = FolderPosterWidth,
                    cellHeight = FolderPosterHeight,
                    columns = 6,
                    horizontalPadding = FolderSafeHorizontal,
                    shape = posterEdgeShape()
                )
            state.error != null ->
                FolderStatus(state.error.orEmpty(), onRetry = onRetryFolder)
            // Same fact as the rails' own all-hidden branch above: the grid
            // would otherwise report the folder as empty when the viewer hid
            // what it holds (see [folderAllHidden]).
            folderAllHidden(state.rails, selectedSourceId, gridRails) ->
                FolderStatus(
                    "Every title here is hidden.",
                    icon = KB_STATUS_ICON_EMPTY,
                    onRetry = { HiddenTitles.unhideAll(context) },
                    actionLabel = "UNHIDE ALL"
                )
            gridItems.isEmpty() ->
                FolderStatus("Nothing to show here yet.", icon = KB_STATUS_ICON_EMPTY)
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    // The screen's state, not one re-created here: a Back out of
                    // Detail disposes this grid and a return scrolls it (see
                    // [BrowseReturnFocus]).
                    state = gridState,
                    // 16dp at the top, matching the Library's ItemGrid: the
                    // glow of a focused card in the FIRST row had 8dp of room
                    // and lost its top edge (see [KBFocusRowInset]).
                    contentPadding = PaddingValues(
                        start = FolderSafeHorizontal,
                        end = FolderSafeHorizontal,
                        top = KBFocusRowInset,
                        bottom = 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = gridItems,
                        key = { _, item -> "grid:${item.type}:${item.id}" }
                    ) { itemIndex, item ->
                        val cardKey = kbFolderCardKey(item)
                        // This cell's own node, published so the screen's
                        // long-press menu can ask for it by key - including the
                        // successor's, which is the one a Hide lands on.
                        val cardRequester = remember(cardKey) { FocusRequester() }
                        focusRegistry[cardKey] = cardRequester
                        val isReturnItem = itemIndex == gridReturnIndex
                        // The page's own first poster: where an ordinary open
                        // lands the D-pad.
                        val isEntryItem = itemIndex == 0
                        // The grid has no rail, so its record names the card and
                        // its place and nothing else (see [BrowseReturnFocus]).
                        val target = BrowseReturnTarget(
                            screenKey = browseReturnKey,
                            itemId = cardKey,
                            position = itemIndex
                        )
                        Column {
                            GlobalPosterCard(
                                posterUrl = item.posterUrl,
                                backdropUrl = item.backdropUrl,
                                contentDescription = item.title,
                                isWatched = itemWatched(item, watchedKeys, resolvedIds),
                                isPartiallyWatched = itemWatchedPartially(
                                    item,
                                    partialWatchedKeys,
                                    resolvedIds
                                ),
                                onClick = { onOpenItem(item, target) },
                                onLongClick = {
                                    onLongPressItem(
                                        item,
                                        successorIndexAfter(gridItems.size, itemIndex)
                                            ?.let { kbFolderCardKey(gridItems[it]) },
                                        target
                                    )
                                },
                                posterWidth = FolderPosterWidth,
                                posterHeight = FolderPosterHeight,
                                // A folder item ships a backdrop but no logo;
                                // the resolver adds the corner clearlogo.
                                artId = item.id,
                                artType = item.type,
                                // Only the global setting reshapes the Grid;
                                // the Home-rails toggle keeps it posters.
                                landscape = gridLandscape,
                                modifier = Modifier
                                    .focusRequester(cardRequester)
                                    .then(
                                        when {
                                            isReturnItem ->
                                                Modifier.focusRequester(returnFocusRequester)

                                            isEntryItem ->
                                                Modifier.focusRequester(entryFocusRequester)

                                            else -> Modifier
                                        }
                                    )
                            )
                            // Grid always keeps regular posters, but its
                            // item captions honor the toggles like Rows.
                            PosterCaptions(
                                title = item.title,
                                year = item.year,
                                rating = item.rating,
                                // Same 5dp as the rails' captions above, for
                                // the same reason.
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Internal dispatch used by KBFolderScreen. */
@Composable
internal fun KBFolderBody(
    layoutMode: KBLayoutModes.Mode,
    viewModel: KBFolderViewModel,
    state: KBFolderViewModel.UiState,
    resolvedIds: Map<String, String>,
    selectedSourceId: String?,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    onSelectSource: (String?) -> Unit,
    // Where a return out of Detail lands, per layout: the page's list or grid
    // state (whichever this mode draws), its return key, the card registry a
    // long-press hands the D-pad back through, and the card the return is
    // coming back for (see [BrowseReturnFocus]).
    browseReturnKey: String,
    listState: LazyListState,
    gridState: LazyGridState,
    focusRegistry: MutableMap<String, FocusRequester>,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    onOpenItem: (KBContentItem, BrowseReturnTarget) -> Unit,
    onLongPressItem: (KBContentItem, String?, BrowseReturnTarget) -> Unit,
    // Re-runs this folder's load, for the error card's own RETRY (see
    // [FolderStatus]). Threaded from the screen because the grid layout has no
    // ViewModel of its own to ask.
    onRetryFolder: () -> Unit,
    // Where an ordinary open lands the D-pad: this page's own first card. Every
    // sibling browse page pins one (see TagScreen.focusFirstCard) - without it
    // the folder's hero and its source chips are as reachable by the framework's
    // own traversal as the first poster is.
    entryFocusRequester: FocusRequester
) {
    when (layoutMode) {
        KBLayoutModes.Mode.FOLLOW_LAYOUT ->
            FollowHomeLayout(
                viewModel, state, resolvedIds, selectedSourceId, watchedKeys,
                partialWatchedKeys, onSelectSource, browseReturnKey, listState,
                focusRegistry, returnTarget, returnFocusRequester, onOpenItem,
                onLongPressItem, onRetryFolder, entryFocusRequester
            )

        KBLayoutModes.Mode.ROWS ->
            RowsLayout(
                viewModel, state, resolvedIds, selectedSourceId, watchedKeys,
                partialWatchedKeys, onSelectSource, browseReturnKey, listState,
                focusRegistry, returnTarget, returnFocusRequester, onOpenItem,
                onLongPressItem, onRetryFolder, entryFocusRequester
            )

        KBLayoutModes.Mode.GRID ->
            GridLayout(
                state, resolvedIds, selectedSourceId, watchedKeys,
                partialWatchedKeys, onSelectSource, browseReturnKey, gridState,
                focusRegistry, returnTarget, returnFocusRequester, onOpenItem,
                onLongPressItem, onRetryFolder, entryFocusRequester
            )
    }
}
