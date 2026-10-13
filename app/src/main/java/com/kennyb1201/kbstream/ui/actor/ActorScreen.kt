package com.kennyb1201.kbstream.ui.actor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.metaLine
import com.kennyb1201.kbstream.ui.components.KBSkeletonRailStack
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_DELAY_MS
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_STEPS
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.revealBrowseReturnRail
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlow
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import kotlinx.coroutines.delay

/**
 * The rail keys of the two filmography rails, and of the outer items that hold
 * them: [revealBrowseReturnRail] looks for a rail by the key its OUTER item
 * carries, and the per-rail filter matches the same value (see
 * [BrowseReturnFocus]).
 */
private const val ACTOR_MOVIES_RAIL = "moviesrow"
private const val ACTOR_TV_RAIL = "tvrow"

/**
 * The Actor page's own key, the only place it is spelled out (see
 * [BrowseReturnFocus]). One page per person: two actors' filmographies must not
 * read each other's record.
 */
private fun actorBrowseScreenKey(actorId: Int): String = "actor:$actorId"

@OptIn(ExperimentalFoundationApi::class)
private class ActorBringIntoViewSpec(
    private val parentFraction: Float = 0.3f,
    private val childFraction: Float = 0f
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val targetOffset = parentFraction * containerSize
        val childOffset = childFraction * size
        val destination = targetOffset - childOffset
        return offset - destination
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val LocalActorBringIntoViewSpec = ActorBringIntoViewSpec()

/**
 * Whether the viewer has hidden this credit.
 *
 * Read the way every other browser rail reads it (see [HiddenTitles.hides]):
 * the IMDB id the ViewModel resolved for it, its TMDB id, or the
 * title-and-year spelling a surface with no ids writes. Without the IMDB half a
 * title hidden from Home or the Library - where that id is what is recorded -
 * stayed on the person's filmography.
 */
private fun creditHidden(
    credit: TmdbPersonCredit,
    normalizedType: String,
    hidden: Set<String>,
    resolvedCreditIds: Map<String, String?>,
    viewModel: ActorViewModel
): Boolean = HiddenTitles.hides(
    hidden,
    normalizedType,
    credit.title ?: credit.name,
    (credit.releaseDate ?: credit.firstAirDate)?.take(4)?.toIntOrNull(),
    credit.id.toString(),
    resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedType)]
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActorScreen(
    actorId: Int,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val person by viewModel.person.collectAsStateWithLifecycle()
    val resolvedCreditIds by viewModel.resolvedCreditIds.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val topWorkBackdropUrl by viewModel.topWorkBackdropUrl.collectAsStateWithLifecycle()
    val topWorkCredit by viewModel.topWorkCredit.collectAsStateWithLifecycle()

    // The viewer's image choices, read as state so a still picked for the
    // top-billed work repaints this page's header on the next frame (see
    // ImageOverrides).
    val overridePaths = rememberImageOverridePaths()

    val posterSize = rememberPosterSize()

    // Where this page's Detail page was opened from, read once when the credits
    // land: the card the viewer pressed, its rail and its position, so backing
    // out of Detail reopens the filmography where they left it instead of at
    // the top (see [BrowseReturnFocus]).
    val browseReturnKey = remember(actorId) { actorBrowseScreenKey(actorId) }
    val browseListState = rememberLazyListState()
    var returnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    // How many times this page has waited for the return's rail to land behind.
    var returnAttempts by remember { mutableStateOf(0) }
    var focusPlaced by remember { mutableStateOf(false) }
    val returnFocusRequester = remember { FocusRequester() }
    val firstCreditFocusRequester = remember { FocusRequester() }

    LaunchedEffect(actorId) {
        viewModel.load(actorId)
    }

    // Long-press context menu for credit posters.
    var menuCredit by remember {
        mutableStateOf<TmdbPersonCredit?>(
            null
        )
    }

    // The place the open menu's title was pressed from, so its "Go to Details"
    // records the same return a card press would (see [BrowseReturnFocus]).
    var menuReturnTarget by remember {
        mutableStateOf<BrowseReturnTarget?>(
            null
        )
    }

    var lastCreditFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissCreditMenu() {
        menuCredit = null
        menuReturnTarget = null
        lastCreditFocusRequester?.requestFocus()
    }

    val loadedPerson = person
    when {
        // Poster-shaped placeholders, like every other browse page. The
        // actor's rails then arrive at their real size instead of a lone
        // centered spinner that shifts the whole layout when the credits land.
        isLoading -> KBSkeletonRailStack(
            posterWidth = posterSize.width,
            posterHeight = posterSize.height,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 36.dp),
            railCount = 3,
            shape = posterEdgeShape()
        )
        error != null ->
            KBStatusMessage(
                message = "Error: $error",
                // A failed actor load left nothing to look at, and the
                // ViewModel's load() re-fetches from scratch.
                onRetry = { viewModel.load(actorId) }
            )
        loadedPerson != null -> {
            val p = loadedPerson
            val context = androidx.compose.ui.platform.LocalContext.current
            val sortedCredits = remember(p) { viewModel.sortedCredits(p) }
            // The header's still, through the chain every backdrop surface
            // walks (see ImageOverrides.backdropPathFor). This page offers no
            // backdrop row of its own, so what the chain buys it is the
            // all-screens choice: a viewer who sets one still for a title sees
            // it behind that title's lead's filmography too, and the top work's
            // own TMDB still is what is left when they have set none.
            val backdropUrl = imageOverrideBackdropUrl(
                paths = overridePaths,
                tmdbId = topWorkCredit?.id,
                screen = ImageOverrides.SCREEN_ALL,
                fallbackUrl = topWorkBackdropUrl
            )
            val headerPortraitUrl = remember(p) {
                p.profilePath?.let { TmdbRepository.PROFILE_BASE + it }
            }

            // Hidden titles are filtered out of the credits as they are read,
            // so the long-press Hide takes the poster it was pressed on off the
            // rail - the one place in the app where hiding a title recorded the
            // choice and left the card sitting there (see [creditHidden]). A
            // rail it empties is dropped with the credit, which is what the
            // guards below already do.
            val hiddenTitleKeys = rememberHiddenTitleKeys()
            val movies = remember(sortedCredits, hiddenTitleKeys, resolvedCreditIds) {
                sortedCredits.filter {
                    it.mediaType == "movie" &&
                        !creditHidden(
                            it, "movie", hiddenTitleKeys, resolvedCreditIds, viewModel
                        )
                }
            }
            val tvShows = remember(sortedCredits, hiddenTitleKeys, resolvedCreditIds) {
                sortedCredits.filter {
                    it.mediaType == "tv" &&
                        !creditHidden(
                            it, "series", hiddenTitleKeys, resolvedCreditIds, viewModel
                        )
                }
            }

            // The outer item indices of the two rail rows, which is what
            // [revealBrowseReturnRail] scrolls to: the biography, when the
            // person has one, is the item before the first header.
            val moviesRowIndex = if (p.biography.orEmpty().isNotBlank()) 2 else 1
            val tvRowIndex = moviesRowIndex + 2

            /**
             * Puts the page back where the viewer left it: the rail on screen,
             * its row scrolled to the card, and the focus on that card. Answers
             * whether the focus landed; a record for a rail this page does not
             * paint answers false, and the caller holds the record and asks
             * again - the credits land before the rails always have.
             */
            suspend fun restoreBrowseReturn(recorded: BrowseReturnTarget): Boolean {
                val railKey = recorded.railKey ?: return false
                val railKeys = buildList {
                    if (movies.isNotEmpty()) add(ACTOR_MOVIES_RAIL)
                    if (tvShows.isNotEmpty()) add(ACTOR_TV_RAIL)
                }
                if (railKey !in railKeys) return false
                browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)
                var landed = false
                var attempts = 0
                while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
                    delay(BROWSE_RETURN_FOCUS_DELAY_MS)
                    landed = runCatching { returnFocusRequester.requestFocus() }
                        .getOrDefault(false)
                    attempts++
                }
                return landed
            }

            /**
             * Takes the page's own first card: where an ordinary open lands,
             * and the last resort for a return this page could never put back.
             * The D-pad was dead on a fresh Actor page until the viewer did
             * something that happened to grab focus, so this is the landing an
             * ordinary open was missing. Retried for the same reason as the
             * other: the rail may not have composed its first card yet.
             */
            suspend fun focusFirstCard() {
                var landed = false
                var attempts = 0
                while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
                    delay(BROWSE_RETURN_FOCUS_DELAY_MS)
                    landed = runCatching { firstCreditFocusRequester.requestFocus() }
                        .getOrDefault(false)
                    attempts++
                }
            }

            // A page reopened from one of its own Detail pages comes back on the
            // card the viewer pressed; an ordinary open, or a return this page
            // could never put back, falls through to the first card. The record
            // is HELD until honoured: the rail that holds the card can land
            // after the first one does, and a record read and then dropped
            // would lose the place it was raised for (see [BrowseReturnFocus]).
            LaunchedEffect(movies, tvShows) {
                if (!focusPlaced && (movies.isNotEmpty() || tvShows.isNotEmpty())) {
                    val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)
                    if (recorded != null && returnAttempts < BROWSE_RETURN_FOCUS_STEPS) {
                        returnTarget = recorded
                        returnAttempts++
                        focusPlaced = restoreBrowseReturn(recorded)
                        return@LaunchedEffect
                    }
                    focusPlaced = true
                    focusFirstCard()
                }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalActorBringIntoViewSpec) {
                Box(modifier = Modifier.fillMaxSize()) {
                    backdropUrl?.let {
                        AsyncImage(
                            model = remember(backdropUrl) {
                                // Full-screen hero backdrop behind the
                                // filmography rails; one image, so it keeps
                                // the fade.
                                ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build()
                            },
                            contentDescription = topWorkCredit?.title ?: topWorkCredit?.name ?: p.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        KBVoid.copy(alpha = 0.65f),
                                        KBVoid
                                    )
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        KBVoid.copy(alpha = 0.72f),
                                        KBVoid.copy(alpha = 0.28f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup()
                            .focusRestorer()
                    ) {
                        Spacer(modifier = Modifier.height(36.dp))

                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                val initials = remember(p.name) {
                                    p.name.trim().split(" ")
                                        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                        .take(2)
                                        .joinToString("")
                                }
                                Box(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(144.dp)
                                        .clip(KBShapeCard)
                                        .background(KBSurfaceRaised)
                                ) {
                                    if (headerPortraitUrl != null) {
                                        AsyncImage(
                                            model = headerPortraitUrl,
                                            contentDescription = p.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                initials,
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = KBTextLo
                                            )
                                        }
                                    }
                                }

                                Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                                    Text(
                                        p.name,
                                        style = MaterialTheme.typography.headlineLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val bioMeta = remember(p) { p.metaLine() }
                                    if (bioMeta.isNotBlank()) {
                                        Text(
                                            bioMeta,
                                            color = KBTextLo,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    topWorkCredit?.let { credit ->
                                        Text(
                                            text = "Known for ${credit.title ?: credit.name ?: "Top work"}",
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        LazyColumn(
                            // The screen's own state, so a return can scroll the
                            // rail holding the pressed card back on screen (see
                            // [BrowseReturnFocus]).
                            state = browseListState,
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth()
                                .focusGroup()
                                .focusRestorer(),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
                        ) {
                            val biography = p.biography.orEmpty()
                            if (biography.isNotBlank()) {
                                item(key = "biography") {
                                    // animateContentSize so View more/less slides
                                    // the block to its new height instead of
                                    // snapping: the toggle swaps maxLines, and a
                                    // layout that pops from four lines to
                                    // fifteen reads as a redraw rather than as
                                    // the same paragraph opening up. The rails
                                    // below keep their own state - this only
                                    // animates the bio's own box.
                                    Column(
                                        modifier = Modifier
                                            .padding(
                                                start = 24.dp,
                                                end = 24.dp,
                                                top = 4.dp,
                                                bottom = 20.dp
                                            )
                                            .animateContentSize()
                                    ) {
                                        var bioExpanded by remember { mutableStateOf(false) }
                                        var bioOverflows by remember { mutableStateOf(false) }
                                        Text(
                                            biography,
                                            color = KBTextHi,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                                            overflow = TextOverflow.Ellipsis,
                                            onTextLayout = { textLayoutResult ->
                                                if (!bioExpanded && textLayoutResult.hasVisualOverflow) {
                                                    bioOverflows = true
                                                }
                                            }
                                        )
                                        if (bioOverflows) {
                                            // TV focus affordance: the toggle is
                                            // a real tv-material Surface using
                                            // the app-wide focused treatment
                                            // (raised surface + accent content +
                                            // scale + accent border + glow) — the
                                            // same language as KBCard/StudioChip —
                                            // so it lights up clearly on D-pad
                                            // focus and pointer hover alike.
                                            // Earlier hand-rolled focus tracking
                                            // never fired because onFocusChanged
                                            // sat behind clickable in the chain.
                                            Surface(
                                                onClick = { bioExpanded = !bioExpanded },
                                                shape = ClickableSurfaceDefaults.shape(
                                                    shape = KBShapeSmall
                                                ),
                                                colors = ClickableSurfaceDefaults.colors(
                                                    containerColor = Color.Transparent,
                                                    contentColor = KBTextLo,
                                                    focusedContainerColor = KBSurfaceRaised,
                                                    focusedContentColor = KBAccent,
                                                    pressedContainerColor = KBSurfaceRaised,
                                                    pressedContentColor = KBAccent
                                                ),
                                                scale = ClickableSurfaceDefaults.scale(
                                                    focusedScale = KBFocusChip,
                                                    pressedScale = KBFocusPressed
                                                ),
                                                border = ClickableSurfaceDefaults.border(
                                                    border = Border(
                                                        border = BorderStroke(
                                                            1.dp,
                                                            KBTextLo.copy(alpha = 0.35f)
                                                        ),
                                                        shape = KBShapeSmall
                                                    ),
                                                    focusedBorder = Border(
                                                        border = BorderStroke(
                                                            2.dp,
                                                            KBAccent
                                                        ),
                                                        shape = KBShapeSmall
                                                    )
                                                ),
                                                glow = ClickableSurfaceDefaults.glow(
                                                    focusedGlow = Glow(
                                                        elevationColor = KBAccent,
                                                        elevation = KBFocusGlow
                                                    )
                                                ),
                                                modifier = Modifier.padding(top = 8.dp)
                                            ) {
                                                Text(
                                                    text = if (bioExpanded) "View less" else "View more",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(
                                                        horizontal = 10.dp,
                                                        vertical = 5.dp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (movies.isNotEmpty()) {
                                item(key = "moviesheader") {
                                    Text(
                                        "MOVIES",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = ACTOR_MOVIES_RAIL) {
                                    ActorCreditRail(
                                        credits = movies,
                                        railKey = ACTOR_MOVIES_RAIL,
                                        railIndex = moviesRowIndex,
                                        normalizedMediaType = "movie",
                                        isFirstRail = true,
                                        bottomPadding = 16.dp,
                                        browseReturnKey = browseReturnKey,
                                        returnTarget = returnTarget?.takeIf { it.railKey == ACTOR_MOVIES_RAIL },
                                        returnFocusRequester = returnFocusRequester,
                                        firstCreditFocusRequester = firstCreditFocusRequester,
                                        resolvedCreditIds = resolvedCreditIds,
                                        watchedKeys = watchedKeys,
                                        partialWatchedKeys = partialWatchedKeys,
                                        viewModel = viewModel,
                                        onNavigateDetail = onNavigateDetail,
                                        onOpenPosterMenu = { credit, requester, target ->
                                            lastCreditFocusRequester = requester
                                            menuCredit = credit
                                            menuReturnTarget = target
                                        }
                                    )
                                }
                            }

                            if (tvShows.isNotEmpty()) {
                                item(key = "tvheader") {
                                    Text(
                                        "TV SHOWS",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = ACTOR_TV_RAIL) {
                                    ActorCreditRail(
                                        credits = tvShows,
                                        railKey = ACTOR_TV_RAIL,
                                        railIndex = tvRowIndex,
                                        normalizedMediaType = "series",
                                        // The first rail the page draws takes the
                                        // entry focus when the movie rail is
                                        // absent.
                                        isFirstRail = movies.isEmpty(),
                                        bottomPadding = 32.dp,
                                        browseReturnKey = browseReturnKey,
                                        returnTarget = returnTarget?.takeIf { it.railKey == ACTOR_TV_RAIL },
                                        returnFocusRequester = returnFocusRequester,
                                        firstCreditFocusRequester = firstCreditFocusRequester,
                                        resolvedCreditIds = resolvedCreditIds,
                                        watchedKeys = watchedKeys,
                                        partialWatchedKeys = partialWatchedKeys,
                                        viewModel = viewModel,
                                        onNavigateDetail = onNavigateDetail,
                                        onOpenPosterMenu = { credit, requester, target ->
                                            lastCreditFocusRequester = requester
                                            menuCredit = credit
                                            menuReturnTarget = target
                                        }
                                    )
                                }
                            }
                        }
                    }

                    menuCredit?.let { credit ->
                        // Same normalized type/key the credit rails use for the
                        // badge, so the toggle matches the poster's current state.
                        val menuMediaType =
                            when (credit.mediaType?.lowercase()) {
                                "movie" -> "movie"
                                "tv", "series" -> "series"
                                else -> null
                            }

                        val isWatched =
                            if (menuMediaType != null) {
                                resolvedCreditIds[
                                    viewModel.creditLookupKey(
                                        credit.id,
                                        menuMediaType
                                    )
                                ]?.let { imdbId ->
                                    viewModel.watchedKey(
                                        imdbId,
                                        menuMediaType
                                    ) in watchedKeys
                                } == true
                            } else {
                                false
                            }

                        PosterContextMenu(
                            title = credit.title
                                ?: credit.name
                                ?: "",
                            // The role's own ids, so the add reaches the
                            // trackers with the pair they key off: the IMDB id
                            // this screen already resolved for the badge, plus
                            // the TMDB id the credit carries.
                            libraryTarget = LibraryAddTarget(
                                mediaType = menuMediaType ?: "movie",
                                imdbId = resolvedCreditIds[
                                    viewModel.creditLookupKey(
                                        credit.id,
                                        menuMediaType ?: "movie"
                                    )
                                ],
                                tmdbId = credit.id,
                                title = credit.title
                                    ?: credit.name
                                    ?: "Untitled",
                                year = (credit.releaseDate
                                    ?: credit.firstAirDate)
                                    ?.take(4)?.toIntOrNull(),
                                posterUrl = credit.posterPath
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        TmdbRepository.POSTER_BASE + it
                                    }
                            ),
                            hideTarget = hideTarget(
                                credit.title
                                    ?: credit.name
                                    ?: "",
                                credit.mediaType ?: "movie",
                                credit.posterPath
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        "${TmdbRepository.POSTER_BASE}$it"
                                    },
                                listOf(
                                    credit.id.toString(),
                                    resolvedCreditIds[
                                        viewModel.creditLookupKey(
                                            credit.id,
                                            credit.mediaType ?: "movie"
                                        )
                                    ]
                                )
                            ),
                            actions = listOf(
                                PosterContextAction(
                                    label = "Go to Details",
                                    description = "Open this title's detail page"
                                ) {
                                    // The same place a card press records: backing
                                    // out of the Detail page this opens returns to
                                    // the credit's own rail and position (see
                                    // [BrowseReturnFocus]).
                                    menuReturnTarget?.let { BrowseReturnFocus.record(it) }
                                    menuCredit = null
                                    menuReturnTarget = null
                                    viewModel.resolveAndNavigate(
                                        credit.id,
                                        credit.mediaType
                                            ?: "movie",
                                        onNavigateDetail
                                    )
                                },
                                PosterContextAction(
                                    label = watchedMenuLabel(
                                        isWatched = isWatched,
                                        mediaType = credit.mediaType ?: "movie"
                                    ),
                                    description = watchedMenuDescription(
                                        isWatched = isWatched,
                                        mediaType = credit.mediaType ?: "movie"
                                    )
                                ) {
                                    menuCredit = null
                                    menuReturnTarget = null
                                    if (isWatched) {
                                        viewModel.markUnwatched(
                                            credit.id,
                                            credit.mediaType
                                                ?: "movie"
                                        )
                                    } else {
                                        viewModel.markAsWatched(
                                            credit.id,
                                            credit.mediaType
                                                ?: "movie"
                                        )
                                    }
                                    lastCreditFocusRequester?.requestFocus()
                                }
                            ),
                            onDismiss = {
                                dismissCreditMenu()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One filmography rail, keyed so a return from Detail can put it back: the
 * outer page reveals the rail, this scrolls the row to the card, and the card
 * the record names carries [returnFocusRequester] (see [BrowseReturnFocus]).
 */
@Composable
private fun ActorCreditRail(
    credits: List<TmdbPersonCredit>,
    railKey: String,
    railIndex: Int,
    normalizedMediaType: String,
    isFirstRail: Boolean,
    bottomPadding: Dp,
    browseReturnKey: String,
    returnTarget: BrowseReturnTarget?,
    returnFocusRequester: FocusRequester,
    firstCreditFocusRequester: FocusRequester,
    resolvedCreditIds: Map<String, String?>,
    watchedKeys: Set<String>,
    partialWatchedKeys: Set<String>,
    viewModel: ActorViewModel,
    onNavigateDetail: (String, String) -> Unit,
    onOpenPosterMenu: (TmdbPersonCredit, FocusRequester, BrowseReturnTarget) -> Unit
) {
    val rowState = rememberLazyListState()

    // Puts this rail ON the card a return is coming back for: a rail the viewer
    // scrolled twenty cards into has not composed that card, so it has no focus
    // node yet (see [BrowseReturnFocus]).
    LaunchedEffect(returnTarget) {
        returnTarget?.let { target ->
            runCatching { rowState.scrollToItem(target.position) }
        }
    }

    LazyRow(
        state = rowState,
        // Horizontal at the app's screen edge, and vertical with the room a
        // focused tile needs INSIDE the row's own clips-to-bounds viewport:
        // 8dp here cut the top of the first credits row's halo off flat (see
        // [KBFocusRowInset]).
        contentPadding = PaddingValues(
            start = KBScreenEdge,
            end = KBScreenEdge,
            top = KBFocusRowInset,
            bottom = KBFocusRowInset
        ),
        modifier = Modifier.padding(bottom = bottomPadding).focusGroup().focusRestorer()
    ) {
        itemsIndexed(
            items = credits,
            key = { _, credit -> credit.id }
        ) { index, credit ->
            val requester = remember(credit.id) { FocusRequester() }
            val cardKey = credit.id.toString()
            val target = BrowseReturnTarget(
                screenKey = browseReturnKey,
                itemId = cardKey,
                position = index,
                railKey = railKey,
                railIndex = railIndex
            )
            // The card the record names, by the key this rail keys it with, so
            // a rail that has re-ordered under the viewer comes back on the
            // card that is still there rather than on whatever took its place.
            val isReturnItem =
                returnTarget != null &&
                    index == returnTarget.position &&
                    cardKey == returnTarget.itemId
            val isFirstItem = isFirstRail && index == 0

            ActorCreditCard(
                credit = credit,
                isWatched = resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedMediaType)]
                    ?.let { imdbId -> viewModel.watchedKey(imdbId, normalizedMediaType) in watchedKeys } == true,
                isPartiallyWatched = resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedMediaType)]
                    ?.let { imdbId -> viewModel.watchedKey(imdbId, normalizedMediaType) in partialWatchedKeys } == true,
                onClick = {
                    // Remember this card, so backing out of its Detail page
                    // reopens this rail ON it (see [BrowseReturnFocus]).
                    BrowseReturnFocus.record(target)
                    viewModel.resolveAndNavigate(
                        credit.id,
                        credit.mediaType ?: normalizedMediaType,
                        onNavigateDetail
                    )
                },
                onLongClick = { onOpenPosterMenu(credit, requester, target) },
                focusRequester = requester,
                // A return wins over the entry focus if both land on one card:
                // the return is the viewer's own place.
                extraFocusRequester = returnFocusRequester.takeIf { isReturnItem }
                    ?: firstCreditFocusRequester.takeIf { isFirstItem }
            )
        }
    }
}

@Composable
private fun ActorCreditCard(
    credit: TmdbPersonCredit,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    // A second requester for the same card: the entry focus on a fresh open, or
    // the return focus on a card backed into from Detail (see
    // [BrowseReturnFocus]).
    extraFocusRequester: FocusRequester? = null,
    isPartiallyWatched: Boolean = false
) {
    val posterSize = rememberPosterSize()
    // Landscape tiles are wider than the poster they replace, so the tile's own
    // container follows the shape the card is about to draw.
    val tileWidth = rememberPosterTileWidth(posterSize.width)
    // The tile's own focus drives the caption marquee below.
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(tileWidth)
            .padding(end = 12.dp)
            .onFocusChanged { focused = it.hasFocus }
    ) {
        GlobalPosterCard(
            posterUrl = remember(credit.posterPath) { credit.posterPath?.let { TmdbRepository.POSTER_BASE + it } },
            contentDescription = credit.title ?: credit.name ?: "",
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height,
            // A credit carries only a poster; the shared resolver fills in the
            // backdrop + clearlogo for the landscape shape.
            artId = credit.id.toString(),
            artType = credit.mediaType ?: "movie",
            modifier = Modifier
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (extraFocusRequester != null) {
                        Modifier.focusRequester(extraFocusRequester)
                    } else {
                        Modifier
                    }
                )
        )

        PosterCaptions(
            title = credit.title ?: credit.name,
            focused = focused,
            year = (credit.releaseDate ?: credit.firstAirDate)?.take(4),
            rating = credit.voteAverage,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}
