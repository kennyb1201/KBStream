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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.ui.components.KBSkeletonRailStack
import com.kennyb1201.kbstream.ui.mobile.ActorScreenMobile
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
import com.kennyb1201.kbstream.ui.browse.BrowseReturnDecision
import com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus
import com.kennyb1201.kbstream.ui.browse.BrowseReturnTarget
import com.kennyb1201.kbstream.ui.browse.browseReturnDecision
import com.kennyb1201.kbstream.ui.browse.rememberFocusHandBack
import com.kennyb1201.kbstream.ui.browse.revealBrowseReturnRail
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The rail keys of the two filmography rails, and of the outer items that hold
 * them: [revealBrowseReturnRail] looks for a rail by the key its OUTER item
 * carries, and the per-rail filter matches the same value (see
 * [BrowseReturnFocus]).
 */
internal const val ACTOR_MOVIES_RAIL = "moviesrow"
internal const val ACTOR_TV_RAIL = "tvrow"

/**
 * The Actor page's own key, the only place it is spelled out (see
 * [BrowseReturnFocus]). One page per person: two actors' filmographies must not
 * read each other's record.
 */
internal fun actorBrowseScreenKey(actorId: Int): String = "actor:$actorId"

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
 * The room a focused poster's caption row needs under it.
 *
 * The focused node a rail reports to the page's list is the POSTER, not the
 * card: the title, year and rating are drawn in a [PosterCaptions] column below
 * it, so a landing that leaves only [KBFocusRowInset] under the poster puts a
 * row the viewer has switched on below the fold. That is the field report -
 * "scroll down and the date, title and rating are gone" - and it is arithmetic,
 * not a guess: this is the height of the caption block at its tallest (one title
 * line plus the year/rating line, with the 5dp gap the card draws).
 */
private val ActorCaptionRoom = 44.dp

/**
 * Focus scrolling for the page's own VERTICAL list - the bio and the rows under
 * it - which is a different question from where a tile comes to rest in a rail.
 *
 * A rail's landing is a line ([ActorBringIntoViewSpec]): bring the focused card
 * to 30% of the row so the viewer sees what is coming next. That is right for a
 * horizontal row and wrong for the page, and the page used to install it on the
 * whole screen - so every focus inside the vertical list was DRAGGED to 30% of
 * the viewport, whether or not it was already on screen. The field report is
 * that arithmetic: opening a person moved focus to the first filmography card
 * (about 370dp down), the spec scrolled the list by the difference between that
 * and 30% of the viewport, and the biography - the thing the page opens on - was
 * pushed off the top, so the viewer had to scroll UP to read it; focusing
 * "View more" then re-applied the same line and cut the bio's first lines off
 * at the list's own top edge.
 *
 * This is the rule the app already uses for vertical lists (see
 * `KBFocusRowInset` and the sports hub's own spec): keep that inset of room
 * between the focused row and the edge of the viewport, scroll only as far as it
 * takes to clear it, and answer ZERO when the row is already comfortably inside
 * it. A page at rest therefore stays at rest, and the bio stays where the viewer
 * arrived.
 *
 * `internal`, because the two answers that matter here - a visible row asks for
 * no scroll, and an off-screen one lands on the margin rather than flush - are
 * arithmetic a contract test asserts exactly, with no device and no D-pad.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class ActorListBringIntoViewSpec(private val insetPx: Float) : BringIntoViewSpec {

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        // A margin can never eat the viewport: a row taller than the room two
        // margins leave still lands on the nearest edge rather than being
        // scrolled somewhere it could not be seen at all.
        val margin = insetPx.coerceAtMost(containerSize / 3f)
        val trailingEdge = offset + abs(size)
        // Already inside the viewport: there is no landing to make. This is the
        // answer the page was missing, and it is also what keeps a row that
        // merely remeasured from being nudged by its own re-request.
        if (offset >= 0f && trailingEdge <= containerSize) return 0f
        return when {
            // Past the bottom margin: scroll forward just far enough to clear it.
            trailingEdge > containerSize - margin -> trailingEdge - (containerSize - margin)
            // Above the top one: back off by the same amount.
            offset < margin -> offset - margin
            // Already clear of both: no scroll at all.
            else -> 0f
        }
    }
}

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
    // The phone's header arrow. The TV page draws no back control (the remote
    // is the way out), so this only reaches ActorScreenMobile.
    onBack: () -> Unit,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = viewModel()
) {
    // The phone entry: the same ViewModel, filmography sorting, hidden-title
    // filter and credit actions, laid out for touch. One branch; the TV page
    // below is unchanged (see ActorScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        ActorScreenMobile(
            actorId = actorId,
            onBack = onBack,
            onNavigateDetail = onNavigateDetail,
            viewModel = viewModel
        )
        return
    }

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
    // All four of these are PER ACTOR, so they are keyed on [actorId]: this
    // screen is one composable for every actor, and a page opened FROM another
    // actor's page (a cast list, a "More Like This" poster) reuses it. Unkeyed,
    // the new actor's page inherited the previous one's return record and tried
    // to put the focus back on a card that is not on this page at all - and it
    // had already spent that record's budget getting there.
    var returnTarget by remember(actorId) {
        mutableStateOf<BrowseReturnTarget?>(null)
    }
    // How many LANDING tries this page has spent on the return's card - the
    // recorded rail on screen, a request made, and it missed. Waiting for that
    // rail to arrive at all spends none of it, which is the difference between
    // a rail that builds slowly and a return that never lands (see
    // [browseReturnDecision]).
    var returnAttempts by remember(actorId) { mutableStateOf(0) }
    // When the held record was first waited for, so a rail whose load failed
    // hands the D-pad back to the first card instead of holding it for ever
    // (see [BROWSE_RETURN_WAIT_MS]).
    var returnWaitStartMs by remember(actorId) { mutableStateOf(0L) }
    var focusPlaced by remember(actorId) { mutableStateOf(false) }
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

    // Closing the menu owes the D-pad a landing on the card that raised it, and
    // the landing is verified rather than assumed: the scrim and the card are
    // recomposed in the same frame, so a single request can miss silently (see
    // [rememberFocusHandBack]).
    val handFocusBack = rememberFocusHandBack()

    fun dismissCreditMenu() {
        menuCredit = null
        menuReturnTarget = null
        handFocusBack(lastCreditFocusRequester)
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

            // The rails this page paints, in the key space its return records
            // use. A record naming a rail that is not in here yet is WAITED for
            // rather than landed on, which is what keeps the page's other rail
            // from spending the landing budget on its own arrival (see
            // [browseReturnDecision]).
            val arrivedRailKeys = buildList {
                if (movies.isNotEmpty()) add(ACTOR_MOVIES_RAIL)
                if (tvShows.isNotEmpty()) add(ACTOR_TV_RAIL)
            }.toSet()

            /**
             * Puts the page back where the viewer left it: the rail on screen,
             * its row scrolled to the card, and the focus on that card. Answers
             * whether the focus landed; a record for a rail this page does not
             * paint answers false, and the caller holds the record and asks
             * again - the credits land before the rails always have.
             */
            suspend fun restoreBrowseReturn(recorded: BrowseReturnTarget): Boolean {
                val railKey = recorded.railKey ?: return false
                if (railKey !in arrivedRailKeys) return false
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
             *
             * Answers whether the focus actually landed, so a miss leaves the
             * retry to the next rail that arrives instead of marking a page
             * with nothing focused as done.
             */
            suspend fun focusFirstCard(): Boolean {
                var landed = false
                var attempts = 0
                while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
                    delay(BROWSE_RETURN_FOCUS_DELAY_MS)
                    landed = runCatching { firstCreditFocusRequester.requestFocus() }
                        .getOrDefault(false)
                    attempts++
                }
                return landed
            }

            // A page reopened from one of its own Detail pages comes back on the
            // card the viewer pressed; an ordinary open, or a return this page
            // could never put back, falls through to the first card. The record
            // is HELD until honoured: the rail that holds the card can land
            // after the first one does, and a record read and then dropped
            // would lose the place it was raised for (see [BrowseReturnFocus]).
            // Two budgets, not one: waiting for the recorded rail to arrive is
            // free and bounded by TIME, while a landing try - the rail on
            // screen, a request made, and it missed - is what spends the
            // twelve-attempt budget. Charging a try for an arrival is what ran
            // this page's budget out on its OTHER rail and reopened it at the
            // top (see [browseReturnDecision]).
            LaunchedEffect(movies, tvShows, isLoading) {
                if (isLoading) {
                    focusPlaced = false
                    // HOLD the record across the reload. These ViewModels are
                    // activity-scoped, so returning from Detail briefly
                    // composes the OLD sections (isLoading still false), this
                    // effect consumes the record, and only then does load()
                    // clear the rows and flip isLoading. Nulling it here
                    // discarded the card the viewer pressed. A genuinely
                    // different actor gets fresh state from the
                    // remember(actorId) keys, so a held record cannot leak.
                    //
                    // returnWaitStartMs is NOT reset either: the ten-second
                    // wait for the recorded rail keeps running from when the
                    // record was read. Zeroing it without a re-consume would
                    // leave the timeout unable to fire.
                    returnAttempts = 0
                } else if (!focusPlaced && (movies.isNotEmpty() || tvShows.isNotEmpty())) {
                    // The wait starts when the record is read, not when a rail
                    // arrives: it is what bounds holding the record at all.
                    val recorded = returnTarget
                        ?: BrowseReturnFocus.consume(browseReturnKey)
                            .also { if (it != null) returnWaitStartMs = System.currentTimeMillis() }
                    if (recorded != null) returnTarget = recorded
                    when (
                        browseReturnDecision(
                            recorded = recorded,
                            arrivedRailKeys = arrivedRailKeys,
                            attemptsSpent = returnAttempts,
                            waitStartMs = returnWaitStartMs,
                            nowMs = System.currentTimeMillis()
                        )
                    ) {
                        // The rail holding the card has not landed yet. Hold the
                        // record and spend nothing: an arrival is not a landing
                        // try, and charging one for it is what ran this page's
                        // budget out on its other rail (see
                        // [browseReturnDecision]).
                        BrowseReturnDecision.WAIT_FOR_RAIL -> return@LaunchedEffect

                        BrowseReturnDecision.TRY_LANDING -> {
                            // Reachable only with a record in hand, by the
                            // rule's own contract.
                            val target = recorded ?: return@LaunchedEffect
                            returnAttempts++
                            // The landing is what marks the page focused; a
                            // false leaves the retry to the next rail that
                            // lands behind this one.
                            focusPlaced = restoreBrowseReturn(target)
                            return@LaunchedEffect
                        }

                        BrowseReturnDecision.FIRST_CARD -> {
                            // The landing is what marks the page focused, exactly
                            // as the return path does. A flag set BEFORE the
                            // suspend request was the trap: this effect restarts
                            // whenever another rail streams in, so an attempt
                            // cancelled inside its delay left the flag true and
                            // the restarted effect skipped the branch - an
                            // ordinary first open with nothing focused and the
                            // D-pad dead.
                            focusPlaced = focusFirstCard()
                        }
                    }
                }
            }

            // The page installs its own vertical landing (see
            // [ActorListBringIntoViewSpec]) instead of the rails' 30% line: the
            // rails install that line around their OWN LazyRow (see
            // [ActorCreditRail]), so the row that wants the focused card a third
            // of the way in still gets it, while the page's own list - the bio
            // and the rows under it - is no longer dragged to that line every
            // time focus moves. Supplying the rail's line here is what scrolled
            // the biography off the top on open.
            val density = LocalDensity.current
            val listBringIntoViewSpec = remember(density) {
                ActorListBringIntoViewSpec(
                    // The poster's own clearance plus the caption row that sits
                    // under it: a landing that clears only the poster leaves the
                    // date, title and rating off the bottom edge.
                    insetPx = with(density) { (KBFocusRowInset + ActorCaptionRoom).toPx() }
                )
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides listBringIntoViewSpec) {
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
                                                // The CHIP glow (8dp), not the
                                                // card's 12dp: this is the same
                                                // control as Detail's overview
                                                // toggle, it is the same size as
                                                // the chips around the app, and a
                                                // 12dp halo is nearly half a pill
                                                // this tall (see
                                                // UiUniformityBatchDContractTest).
                                                glow = ClickableSurfaceDefaults.glow(
                                                    focusedGlow = Glow(
                                                        elevationColor = KBAccent,
                                                        elevation = KBFocusGlowSmall
                                                    )
                                                ),
                                                // Focusing the toggle recovers the
                                                // biography instead of cutting it:
                                                // this is the list's first item but
                                                // not its first focusable one, so a
                                                // D-pad walk up from the rails could
                                                // leave the paragraph scrolled half
                                                // off the top with the toggle alone
                                                // in view. Detail's overview toggle
                                                // does exactly this, for exactly this
                                                // reason - and the bio is the thing
                                                // the viewer came up to read.
                                                // ...only while the bio is
                                                // COLLAPSED. Expanded, the item
                                                // is taller than a screen, so
                                                // snapping its top to the
                                                // viewport's top is what put the
                                                // toggle itself below the bottom
                                                // edge - the "View more is
                                                // clipped when focused" report.
                                                // Expanded, the standard landing
                                                // applies and keeps the toggle
                                                // inside the viewport.
                                                modifier = Modifier
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.hasFocus && !bioExpanded) {
                                                            scope.launch {
                                                                browseListState.animateScrollToItem(0)
                                                            }
                                                        }
                                                    }
                                                    .padding(top = 8.dp)
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
                                        pageListState = browseListState,
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
                                        pageListState = browseListState,
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

                        // The IMDB key first, then this credit's own
                        // `tmdb:<n>` one: a credit past the resolution cap, or
                        // one TMDB returned no `imdb_id` for, is watched under
                        // the flavor that needs no network (see
                        // [WatchedStatusKeys]).
                        val menuTmdbKey = menuMediaType?.let {
                            viewModel.watchedKey(
                                WatchedStatusKeys.tmdbKeyId(credit.id),
                                it
                            )
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
                                } == true || menuTmdbKey in watchedKeys
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
                                    handFocusBack(lastCreditFocusRequester)
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActorCreditRail(
    credits: List<TmdbPersonCredit>,
    railKey: String,
    railIndex: Int,
    normalizedMediaType: String,
    isFirstRail: Boolean,
    bottomPadding: Dp,
    /** The page's own vertical list, so the first rail can scroll it back up. */
    pageListState: LazyListState,
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
    val rowScope = rememberCoroutineScope()

    // Where this rail's return lands, resolved by the card's OWN key rather
    // than by the recorded position: a rail that has re-ordered under the
    // viewer (a hidden title, a new release) still holds the card, and testing
    // the position first meant NOTHING matched once the card had moved - the
    // return requester attached to no card, its retry missed the budget, and
    // the page fell through to its first-card path. The recorded position is
    // only the fallback, for a card the record cannot name.
    val returnIndex = returnTarget?.let { target ->
        credits.indexOfFirst { it.id.toString() == target.itemId }
            .takeIf { it >= 0 }
            ?: target.position.takeIf { it in credits.indices }
    }

    // Puts this rail ON that card: a rail the viewer scrolled twenty cards into
    // has not composed it, so it has no focus node yet (see
    // [BrowseReturnFocus]).
    LaunchedEffect(returnIndex) {
        returnIndex?.let { index ->
            runCatching { rowState.scrollToItem(index) }
        }
    }

    // The rail's own landing line (see [ActorBringIntoViewSpec]): a focused
    // card comes to rest at 30% of the ROW, so the viewer can see what is
    // coming next. Supplied around this LazyRow alone - the page installs its
    // own vertical rule at the top (see [ActorListBringIntoViewSpec]), and it
    // was the two being the same object that scrolled the bio off on open.
    CompositionLocalProvider(LocalBringIntoViewSpec provides LocalActorBringIntoViewSpec) {
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
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .focusGroup()
                .focusRestorer()
                // UP out of the FIRST rail scrolls the page back to the top when
                // the page is scrolled, instead of asking for a focus target that
                // is not composed: the bio and its toggle are the list's first
                // item, so once the rails are in view there is nothing above them
                // to move to - focus search cannot find an item LazyColumn has
                // already disposed - and the page could not be scrolled back up
                // at all. One press brings the bio back on screen; the next one
                // lands on its toggle, which is by then composed.
                .then(
                    if (isFirstRail) {
                        Modifier.onPreviewKeyEvent { event ->
                            val scrolled = pageListState.firstVisibleItemIndex > 0 ||
                                pageListState.firstVisibleItemScrollOffset > 0
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionUp &&
                                scrolled
                            ) {
                                rowScope.launch { pageListState.animateScrollToItem(0) }
                                true
                            } else {
                                false
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
        itemsIndexed(
            items = credits,
            // One row per (title, character): TMDB's person credits carry a
            // row per role, so a performer with two roles in one production
            // (routine for animation voice casts) repeats the id and LazyRow
            // would throw on the duplicate key - the same defect the Detail
            // cast rail has. The return target still names the bare id
            // (returnIndex matches on it), so back-to-card keeps working.
            key = { _, credit -> "credit:${credit.id}:${credit.character.orEmpty()}" }
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
            // Matched on the key alone (see [returnIndex]), which is what makes
            // the promise true: the card is found by the key this rail keys it
            // with, so a rail that has re-ordered under the viewer comes back
            // on the card that is still there rather than on whatever took its
            // place.
            val isReturnItem = index == returnIndex
            val isFirstItem = isFirstRail && index == 0

            // Both id flavors this credit can be watched under: the resolved
            // IMDB one, then its own raw `tmdb:<n>` id, which is the one a
            // credit past the `.take(60)` resolution cap still has (see
            // [WatchedStatusKeys]). The checkmark wins over the eye.
            val creditImdbId =
                resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedMediaType)]
            val creditTmdbWatchedKey =
                viewModel.watchedKey(WatchedStatusKeys.tmdbKeyId(credit.id), normalizedMediaType)
            val creditWatched =
                creditImdbId?.let {
                    viewModel.watchedKey(it, normalizedMediaType) in watchedKeys
                } == true || creditTmdbWatchedKey in watchedKeys

            ActorCreditCard(
                credit = credit,
                isWatched = creditWatched,
                isPartiallyWatched = !creditWatched && (
                    creditImdbId?.let {
                        viewModel.watchedKey(it, normalizedMediaType) in partialWatchedKeys
                    } == true || creditTmdbWatchedKey in partialWatchedKeys
                    ),
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
