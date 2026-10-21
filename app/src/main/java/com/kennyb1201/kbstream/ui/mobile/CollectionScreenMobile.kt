package com.kennyb1201.kbstream.ui.mobile

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.collectionBrowseScreenKey
import com.kennyb1201.kbstream.ui.collection.CollectionViewModel
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscape
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.MobileLoading
import com.kennyb1201.kbstream.ui.components.touch.MobileStatus
import com.kennyb1201.kbstream.ui.components.touch.PosterCard
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * A collection (franchise), for a finger.
 *
 * Same [CollectionViewModel], same release-ordered parts, same hidden-title
 * filter, same watched arithmetic (the resolved IMDB key first, the TMDB key
 * second) and the same two-item context menu. The TV page is a fixed header
 * over one horizontal rail because a collection is two to five films and fills
 * one screen; the phone keeps the header and lays the parts out as a grid,
 * which is what a thumb can scroll through and tap.
 *
 * Taps record the same [BrowseReturnTarget] the TV rail records, so backing out
 * of a film's Detail page reopens the collection scrolled to that film. The
 * record is honoured by scrolling, not by focus: a phone has no focus ring to
 * put back on the poster.
 *
 * The header's backdrop goes through the same override chain as the TV
 * header - this screen's own still, then the all-screens still, then TMDB's -
 * so an image chosen for the collection is the one drawn on both form factors.
 */
@Composable
fun CollectionScreenMobile(
    collectionId: Int,
    collectionName: String,
    onBack: () -> Unit,
    onNavigateDetail: (type: String, id: String) -> Unit,
    viewModel: CollectionViewModel = viewModel()
) {
    val collectionRaw by viewModel.collection.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val overridePaths = rememberImageOverridePaths()

    val collection = remember(collectionRaw, resolvedIds, hiddenTitleKeys) {
        collectionRaw?.let { detail ->
            detail.copy(
                parts = detail.parts.filterNot { part ->
                    HiddenTitles.hides(
                        hiddenTitleKeys,
                        "movie",
                        part.title ?: part.name,
                        part.releaseDate?.take(4)?.toIntOrNull(),
                        part.id.toString(),
                        resolvedIds[viewModel.lookupKey(part.id, "movie")]
                    )
                }
            )
        }
    }

    // Release order, resolved once: the grid paints in this order and the card
    // a return is coming back for is a position in THIS list.
    val parts = remember(collection) {
        collection?.parts?.sortedBy { it.releaseDate ?: "9999-99-99" }.orEmpty()
    }

    val screenKey = remember(collectionId) { collectionBrowseScreenKey(collectionId) }
    // Saveable, so turning the phone keeps the viewer's place in the
    // collection instead of throwing the grid back to the first film.
    val gridState = rememberLazyGridState()
    var returnTarget by remember(collectionId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    var menuPart by remember { mutableStateOf<TmdbCollectionPart?>(null) }

    LaunchedEffect(collectionId) {
        viewModel.load(collectionId)
        returnTarget = BrowseReturnFocus.consume(screenKey)
    }

    LaunchedEffect(returnTarget, parts) {
        val target = returnTarget ?: return@LaunchedEffect
        val index = parts.indexOfFirst { "collection_part:${it.id}" == target.itemId }
            .takeIf { it >= 0 }
            ?: target.position.takeIf { it in parts.indices }
            ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        returnTarget = null
    }

    fun isWatched(part: TmdbCollectionPart): Boolean {
        val imdbKey = resolvedIds[viewModel.lookupKey(part.id, "movie")]
            ?.let { viewModel.watchedKey(it, "movie") }
        return (imdbKey != null && imdbKey in watchedKeys) ||
            viewModel.watchedKey("tmdb:${part.id}", "movie") in watchedKeys
    }

    fun isPartiallyWatched(part: TmdbCollectionPart): Boolean {
        if (isWatched(part)) return false
        val imdbKey = resolvedIds[viewModel.lookupKey(part.id, "movie")]
            ?.let { viewModel.watchedKey(it, "movie") }
        return (imdbKey != null && imdbKey in partialWatchedKeys) ||
            viewModel.watchedKey("tmdb:${part.id}", "movie") in partialWatchedKeys
    }

    MobileTheme {
        // Landscape cards are wider than posters, so the columns widen with
        // them - the same 168dp the catalog grid uses.
        val landscape = rememberGlobalLandscape()
        val posterSize = rememberTouchPosterSize()
        val tileWidth =
            if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
        // Three poster columns in portrait, more when the phone is turned.
        val columns = rememberMobileGridColumns(
            preferredTileWidth = tileWidth,
            landscape = landscape
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 28.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        // Top-left of the header, above the card: the card's
                        // first cell is the poster, so an overlaid arrow would
                        // cover it.
                        MobileBackButton(onBack = onBack)
                        CollectionHeaderMobile(
                            name = collection?.name ?: collectionName,
                            overview = collection?.overview,
                            posterUrl = imageOverridePosterUrl(
                                paths = overridePaths,
                                rawId = collectionId.toString(),
                                fallbackUrl = collection?.posterPath
                                    ?.let { "${TmdbRepository.POSTER_BASE}$it" }
                            ),
                            backdropUrl = imageOverrideBackdropUrl(
                                paths = overridePaths,
                                tmdbId = collectionId,
                                screen = ImageOverrides.SCREEN_ALL,
                                fallbackUrl = collection?.backdropPath
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { TmdbRepository.fullResBackdropUrl(it) }
                            ),
                            partCount = collection?.parts?.size
                        )
                    }
                }

                when {
                    isLoading && collection == null -> item(
                        key = "loading",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MobileLoading()
                    }

                    collection == null -> item(
                        key = "error",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MobileStatus(
                            message = "We couldn't load this collection right now.",
                            actionLabel = "TRY AGAIN",
                            onAction = { viewModel.load(collectionId) }
                        )
                    }

                    parts.isEmpty() -> item(
                        key = "empty",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        MobileStatus("This collection lists no movies yet.")
                    }

                    else -> itemsIndexed(
                        items = parts,
                        key = { _, part -> "collection_part:${part.id}" }
                    ) { index, part ->
                        CollectionPartTile(
                            part = part,
                            isWatched = isWatched(part),
                            isPartiallyWatched = isPartiallyWatched(part),
                            onClick = {
                                BrowseReturnFocus.record(
                                    BrowseReturnTarget(
                                        screenKey = screenKey,
                                        itemId = "collection_part:${part.id}",
                                        position = index
                                    )
                                )
                                onNavigateDetail("movie", part.id.toString())
                            },
                            onLongClick = { menuPart = part }
                        )
                    }
                }
            }

            menuPart?.let { part ->
                val watched = isWatched(part)
                PosterContextMenu(
                    title = part.title ?: part.name ?: "",
                    subtitle = part.releaseDate?.take(4),
                    libraryTarget = LibraryAddTarget(
                        mediaType = "movie",
                        imdbId = resolvedIds[viewModel.lookupKey(part.id, "movie")],
                        tmdbId = part.id,
                        title = part.title ?: part.name ?: "Untitled",
                        year = part.releaseDate?.take(4)?.toIntOrNull(),
                        posterUrl = part.posterPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.POSTER_BASE + it }
                    ),
                    hideTarget = hideTarget(
                        part.title ?: part.name ?: "",
                        "movie",
                        part.posterPath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                        listOf(
                            part.id.toString(),
                            resolvedIds[viewModel.lookupKey(part.id, "movie")]
                        ),
                        year = part.releaseDate?.take(4)?.toIntOrNull()
                    ),
                    actions = listOf(
                        PosterContextAction(
                            label = "Go to Details",
                            description = "Open this movie's detail page"
                        ) {
                            menuPart = null
                            // Raise the record a card press would - this opens
                            // the same Detail page.
                            BrowseReturnFocus.record(
                                BrowseReturnTarget(
                                    screenKey = screenKey,
                                    itemId = "collection_part:${part.id}",
                                    position = parts.indexOfFirst { it.id == part.id }
                                        .takeIf { it >= 0 } ?: 0
                                )
                            )
                            onNavigateDetail("movie", part.id.toString())
                        },
                        PosterContextAction(
                            label = if (watched) "Mark as Unwatched" else "Mark as Watched",
                            description = if (watched) {
                                "Clear watched status on this device and Simkl"
                            } else {
                                "Show this movie as watched"
                            }
                        ) {
                            menuPart = null
                            if (watched) {
                                viewModel.markUnwatched(part.id)
                            } else {
                                viewModel.markAsWatched(part.id)
                            }
                        }
                    ),
                    onDismiss = { menuPart = null }
                )
            }
        }
    }
}

/** The franchise header: still behind the poster, name, count and overview. */
@Composable
private fun CollectionHeaderMobile(
    name: String,
    overview: String?,
    posterUrl: String?,
    backdropUrl: String?,
    partCount: Int?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface, KBShapeCard)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                KBVoid.copy(alpha = 0.92f),
                                KBVoid.copy(alpha = 0.62f)
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterCard(
                posterUrl = posterUrl,
                contentDescription = name,
                isWatched = false,
                onClick = {},
                // Decoration, as on TV: the header poster's click was a no-op
                // there and is not a tap target here either.
                modifier = Modifier
                    .width(92.dp)
                    .height(138.dp)
            )

            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    text = name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                partCount?.let { count ->
                    Text(
                        text = if (count == 1) "1 movie" else "$count movies",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                overview
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text ->
                        Text(
                            text = text,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
            }
        }
    }
}

/** One film: poster with the shared watch badges, plus title/year/rating. */
@Composable
private fun CollectionPartTile(
    part: TmdbCollectionPart,
    isWatched: Boolean,
    isPartiallyWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // A collection PART is a real title (always a movie), so it follows the
        // global landscape switch like every other title tile.
        TouchGlobalPosterTile(
            posterUrl = part.posterPath?.let { "${TmdbRepository.POSTER_BASE}$it" },
            contentDescription = part.title ?: part.name ?: "Collection movie",
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            artId = part.id.toString(),
            artType = "movie",
            modifier = Modifier.fillMaxWidth()
        )
        // Shared caption block: a collection's parts are titles, so the
        // poster-caption toggles apply to them like to any other tile.
        TouchPosterCaptions(
            title = part.title ?: part.name ?: "Untitled",
            year = part.releaseDate?.take(4),
            rating = part.voteAverage
        )
    }
}

