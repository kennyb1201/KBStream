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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.revealBrowseReturnRail
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscape
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.GenreChipRow
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterCard
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.studio.BrandLogo
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The sectioned browse pages (a genre, a keyword, a studio/network/service and a
 * decade), for a finger.
 *
 * All four TV pages are the same page with a different header: rails stream in
 * one at a time, each rail is a horizontal row of posters with its own paging
 * state, a long press raises the poster menu, and every card press records the
 * place its Detail page returns to. Reproducing that five times would give the
 * app five touch layouts to drift apart, so the layout lives here once and the
 * three screens hand it their own ViewModel's flows (Collection is the fourth
 * page of the family and is ported next to this file, because it is one rail
 * with a fixed header rather than a page of rails).
 *
 * The shape is the TV's: a header, then one titled band per rail, and the band
 * itself scrolls sideways. It was a single vertical grid first, and that is the
 * shape this file had to leave: a grid lays a rail's cards out as ROWS, so the
 * next rail - and the next genre, the next studio - sat below a screenful of
 * this one's posters, and reaching it meant scrolling past cards already read.
 * A band is one thumb-swipe away from the rail under it, which is how the
 * television page reads.
 *
 * The poster tile, the watched/partial badges, the caption block, the
 * hidden-title filter, the context sheet and the return record are all the
 * shared ones.
 *
 * Paging is explicit on touch: a rail that has another page ends its band with a
 * LOAD MORE tile, so the way to the next page is where the rail itself ends.
 * The TV triggers it from a scroll delta on the rail's own row.
 *
 * There is deliberately no focus plumbing: the TV's return record carried a
 * rail key and a card key so a D-pad could be put back on the exact poster. On
 * touch the same record puts the page back the way the viewer left it - the
 * outer list on the rail, then that rail on the card (see the LaunchedEffect
 * below), which is the phone equivalent of putting it back under the thumb.
 */

/** One rail's paging state, as the shared page needs it. */
internal data class RailPaging(
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
) {
    val tailVisible: Boolean get() = hasMore || isLoadingMore
}

/** Everything a guided browse header needs, independent of which sheet it is. */
internal data class SectionsHeader(
    val eyebrow: String?,
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val description: String? = null,
    val logoUrls: List<String> = emptyList()
)

/** The media type the app compares watched state under. */
internal fun sectionsMediaType(mediaType: String?): String =
    when (mediaType?.trim()?.lowercase()) {
        "tv", "series" -> "series"
        else -> "movie"
    }

/** The key a rail card is identified by, the same one the TV rails use. */
internal fun cardKey(item: StudioItem): String = "${item.mediaType}:${item.item.id}"

@Composable
internal fun SectionsBrowseMobile(
    header: SectionsHeader,
    // The page's way back, drawn as the shared top-left arrow. Same callback the
    // host's system back runs (see AppRoot's BackHandler), so the browse return
    // record below behaves identically for the arrow and the gesture.
    onBack: () -> Unit,
    sectionsRaw: List<StudioSection>,
    isLoading: Boolean,
    error: String?,
    screenKey: String,
    emptyText: String,
    resolvedIds: Map<String, String>,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    lookupKey: (Int, String) -> String,
    watchedKey: (String, String) -> String,
    genres: List<TmdbGenre> = emptyList(),
    selectedGenreId: Int? = null,
    onSelectGenre: (Int?) -> Unit = {},
    paging: (String) -> RailPaging = { RailPaging() },
    onLoadMore: (String) -> Unit = {},
    onRetry: () -> Unit,
    onOpenItem: (StudioItem) -> Unit,
    onToggleWatched: (StudioItem, Boolean) -> Unit
) {
    // A hidden title drops out of every rail, exactly as on TV - including a
    // rail that then has nothing left in it.
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val sections = remember(sectionsRaw, resolvedIds, hiddenTitleKeys) {
        sectionsRaw.mapNotNull { section ->
            val visible = section.items.filterNot { railItem ->
                val mediaType = sectionsMediaType(railItem.mediaType)
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    mediaType,
                    railItem.item.title ?: railItem.item.name,
                    railItem.item.releaseDate?.take(4)?.toIntOrNull()
                        ?: railItem.item.firstAirDate?.take(4)?.toIntOrNull(),
                    railItem.item.id.toString(),
                    resolvedIds[lookupKey(railItem.item.id, mediaType)]
                )
            }
            section.takeIf { visible.isNotEmpty() }?.copy(items = visible)
        }
    }

    val overridePaths = rememberImageOverridePaths()
    val fanUrls = remember(sections, overridePaths) {
        sections.asSequence()
            .flatMap { it.items.asSequence() }
            .mapNotNull { railItem ->
                val path = railItem.item.posterPath ?: return@mapNotNull null
                imageOverridePosterUrl(
                    paths = overridePaths,
                    rawId = railItem.item.id.toString(),
                    fallbackUrl = "${TmdbRepository.POSTER_BASE}$path"
                )
            }
            .distinct()
            .take(5)
            .toList()
    }

    // The page is a vertical list of rails, so it owns two levels of scroll
    // state: the outer one that brings a RAIL back on screen, and one per rail
    // for the card inside it.
    //
    // The per-rail states live HERE, keyed by the rail title, rather than in
    // each rail's own `remember`: a band that scrolls out of view is disposed
    // with its state, so the map is what keeps a rail's sideways offset (and
    // what lets the return hand-back address a row that has not composed yet).
    val listState = rememberLazyListState()
    val railTitles = remember(sections) { sections.map { it.title } }
    // Each rail's sideways offset is saved as (index, offset) and rebuilt into
    // the same LazyListState, so turning the phone keeps every band where the
    // viewer left it. The map itself still has to live here: a band that scrolls
    // out of view is disposed with its own state, and the return hand-back
    // addresses rows that have not composed yet.
    val rowStates = rememberSaveable(
        railTitles,
        saver = mapSaver(
            save = { states ->
                states.mapValues { (_, state) ->
                    listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }
            },
            restore = { saved ->
                saved.mapValues { (_, value) ->
                    val position = value as? List<*>
                    LazyListState(
                        firstVisibleItemIndex = (position?.getOrNull(0) as? Int) ?: 0,
                        firstVisibleItemScrollOffset = (position?.getOrNull(1) as? Int) ?: 0
                    )
                }
            }
        )
    ) {
        railTitles.associateWith { LazyListState() }
    }
    var returnTarget by remember { mutableStateOf<BrowseReturnTarget?>(null) }
    var menu by remember { mutableStateOf<Pair<StudioItem, BrowseReturnTarget>?>(null) }

    LaunchedEffect(screenKey) {
        returnTarget = BrowseReturnFocus.consume(screenKey)
    }

    // Puts the page back where the Detail page was opened from, in the two steps
    // the shape needs: the outer list onto the rail the card sits in, then that
    // rail onto the card. `revealBrowseReturnRail` is the shared TV walk (hint,
    // then a step each way, because hidden titles and a paging load move rails
    // while the viewer is away).
    //
    // The rails land after the page composes, so the record is held until the
    // rail exists. Revealing it puts the band on screen (and composes it); the
    // row then scrolls to the card whether or not it has composed yet, because
    // a [LazyListState] takes an offset before its list exists.
    LaunchedEffect(returnTarget, sections) {
        val target = returnTarget ?: return@LaunchedEffect
        val railKey = target.railKey
        if (railKey == null) {
            runCatching { listState.scrollToItem(target.railIndex.coerceAtLeast(0)) }
        } else if (listState.revealBrowseReturnRail(railKey, target.railIndex)) {
            runCatching { rowStates[railKey]?.scrollToItem(target.position) }
        }
        returnTarget = null
    }

    fun record(sectionTitle: String, railIndex: Int, position: Int, item: StudioItem): BrowseReturnTarget =
        BrowseReturnTarget(
            screenKey = screenKey,
            itemId = cardKey(item),
            position = position,
            railKey = sectionTitle,
            railIndex = railIndex
        )

    // Landscape cards are wider than posters, so a rail's tile - and the caption
    // under it - widen with the card. Both come from the viewer's Poster Size:
    // the phone used to pin 112dp here, so Settings' size switch reached the
    // television and nothing else.
    val landscape = rememberGlobalLandscape()
    val posterSize = rememberTouchPosterSize()
    val tileWidth = if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header", contentType = "header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Top-left of the header, on its own line: the card below
                    // starts with the title and the poster fan, so an overlaid
                    // arrow would sit on the art.
                    MobileBackButton(onBack = onBack)
                    BrowseHeaderPanel(header = header, fanUrls = fanUrls)
                }
            }

            if (genres.isNotEmpty()) {
                item(key = "genres", contentType = "genres") {
                    GenreChipRow(
                        genres = genres,
                        selectedGenreId = selectedGenreId,
                        onSelect = onSelectGenre,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }

            when {
                isLoading && sections.isEmpty() -> {
                    item(key = "loading", contentType = "status") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            BrowseStatus(message = "Loading…")
                        }
                    }
                }

                // A failed load says so instead of reading as an empty page -
                // the TV pages fixed this and the phone must not regress it.
                error != null && sections.isEmpty() -> {
                    item(key = "error", contentType = "status") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            BrowseStatus(
                                message = "Error: $error",
                                action = "TRY AGAIN",
                                onAction = onRetry
                            )
                        }
                    }
                }

                sections.isEmpty() -> {
                    item(key = "empty", contentType = "status") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            BrowseStatus(message = emptyText)
                        }
                    }
                }

                // One band per rail, the rail's own title above it, and the
                // band scrolls sideways. The item KEY is the rail's title,
                // because that is what the return record carries
                // (`BrowseReturnTarget.railKey`) and what
                // [revealBrowseReturnRail] looks for.
                else -> itemsIndexed(
                    items = sections,
                    key = { _, section -> section.title },
                    contentType = { _, _ -> "rail" }
                ) { railIndex, section ->
                    // Every rail in `sections` has a state; the fallback is only
                    // so a band cannot crash on the frame its title arrives.
                    val rowState = rowStates[section.title] ?: LazyListState()
                    val pagingState = paging(section.title)

                    Column {
                        BrowseRailTitle(
                            title = section.title,
                            loadingMore = pagingState.isLoadingMore
                        )

                        LazyRow(
                            state = rowState,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(
                                items = section.items,
                                key = { _, railItem -> cardKey(railItem) }
                            ) { position, railItem ->
                                val mediaType = sectionsMediaType(railItem.mediaType)
                                val imdbId =
                                    resolvedIds[lookupKey(railItem.item.id, mediaType)]
                                val tmdbKeyId = "tmdb:${railItem.item.id}"
                                val watched =
                                    imdbId?.let {
                                        watchedKey(it, mediaType) in watchedKeys
                                    } == true ||
                                        watchedKey(tmdbKeyId, mediaType) in watchedKeys
                                // The checkmark wins over the eye, tested here
                                // rather than trusted from the view model's
                                // subtraction.
                                val partial =
                                    !watched && (
                                        imdbId?.let {
                                            watchedKey(it, mediaType) in partialWatchedKeys
                                        } == true ||
                                            watchedKey(tmdbKeyId, mediaType) in
                                            partialWatchedKeys
                                        )

                                BrowseTile(
                                    item = railItem,
                                    width = tileWidth,
                                    posterSize = posterSize,
                                    isWatched = watched,
                                    isPartiallyWatched = partial,
                                    onClick = {
                                        BrowseReturnFocus.record(
                                            record(
                                                section.title,
                                                railIndex,
                                                position,
                                                railItem
                                            )
                                        )
                                        onOpenItem(railItem)
                                    },
                                    onLongClick = {
                                        menu = railItem to record(
                                            section.title,
                                            railIndex,
                                            position,
                                            railItem
                                        )
                                    }
                                )
                            }

                            // The next page lives where the rail ends, so it is
                            // one swipe further, not below the band.
                            if (pagingState.tailVisible) {
                                item(key = "more:${section.title}") {
                                    Box(
                                        modifier = Modifier
                                            .width(tileWidth)
                                            .height(if (landscape) 96.dp else 120.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        KBButton(
                                            label = if (pagingState.isLoadingMore) {
                                                "LOADING…"
                                            } else {
                                                "LOAD MORE"
                                            },
                                            enabled = !pagingState.isLoadingMore,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            onLoadMore(section.title)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // The row menu: the same sheet the rest of the app uses, with the same
        // library, hide, details and watched rows the TV rail menu holds.
        menu?.let { (selected, target) ->
            val mediaType = sectionsMediaType(selected.mediaType)
            val imdbId = resolvedIds[lookupKey(selected.item.id, mediaType)]
            val tmdbKeyId = "tmdb:${selected.item.id}"
            val isWatched =
                imdbId?.let { watchedKey(it, mediaType) in watchedKeys } == true ||
                    watchedKey(tmdbKeyId, mediaType) in watchedKeys

            PosterContextMenu(
                title = selected.item.title ?: selected.item.name ?: "",
                subtitle = (selected.item.releaseDate ?: selected.item.firstAirDate)
                    ?.take(4)
                    ?.let { year -> "$year · ${if (mediaType == "series") "Series" else "Movie"}" },
                libraryTarget = LibraryAddTarget(
                    mediaType = mediaType,
                    imdbId = imdbId,
                    tmdbId = selected.item.id,
                    title = selected.item.title ?: selected.item.name ?: "Untitled",
                    year = (selected.item.releaseDate ?: selected.item.firstAirDate)
                        ?.take(4)?.toIntOrNull(),
                    posterUrl = selected.item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { TmdbRepository.POSTER_BASE + it }
                ),
                hideTarget = hideTarget(
                    selected.item.title ?: selected.item.name ?: "",
                    selected.mediaType,
                    selected.item.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                    listOf(selected.item.id.toString(), imdbId),
                    year = (selected.item.releaseDate ?: selected.item.firstAirDate)
                        ?.take(4)?.toIntOrNull()
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        menu = null
                        // The menu opens the same Detail page the card does,
                        // so it raises the same return record.
                        BrowseReturnFocus.record(target)
                        onOpenItem(selected)
                    },
                    PosterContextAction(
                        label = watchedMenuLabel(
                            isWatched = isWatched,
                            mediaType = selected.mediaType
                        ),
                        description = watchedMenuDescription(
                            isWatched = isWatched,
                            mediaType = selected.mediaType
                        )
                    ) {
                        menu = null
                        onToggleWatched(selected, isWatched)
                    }
                ),
                onDismiss = { menu = null }
            )
        }
    }
}

/** The page header: eyebrow, title, the brand mark or poster fan, and the facts. */
@Composable
private fun BrowseHeaderPanel(
    header: SectionsHeader,
    fanUrls: List<String>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            header.eyebrow?.takeIf { it.isNotBlank() }?.let { eyebrow ->
                Text(
                    text = eyebrow.uppercase(),
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = header.title,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            header.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            header.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Text(
                    text = meta,
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            header.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        when {
            header.logoUrls.isNotEmpty() -> BrandLogo(
                urls = header.logoUrls,
                name = header.title,
                modifier = Modifier
                    .width(132.dp)
                    .height(60.dp)
                    .padding(start = 12.dp)
            )

            fanUrls.isNotEmpty() -> Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A small fan, the touch reading of the TV header: the top
                // titles' own posters, overlapped and tilted, with the middle
                // one forward.
                val rotations = listOf(-9f, -4f, 0f, 4f, 9f)
                val widths = listOf(44.dp, 50.dp, 58.dp, 50.dp, 44.dp)
                val heights = listOf(66.dp, 75.dp, 87.dp, 75.dp, 66.dp)
                fanUrls.take(5).forEachIndexed { index, url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset(x = (-10 * index).dp)
                            .rotate(rotations[index])
                            .clip(KBShapeCard)
                            .width(widths[index])
                            .height(heights[index])
                    )
                }
            }
        }
    }
}

/**
 * One rail's heading: its title, and the append state while a page is in
 * flight - the phone's stand-in for the TV pendant that hangs off the rail.
 */
@Composable
private fun BrowseRailTitle(title: String, loadingMore: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = KBTextLo,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (loadingMore) {
            Text(
                text = "LOADING…",
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * One poster in a rail: the shared touch card at [width], with the shared
 * caption block under it (title/year/rating, each gated by its Settings toggle).
 */
@Composable
private fun BrowseTile(
    item: StudioItem,
    width: Dp,
    posterSize: TouchPosterSize,
    isWatched: Boolean,
    isPartiallyWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TouchGlobalPosterCard(
            posterUrl = item.item.posterPath
                ?.let { "${TmdbRepository.POSTER_BASE}$it" },
            backdropUrl = item.item.backdropPath
                ?.let { "${TmdbRepository.BACKDROP_BASE}$it" },
            contentDescription = item.item.title ?: item.item.name,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            artId = item.item.id.toString(),
            artType = item.mediaType,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height
        )
        TouchPosterCaptions(
            title = item.item.title ?: item.item.name ?: "Untitled",
            year = (item.item.releaseDate ?: item.item.firstAirDate)?.take(4),
            rating = item.item.voteAverage,
            modifier = Modifier.width(width)
        )
    }
}

/** A full-span status line, with an optional action under it. */
@Composable
private fun BrowseStatus(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        if (action != null && onAction != null) {
            KBButton(label = action, onClick = onAction)
        }
    }
}
