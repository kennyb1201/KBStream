package com.kennyb1201.kbstream.ui.sports

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.sports.EspnGameSummary
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.Leader
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.StandingEntry
import com.kennyb1201.kbstream.data.sports.StandingGroup
import com.kennyb1201.kbstream.data.sports.standingsTeam
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.mobile.SportsHubScreenMobile
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.OswaldFamily
import kotlin.math.abs
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/** The gap between the two cards of a grid row. */
private const val GRID_GUTTER_DP = 16

/** Between stacked cards, and the slack a section heading needs on each side. */
private const val CARD_GAP_DP = 12

/**
 * Below this width two cards side by side stop being readable, so the grid
 * falls back to one per row. A 1080p TV reports ~960dp and gets two; a 720p box
 * at 1.5x density reports ~853dp and gets one, rather than two cards squeezed
 * into a third of the screen each.
 */
private const val TWO_COLUMN_MIN_WIDTH_DP = 900

/**
 * The search field's width.
 *
 * Fixed rather than a fraction of the row: the field is one control on a line
 * of its own, and a box stretched to the width of a 55" screen would read as a
 * banner rather than as something to type into.
 */
private const val SEARCH_FIELD_WIDTH_DP = 360

/**
 * How far the sheet's scrim dims the hub behind it.
 *
 * Black at 60%: enough that the plate is the only thing being read, and not so
 * much that the slate behind it disappears - the sheet is the card opened, and
 * the card it came from should still be findable under it.
 */
private const val SHEET_SCRIM_ALPHA = 0.6f

/**
 * Frames the hub's initial focus claim will wait for its tab to be composed.
 *
 * A requester whose node is not attached yet answers false rather than
 * throwing, so the claim is retried frame by frame until it lands (see the
 * entry focus effect). Twelve is the app's usual budget for this - the same
 * count KBStatusMessage and the guide use - and is far longer than the second
 * frame the tab row actually needs.
 */
private const val HUB_ENTRY_FOCUS_ATTEMPTS = 12

/**
 * The sports hub: every league the profile turned on, its games, and the
 * channel in the viewer's own playlist that is carrying each one.
 *
 * Deliberately built out of the app's existing parts - [KBCard] for anything
 * pressable, the theme's own surfaces and type scale, the guide's chip idiom
 * for the tab row - because the hub lives INSIDE Live TV and a screen with its
 * own visual language would read as a different app bolted on. What is new is
 * the content: team marks at a size you can see, scores set in Oswald, and a
 * single line that says when it is on.
 *
 * Playback is not this screen's business. A card hands the feeds the playlist
 * holds for its game - strongest first - to [onPlayChannels], which is the same
 * launch a guide click takes.
 *
 * [initialDetailGameId] / [initialDetailTournamentId] reopen the sheet the
 * player being returned from was launched out of - see [SportsReopenRules] and
 * the reopen effect below. Both are null for every other way into the hub.
 */
@Composable
fun SportsHubScreen(
    viewModel: SportsHubViewModel = viewModel(),
    modifier: Modifier = Modifier,
    // The phone's header arrow; the TV page draws no back control.
    onBack: () -> Unit,
    onPlayChannels: (gameId: String?, tournamentId: String?, List<IptvChannel>) -> Unit =
        { _, _, _ -> },
    initialDetailGameId: String? = null,
    initialDetailTournamentId: String? = null,
) {
    // The phone entry: the same ViewModel, the same rule objects and the same
    // launch callback, laid out for touch. One branch; the TV page below is
    // unchanged (see SportsHubScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        SportsHubScreenMobile(
            viewModel = viewModel,
            modifier = modifier,
            onBack = onBack,
            onPlayChannels = onPlayChannels,
            initialDetailGameId = initialDetailGameId,
            initialDetailTournamentId = initialDetailTournamentId
        )
        return
    }

    val enabled by viewModel.enabledLeagues.collectAsStateWithLifecycle()
    val leagueOrder by viewModel.leagueOrder.collectAsStateWithLifecycle()
    // The loaded sections, ESPN's own order. Ordering is the BODY's business,
    // not the state's: a league is drawn grouped into live / upcoming / final,
    // and a favorites-first sort applied before that partition is thrown away by
    // the partition's own sort - which is why the star only ever led the slate
    // when nothing was grouped. See LeagueBody.
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val selectedPath by viewModel.selectedLeaguePath.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteTeamKeys.collectAsStateWithLifecycle()
    val favoritesSection by viewModel.favoritesSection.collectAsStateWithLifecycle()
    val leagueView by viewModel.view.collectAsStateWithLifecycle()
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    val standingsLoading by viewModel.standingsLoading.collectAsStateWithLifecycle()
    val standingsFailed by viewModel.standingsFailed.collectAsStateWithLifecycle()
    val gameReminders by viewModel.gameReminders.collectAsStateWithLifecycle()
    val lineupStatus by viewModel.lineupStatus.collectAsStateWithLifecycle()
    val matchingDone by viewModel.matchingDone.collectAsStateWithLifecycle()
    // The open sheet's live stats - team stats, win probability, the last play.
    // Null for every game without them, which is why the sheet needs no loading
    // or error state of its own (see the ViewModel's openDetail).
    val detailSummary by viewModel.detailSummary.collectAsStateWithLifecycle()
    // True only once the hub knows it has NO lineup: the difference between
    // "your playlist does not carry this game" and "there is no playlist to
    // compare against". LOADING is deliberately neither - a read still in
    // flight (the paged lineup read takes a moment on a large provider) must
    // not have the cards accuse either the viewer or the hub, so they keep the
    // ordinary sentence until the answer is in.
    val lineupMissing = lineupStatus == LineupStatus.MISSING

    // The viewer's own order, so a rearrangement in the leagues panel moves the
    // tabs with it rather than only the panel.
    val leagues = remember(enabled, leagueOrder) { SportsLeagues.enabled(enabled, leagueOrder) }
    // FAVOURITES leads the row: it is the tab whose contents the viewer chose,
    // so it is the one they are most likely to want, and it is the only place
    // the feature is discoverable from. The leagues panel never lists it - it
    // switches leagues on and off, and this tab is a view over them.
    val tabs = remember(leagues) { listOf(SportsLeagues.FAVORITES) + leagues }

    // Keep a tab selected as the catalog changes: a league that was just
    // switched off must not stay the selection (its tab is gone).
    //
    // Which tab a fresh hub opens on is the viewer's own games when they follow
    // anyone - FAVOURITES is the only tab whose contents they chose, so it is
    // the one they came for - and the first enabled league for everyone else,
    // which is what this did before. `favoriteKeys` is the hub's own follow set
    // (see SportsHubViewModel): it is read from the store when the ViewModel is
    // built, so it is already populated on the first composition and the
    // default cannot race a load and settle on a league by accident.
    //
    // `tabs` is the only key, on purpose. Reading the follow set here rather
    // than keying on it is what keeps this the INITIAL default and nothing
    // more: a star toggled while the hub is open must not yank the viewer off
    // the tab they are reading, and a manual tab switch has already stored a
    // selection, so the guard below skips them entirely.
    LaunchedEffect(tabs) {
        val current = selectedPath
        if (current == null || tabs.none { it.path == current }) {
            if (favoriteKeys.isNotEmpty()) {
                viewModel.selectLeague(SportsLeagues.FAVORITES.path)
            } else {
                leagues.firstOrNull()?.let { viewModel.selectLeague(it.path) }
            }
        }
    }

    // One list state for the league body, owned here rather than inside it, so
    // the D-pad fallback below can scroll the cards even when focus is still up
    // on the tab row.
    //
    // Seeded from the ViewModel's own memory of where it was left: the player
    // is a Screen in this activity, so opening a game disposes this composable
    // and a plain rememberLazyGridState came back at the top of the slate the
    // viewer was halfway down (see SportsHubViewModel.hubScrollIndex).
    val listState = rememberLazyGridState(
        initialFirstVisibleItemIndex = viewModel.hubScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.hubScrollOffset
    )
    val scrollScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Which standings column holds the D-pad, when the body is in its
    // two-column form. Each column scrolls on its own LazyGridState there, so
    // the screen-level [listState] the scroll fallback is built on is composed
    // with by NOTHING in that mode - a Down/Up at a column edge scrolled
    // nothing and the press appeared dead. The focused column reports itself
    // here (see StandingsBody/StandingsColumn) and the fallback rides on it,
    // falling back to [listState] for the one-column body and the games list.
    val standingsColumnState = remember { mutableStateOf<LazyGridState?>(null) }
    // Cleared when the league changes: the columns report themselves through
    // onFocusChanged, which is not guaranteed to fire as one is disposed, so a
    // switch could leave the outgoing league's column object held here - and the
    // scroll fallback then rode a grid that is no longer on screen (a no-op
    // press at a column edge rather than a scroll).
    LaunchedEffect(selectedPath, leagueView) {
        standingsColumnState.value = null
    }

    // A card THAT CANNOT BE PLAYED has no press to give, so a run of them is a
    // run of dead D-pad stops - and the fallback below is what stops a press at
    // the bottom of one such run from appearing dead. (The comment here used to
    // claim those cards were "deliberately NOT focusable", which stopped being
    // true when they were made focusable so a press could open their sheet -
    // see TournamentCard's non-playable branch. What matters to this fallback is
    // only that a press has no target to move to.) That is the "it won't scroll
    // down into the games" the viewer hits on a slate their playlist only half
    // covers.
    //
    // So: Down/Up first gets the platform's own move - which is what a press
    // that HAS a target still does - and only when there is nothing to focus
    // does the press become a scroll.
    val scrollByPage: (FocusDirection) -> Boolean = { direction ->
        val scrollTarget = standingsColumnState.value ?: listState
        val info = scrollTarget.layoutInfo
        val viewport = info.viewportSize.height -
            info.beforeContentPadding - info.afterContentPadding
        val canScroll = if (direction == FocusDirection.Down) {
            scrollTarget.canScrollForward
        } else {
            scrollTarget.canScrollBackward
        }
        if (!canScroll || viewport <= 0) {
            false
        } else {
            val row = info.visibleItemsInfo.lastOrNull()?.size?.height ?: viewport
            val step = sportsScrollStep(rowHeight = row, viewportHeight = viewport)
            scrollScope.launch {
                scrollTarget.animateScrollBy(
                    if (direction == FocusDirection.Down) step.toFloat() else -step.toFloat()
                )
            }
            true
        }
    }

    // ── The initial D-pad landing ────────────────────────────────────
    //
    // Every other browse screen claims focus on entry, and this hub's own key
    // handler answers a DOWN/UP press with moveFocus(direction) ||
    // scrollByPage(direction): with NOTHING focused, the first press used to
    // scroll the list with no focus cue at all, so the viewer's first act on
    // the hub displaced the very slate they were trying to read, and which card
    // ended up focused afterwards was whatever the platform happened to resolve.
    //
    // The selected tab takes it, as it does in the guide: it is the top of the
    // screen, the thing the body below hangs from, and one press from both the
    // search field and the cards.
    //
    // Gated twice. A return from the player that reopens a sheet (both ids
    // non-null) is left alone - that sheet parks focus on its own WATCH button,
    // and a claim here would yank it away a frame later - and the latch keeps
    // this ONE-SHOT, so a league switched on from the panel later cannot pull
    // the viewer off the card they are reading.
    val selectedTabFocus = remember { FocusRequester() }
    var entryFocusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(leagues, selectedPath) {
        if (entryFocusPlaced || leagues.isEmpty()) return@LaunchedEffect
        if (initialDetailGameId != null || initialDetailTournamentId != null) {
            entryFocusPlaced = true
            return@LaunchedEffect
        }
        // The tab is only focusable once the row has composed it, so the
        // request is retried across frames and the RETURN VALUE is the test: an
        // unattached requester answers false rather than throwing, while
        // runCatching's isSuccess only ever means "no exception was thrown" and
        // would read a stranded focus as a landing (see KBStatusMessage).
        var landed = false
        var attempts = 0
        while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
            awaitFrame()
            landed = runCatching { selectedTabFocus.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
        entryFocusPlaced = landed
    }

    var showLeagues by remember { mutableStateOf(false) }

    // ── Search (see [SportsSearchRules]) ─────────────────────────────
    //
    // Screen state, not ViewModel state, and deliberately: searching filters
    // what is already loaded, so nothing here belongs in the data layer, and
    // nothing here can fetch. The query is dropped with the composition, like
    // any other scroll position.
    var searchQuery by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    // Hung on the field to end its editing session when this screen decides a
    // Back belonged to the keyboard rather than to the field (see KBTextField).
    var searchEndEditingSignal by remember { mutableStateOf(0) }

    // The game whose detail sheet is raised. A tap on a card opens this rather
    // than playing it: the sheet carries the matchup, and its Watch button is
    // what plays, through the same channel-match path the tap used to take.
    var detailGame by remember { mutableStateOf<SportsGame?>(null) }

    // The tournament whose detail sheet is raised. A press on a tournament card
    // the playlist cannot carry opens this rather than playing: there is no feed
    // to play, and the sheet is where the reason is stated. A MATCHED card still
    // plays, exactly as it did.
    var detailTournament by remember { mutableStateOf<TournamentEvent?>(null) }

    // Back from the player returns to the sheet its WATCH was pressed in: the
    // Screen the player was launched with carries that game's (or tournament's)
    // id, and the first composition that can see it in the loaded sections raises
    // the sheet. The lookup is [SportsReopenRules]', so "the game has dropped off
    // the board" is a null result - the hub simply renders - rather than an empty
    // sheet or a crash.
    //
    // One-shot: [reopenConsumed] latches on the first hit, so closing the sheet
    // leaves it closed and a later recomposition (or the sections arriving from
    // the 30-second tick) cannot raise it again. A hub reached any other way - a
    // fresh hub, with both ids null - never opens one, and the empty lookup
    // returns without touching [sections] at all.
    var reopenConsumed by remember(initialDetailGameId, initialDetailTournamentId) {
        mutableStateOf(false)
    }
    LaunchedEffect(initialDetailGameId, initialDetailTournamentId, sections) {
        if (reopenConsumed) return@LaunchedEffect
        val game = SportsReopenRules.game(sections, initialDetailGameId)
        val tournament = SportsReopenRules.tournament(sections, initialDetailTournamentId)
        if (game != null || tournament != null) {
            detailGame = game
            detailTournament = tournament
            reopenConsumed = true
        }
    }

    // Any sheet over the hub pauses the section's LIVE beat (see
    // [rememberLivePulse]): the dots are behind that sheet, and a beat nobody
    // can see is exactly the per-frame work the shared clock exists to avoid.
    // Only the animation stops - the data keeps polling underneath, so the
    // scores are as fresh the moment the sheet closes as they were when it
    // opened.
    val sheetOpen = detailGame != null || detailTournament != null

    // The sheet's fetch policy, in one place: opening a game asks the hub for its
    // summary - lazily, and only for a live one - and closing clears it, so the
    // live tick stops refreshing a sheet nobody is looking at.
    LaunchedEffect(detailGame) {
        val open = detailGame
        if (open == null) viewModel.closeDetail() else viewModel.openDetail(open)
    }
    // Leaving the hub leaves the sheet behind: without this the ViewModel would
    // keep re-reading the summary on every live tick for a sheet that is gone.
    //
    // The grid's place and the live tick go with it. This screen really is
    // disposed by the player route, so Back out of a game has to hand its
    // scroll position back through the (activity-scoped) ViewModel, and ESPN
    // must stop being asked for a scoreboard every 30 seconds behind the film
    // the viewer is now watching.
    DisposableEffect(Unit) {
        // Re-armed on entry, not only stopped on exit: a live game may have
        // kicked off while the player was up.
        viewModel.resumeLivePoll()
        onDispose {
            viewModel.closeDetail()
            viewModel.rememberHubScroll(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
            viewModel.pauseLivePoll()
        }
    }

    // The game whose teams are being followed. A long press on a card raises
    // this: the card itself stays one focus stop, and the two teams - the
    // things a viewer actually follows - are chosen inside the dialog.
    var favoriteEditor by remember { mutableStateOf<SportsGame?>(null) }

    // The field's own Back ladder, in the order the spec asks for: the first
    // Back gives up the FIELD (its editing session if it still has one, then its
    // focus), and only a Back with nothing of the field's left to give up clears
    // the query. That ordering is what stops a Back meant for the keyboard from
    // throwing away what the viewer typed.
    //
    // Registered before the overlays below, so an open panel keeps its own Back
    // (the last enabled handler wins): a Back inside the leagues panel closes
    // the panel, as it always did.
    BackHandler(enabled = searchEditing || searchFocused || searchQuery.isNotEmpty()) {
        when {
            // On Fire OS the IME closes and hands the press on, so the session
            // can still be open when the press arrives here: end it and keep the
            // query - this press was the keyboard's.
            searchEditing -> searchEndEditingSignal++
            // The field has focus and no session: this Back is the one that gives
            // up the field.
            searchFocused -> focusManager.clearFocus()
            // Nothing of the field's left: the query goes, and the hub is back on
            // the exact view it had before - no re-fetch, see
            // [SportsSearchRules].
            else -> searchQuery = ""
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        else -> return@onKeyEvent false
                    }
                    // Consumed either way: a move already happened on the first
                    // branch, and the scroll is this press's answer on the
                    // second - letting the platform handle it too would move
                    // focus AND scroll.
                    when (direction) {
                        FocusDirection.Down, FocusDirection.Up ->
                            focusManager.moveFocus(direction) || scrollByPage(direction)

                        // Left/right have no scroll fallback. The standings' left
                        // column has no table to its left, so a Left press at its
                        // edge rises to the row above (the tab row) rather than
                        // leaving focus stranded at the column edge; anywhere
                        // else the platform's own move stands.
                        FocusDirection.Left ->
                            focusManager.moveFocus(direction) ||
                                (leagueView == LeagueView.STANDINGS &&
                                    focusManager.moveFocus(FocusDirection.Up))

                        FocusDirection.Right -> focusManager.moveFocus(direction)

                        // The mapping above only ever produces the four handled
                        // directions; this keeps the `when` an expression.
                        else -> false
                    }
                }
        ) {
            SportsHeader(
                onLeaguesClick = { showLeagues = true },
                onRefreshClick = { viewModel.refresh() },
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (leagues.isEmpty()) {
                KBStatusMessage(
                    title = "No leagues enabled",
                    message = "Turn on a league to see its games and the channel carrying them.",
                    icon = KB_STATUS_ICON_EMPTY,
                    actionLabel = "CHOOSE LEAGUES",
                    onRetry = { showLeagues = true },
                    modifier = Modifier.fillMaxSize()
                )
                return@Column
            }

            LeagueTabRow(
                leagues = tabs,
                selectedPath = selectedPath,
                selectedFocusRequester = selectedTabFocus,
                onSelect = { path ->
                    // A tab is a slate of its own, and one list state is shared
                    // by all of them: without this, switching NFL to NHL opened
                    // the new league at the old one's offset.
                    if (path != selectedPath) {
                        viewModel.forgetHubScroll()
                        scrollScope.launch { listState.scrollToItem(0) }
                    }
                    viewModel.selectLeague(path)
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // One search field, below the tabs: it filters the cards under it
            // rather than opening a surface of its own, so what it searches
            // stays visible behind it.
            SportsSearchField(
                query = searchQuery,
                onQueryChanged = { searchQuery = it },
                onFocusChanged = { searchFocused = it },
                onEditingChanged = { searchEditing = it },
                endEditingSignal = searchEndEditingSignal,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Said once, at the top, when the hub has no lineup at all: every
            // card below would otherwise repeat the same sentence twenty times
            // and blame the games for it.
            if (lineupMissing) {
                LineupMissingNotice(onRetry = viewModel::retryLineup)
                Spacer(modifier = Modifier.height(16.dp))
            }

            val favoritesTab = selectedPath == SportsLeagues.FAVORITES.path
            val selectedLeague = leagues.firstOrNull { it.path == selectedPath }
            val section = if (favoritesTab) {
                favoritesSection
            } else {
                sections.firstOrNull { it.league.path == selectedPath }
            }

            // ── Search results ──────────────────────────────────────
            //
            // One behavior, as the spec asks: the favourites tab searches the
            // followed list, and every other tab searches ALL of the enabled
            // leagues - "which tab was that game on?" is the question this field
            // exists to save the viewer from answering by hand.
            //
            // It reads the loaded sections and nothing else: a league the hub
            // has not fetched yet cannot match, and is never fetched on the
            // field's account (see [SportsSearchRules]).
            if (SportsSearchRules.isActive(searchQuery)) {
                val searchSections = if (favoritesTab) listOf(favoritesSection) else sections
                val searchLeague = if (favoritesTab) {
                    SportsLeagues.FAVORITES
                } else {
                    SportsSearchRules.RESULTS_LEAGUE
                }
                val results = SportsSearchRules.results(searchSections, searchQuery, searchLeague)
                if (results == null) {
                    KBStatusMessage(
                        title = "No games found",
                        message = "Nothing matching \"${searchQuery.trim()}\" is on the loaded schedule.",
                        icon = KB_STATUS_ICON_EMPTY,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SearchResultsLine(
                        games = results.games.size,
                        events = results.tournaments.size,
                        leagues = SportsSearchRules.leaguesWithHits(searchSections, searchQuery)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LeagueBody(
                        pulseEnabled = !sheetOpen,
                        section = results,
                        matches = matches,
                        favoriteKeys = favoriteKeys,
                        listState = listState,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone,
                        onOpenDetail = { detailGame = it },
                        onOpenTournamentDetail = { detailTournament = it },
                        onPlayChannels = onPlayChannels,
                        onEditFavorites = { favoriteEditor = it },
                    )
                }
                return@Column
            }

            // The standings toggle exists only where a table does, and only
            // outside the favourites tab - which is a view over the leagues,
            // not one of them. A league with no table is never offered a view
            // that could only be empty.
            val standingsAvailable = !favoritesTab && selectedLeague?.hasStandings == true
            if (standingsAvailable) {
                LeagueViewToggle(selected = leagueView, onSelect = viewModel::setView)
                Spacer(modifier = Modifier.height(12.dp))
            }

            val standingsLeague = selectedLeague
                .takeIf { standingsAvailable && leagueView == LeagueView.STANDINGS }
            if (standingsLeague != null) {
                StandingsBody(
                    league = standingsLeague,
                    groups = standings[standingsLeague.path],
                    loading = standingsLeague.path in standingsLoading,
                    failed = standingsLeague.path in standingsFailed,
                    listState = listState,
                    // Following a team from the standings keys under the same
                    // favourite the scoreboard uses, so the row has a real
                    // action instead of being a D-pad stop that does nothing.
                    favoriteKeys = favoriteKeys,
                    onToggleTeam = { team, on -> viewModel.setTeamFavorite(team, on) },
                    // Two-column standings: whichever column the D-pad is on
                    // becomes the state the scroll fallback rides, so a press
                    // at a column edge scrolls THAT column instead of dying.
                    onColumnFocusChanged = { state, hasFocus ->
                        if (hasFocus) {
                            standingsColumnState.value = state
                        } else if (standingsColumnState.value === state) {
                            standingsColumnState.value = null
                        }
                    },
                    onRetry = viewModel::retryStandings,
                )
                return@Column
            }

            when {
                section == null -> KBStatusMessage(
                    message = "Loading scores…",
                    loading = true,
                    modifier = Modifier.fillMaxSize()
                )

                // The favourites tab has two empty states of its own, and they
                // are different questions: "you have not picked anyone" is a
                // how-to, and "nobody you follow is on" is a schedule.
                favoritesTab && favoriteKeys.isEmpty() -> KBStatusMessage(
                    title = "No favorite teams yet",
                    message = "Long-press a game on any league tab and pick the teams you follow.",
                    icon = KB_STATUS_ICON_EMPTY,
                    modifier = Modifier.fillMaxSize()
                )

                favoritesTab -> when {
                    section.games.isNotEmpty() -> LeagueBody(
                        pulseEnabled = !sheetOpen,
                        section = section,
                        matches = matches,
                        favoriteKeys = favoriteKeys,
                        listState = listState,
                        onOpenDetail = { detailGame = it },
                        onOpenTournamentDetail = { detailTournament = it },
                        onPlayChannels = onPlayChannels,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone,
                        onEditFavorites = { favoriteEditor = it },
                    )

                    // A failed ESPN fetch used to read as "your teams aren't
                    // playing": the favourites section is built in the
                    // ViewModel by flattening the league sections, and that
                    // aggregate carries no failure flag of its own - so the
                    // error state further down (which is keyed on the LEAGUE
                    // section) could never apply to this tab. The sections it
                    // was built from are right here, so ask them.
                    sections.any { it.failed } -> KBStatusMessage(
                        title = "Couldn't reach ESPN",
                        message = "Scores are unavailable right now.",
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> KBStatusMessage(
                        title = "Nothing on",
                        message = "None of your teams are playing right now.",
                        icon = KB_STATUS_ICON_EMPTY,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // A failed fetch with NOTHING to show is the error state. A
                // failed fetch over cards the hub already has keeps them up -
                // a stale scoreboard beats an empty screen while ESPN is
                // unreachable.
                section.failed && section.games.isEmpty() && section.tournaments.isEmpty() ->
                    KBStatusMessage(
                        title = "Couldn't reach ESPN",
                        message = "Scores for ${section.league.label} are unavailable right now.",
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    )

                section.games.isEmpty() && section.tournaments.isEmpty() -> KBStatusMessage(
                    title = "Nothing on",
                    message = "No ${section.league.label} games on the schedule right now.",
                    icon = KB_STATUS_ICON_EMPTY,
                    modifier = Modifier.fillMaxSize()
                )

                else -> LeagueBody(
                    pulseEnabled = !sheetOpen,
                    section = section,
                    matches = matches,
                    favoriteKeys = favoriteKeys,
                    listState = listState,
                    lineupMissing = lineupMissing,
                    matchingDone = matchingDone,
                    onOpenDetail = { detailGame = it },
                    onOpenTournamentDetail = { detailTournament = it },
                    onPlayChannels = onPlayChannels,
                    onEditFavorites = { favoriteEditor = it },
                )
            }
        }

        if (isLoading && sections.isEmpty()) {
            KBStatusMessage(
                title = "Loading sports",
                message = "Fetching today's games…",
                loading = true,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showLeagues) {
            // The panel is the one place league visibility is edited. Back
            // closes it before it can leave the hub: an overlay that swallowed
            // Back into a navigation would read as a lost press.
            BackHandler { showLeagues = false }
            LeagueTogglesPanel(
                enabled = enabled,
                order = leagueOrder,
                reminders = gameReminders,
                onToggle = { path, on -> viewModel.setLeagueEnabled(path, on) },
                onToggleReminders = viewModel::setGameReminders,
                onMove = viewModel::moveLeague,
                onClearMemory = viewModel::clearChannelMemory,
                onClose = { showLeagues = false },
            )
        }

        favoriteEditor?.let { game ->
            BackHandler { favoriteEditor = null }
            FavoriteTeamsDialog(
                game = game,
                favoriteKeys = favoriteKeys,
                onToggle = { team, on -> viewModel.setTeamFavorite(team, on) },
                onClose = { favoriteEditor = null },
            )
        }

        detailGame?.let { game ->
            // Back dismisses the sheet before it can leave the hub; focus comes
            // back to the card that raised it, because the sheet is its own
            // dialog window and closing it returns to the tree behind.
            BackHandler { detailGame = null }
            GameDetailSheet(
                game = game,
                // Every feed the playlist holds for this game, strongest first;
                // the sheet's Watch button is the head and the backups are its
                // own rows. Empty means the card's line was telling the truth.
                channels = matches[game.id].orEmpty(),
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                // The live stats the hub read for this game, if any - the sheet
                // still fetches nothing itself (see GameDetailSheet).
                summary = detailSummary,
                // Picking a feed OTHER than the matched head is the viewer
                // correcting the matcher; record it so the next game with
                // either team opens there. See [SportsChannelMemory].
                onManualPick = { chosen -> viewModel.rememberChannel(game, chosen.id) },
                // The launch carries this game with it, so a Back from the
                // player lands on this sheet again rather than on the bare hub.
                // See Screen.Sports / the reopen effect above.
                onPlay = { feeds ->
                    detailGame = null
                    onPlayChannels(game.id, null, feeds)
                },
                onClose = { detailGame = null },
            )
        }

        detailTournament?.let { event ->
            // Back dismisses the sheet before it can leave the hub; focus comes
            // back to the card that raised it, because the sheet is its own
            // dialog window and closing it returns to the tree behind - the same
            // mechanism the game sheet has.
            BackHandler { detailTournament = null }
            TournamentDetailSheet(
                event = event,
                // Every feed the playlist holds for this tournament, strongest
                // first. Usually empty - the sheet is raised by cards with none -
                // but the match can land while it is open, and then the sheet
                // offers the same Watch the card behind it now would.
                channels = matches[event.id].orEmpty(),
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                // A backup tap is the viewer correcting the matcher, so record
                // it against the tournament's own key; the game sheet does the
                // same for a game's two teams. See [SportsChannelMemory].
                onManualPick = { chosen -> viewModel.rememberTournamentChannel(event, chosen.id) },
                // Same one-shot return for a tournament sheet.
                onPlay = { feeds ->
                    detailTournament = null
                    onPlayChannels(null, event.id, feeds)
                },
                onClose = { detailTournament = null },
            )
        }
    }
}

// ── Header and tabs ─────────────────────────────────────────────────────

@Composable
private fun SportsHeader(
    onLeaguesClick: () -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            KBPageTitle(text = "SPORTS HUB")
            Text(
                text = "Live scores • tap to watch • long-press a game to follow its teams",
                style = MaterialTheme.typography.bodyMedium,
                color = KBTextLo,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KBButton(label = "REFRESH", onClick = onRefreshClick)
            KBButton(label = "LEAGUES", onClick = onLeaguesClick)
        }
    }
}

@Composable
private fun LeagueTabRow(
    leagues: List<SportsLeague>,
    selectedPath: String?,
    onSelect: (String) -> Unit,
    selectedFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(horizontal = KBFocusChipInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .offset(x = -KBFocusChipInset)
            .focusGroup()
    ) {
        itemsIndexed(leagues, key = { _, league -> league.path }) { _, league ->
            val selected = league.path == selectedPath
            LeagueTab(
                label = league.label,
                selected = selected,
                onClick = { onSelect(league.path) },
                // The selected tab is the hub's initial D-pad landing (see
                // SportsHubScreen), so it is the one that carries the
                // requester. Exactly one tab is selected at a time.
                modifier = if (selected && selectedFocusRequester != null) {
                    Modifier.focusRequester(selectedFocusRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * The hub's search field.
 *
 * The app's one text field ([KBTextField]) rather than a new input: it already
 * carries the TV keyboard and D-pad contract every other screen relies on (OK
 * starts editing, Up/Down leaves the field instead of being swallowed by the
 * leanback IME, Back ends the session without eating the screen's own Back).
 * Two of its switches matter here:
 *
 *  - [KBTextField.openKeyboardOnFocus] = false, so merely walking the D-pad over
 *    the field does not throw a full-screen keyboard over the scores: the field
 *    takes focus silently and OK opens the keyboard - "focusable, opens the TV
 *    keyboard on select".
 *  - [KBTextField.closeKeyboardOnBlur] = true, because the results sit BELOW the
 *    field in the same focus walk: an always-editable field re-opens the
 *    keyboard every time the D-pad comes back up to it, which is how a result
 *    list ends up reachable only by pressing Back.
 */
@Composable
private fun SportsSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    endEditingSignal: Int,
    modifier: Modifier = Modifier,
) {
    KBTextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = "Search teams or events…",
        modifier = modifier.width(SEARCH_FIELD_WIDTH_DP.dp),
        openKeyboardOnFocus = false,
        closeKeyboardOnBlur = true,
        onFocusChanged = onFocusChanged,
        onEditingChanged = onEditingChanged,
        endEditingSignal = endEditingSignal,
    )
}

/**
 * "12 RESULTS · 4 LEAGUES", above the cards it describes.
 *
 * The league count is the part that cannot be read off the list: three hits can
 * be one league's slate or one game carried on three tabs, and the second figure
 * is what says which. Counts only - the cards below say what they are.
 */
@Composable
private fun SearchResultsLine(games: Int, events: Int, leagues: Int) {
    val total = games + events
    Text(
        text = "$total RESULT${if (total == 1) "" else "S"} · " +
            "$leagues LEAGUE${if (leagues == 1) "" else "S"}",
        style = MaterialTheme.typography.labelSmall,
        color = KBTextLo
    )
}

@Composable
private fun LeagueTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KBCard(
        onClick = onClick,
        modifier = modifier,
        focusedScale = KBFocusChip,
        shape = KBShapePill,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) KBVoid else KBTextHi,
            modifier = Modifier
                .background(
                    if (selected) KBAccent else KBSurfaceRaised,
                    KBShapePill
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

// ── The card lists' focus landing ───────────────────────────────────────

/**
 * A hub list of cards: the standings' one-column scroll container, with the
 * focus landing its cards need and nothing else. (The league feed's [HubGrid]
 * is the same wrapper with the feed's own column count.)
 *
 * The landing is the point. A lazy list clips its content to its own bounds,
 * and Compose's default bring-into-view scrolls the focused node the least
 * distance that makes its LAYOUT rectangle visible - flush with the physical
 * edge of the viewport. A game card's focus cue is not inside that rectangle:
 * it grows by [KBFocusRow] (about 1% of its own height per side) and carries a
 * 12dp glow shadow drawn outside its bounds, so a card that focus had to scroll
 * to landed with its 2dp accent border and its glow sheared off flat along the
 * bottom of the list. The FIRST card of a section never showed it - focus
 * arrives from the search field above with that card already fully visible, so
 * nothing scrolls - and every card after it, which the D-pad does have to
 * scroll to, did.
 *
 * `contentPadding` cannot fix that: it sits inside the clip, and the default
 * spec is handed the scrollable's whole viewport and counts the focused
 * rectangle as visible the moment it fits in it, so padding never changes where
 * an item focus lands on comes to rest. The margin has to be in the spec
 * (see [SportsCardBringIntoViewSpec]) - the same switch Home's rail column and
 * the detail screen throw.
 *
 * It is supplied around the list rather than on the screen, so the tab row
 * above - a horizontal chip row whose own [KBFocusChipInset] landing must not
 * be re-tuned - is left alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubList(
    listState: LazyGridState,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit
) {
    val density = LocalDensity.current
    val bringIntoViewSpec = remember(density) {
        SportsCardBringIntoViewSpec(insetPx = with(density) { KBFocusRowInset.toPx() })
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        LazyVerticalGrid(
            // One column: the standings rows are full-width items, exactly as
            // they were in the lazy column this replaced.
            columns = GridCells.Fixed(1),
            state = listState,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
            // The list's own slack AFTER the last card (the 24dp it always had),
            // written as the landing margin plus the breathing room on top of it
            // because the two are coupled: the spec can only bring the LAST row
            // to its margin if the list is able to scroll that far past it.
            contentPadding = PaddingValues(bottom = KBFocusRowInset + 8.dp),
            content = content
        )
    }
}

/**
 * The league feed's grid: the same focus landing as [HubList], for a feed whose
 * items must be the games themselves.
 *
 * The feed is a grid rather than a lazy column so each game can be its OWN
 * item, keyed by the game's stable id. The old shape chunked a section's games
 * into rows and keyed each row by its members ("row-" + the ids joined); a
 * row's identity was therefore derived from which games happened to be grouped
 * in it, and the 30s poll - which repartitions live/upcoming/final whenever a
 * game changes state - rewrote the key of every row at and after the change.
 * LazyColumn anchors its scroll and its focus to item keys, so a rewritten row
 * key disposed the focused card's row and dropped focus with nothing to restore
 * it to. One item per game ends that: a game that changes section moves, and
 * keeps its key and the viewer's focus while it moves.
 *
 * Headers, tournaments and live games span the full line with a
 * [GridItemSpan], so a heading still reads across the whole width, a
 * leaderboard still gets the width it wants, and a live card still stands
 * alone. This is what the old comment here claimed a grid "cannot" do; a
 * full-span item is exactly how it does it without nesting two scrollers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubGrid(
    listState: LazyGridState,
    columns: Int,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit
) {
    val density = LocalDensity.current
    val bringIntoViewSpec = remember(density) {
        SportsCardBringIntoViewSpec(insetPx = with(density) { KBFocusRowInset.toPx() })
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = listState,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp),
            // The same slack past the last card the list keeps, for the same
            // reason: the landing margin needs somewhere to land.
            contentPadding = PaddingValues(bottom = KBFocusRowInset + 8.dp),
            content = content
        )
    }
}

/**
 * Focus scrolling that keeps [KBFocusRowInset] of room between the focused card
 * and the edge of the viewport it is being scrolled to; see [HubList] for why
 * the default landing is not enough.
 *
 * The rule is the default one with a margin at each edge, for a card that has
 * to be brought IN: scroll the child only as far as it takes to clear that
 * margin, and not at all when it already has. Landing ON the margin rather than
 * on a fixed line is what keeps it steady - the distance depends on the focused
 * card's own rectangle and nothing else, so every card lands the same way and
 * the scroll cannot bounce mid-flight.
 *
 * A card that is ALREADY inside the viewport is the other page of the same
 * rule, and it answers zero: there is no landing to make. It is the page a
 * RE-REQUEST lands on, and re-requests are what a LIVE card produces, because
 * it remeasures on every 30s poll.
 *
 * The two pages are drawn here, in the one spec a scroll container reads,
 * rather than in the card they are about: `LocalBringIntoViewSpec` is consumed
 * by the scrolling surface itself (Compose attaches its bring-into-view node
 * where `Modifier.scrollable` is), and a lazy list's items are subcompositions
 * of that list - so a spec provided around a card never reaches the node that
 * calculates the scroll, whatever it says.
 *
 * `internal`, like the Home rail's own landing beside it, because what it
 * computes is arithmetic a contract test asserts exactly - with no device and
 * no D-pad.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class SportsCardBringIntoViewSpec(private val insetPx: Float) : BringIntoViewSpec {

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        // A margin can never eat the viewport: a card taller than the room the
        // two of them leave still lands on the nearest edge, rather than being
        // scrolled to somewhere it could not be seen at all.
        val margin = insetPx.coerceAtMost(containerSize / 3f)
        // The rect arrives as the focused node's leading edge plus its extent -
        // the card that asked to be brought into view, which is also the thing
        // carrying the cue.
        val trailingEdge = offset + abs(size)
        // Already inside the viewport: nothing to bring into view, and this is
        // the page a RE-REQUEST lands on. Bring-into-view is asked again every
        // time the focused card remeasures, and a LIVE card remeasures on the
        // 30s poll. With the margin rule alone the answer there is not zero - a
        // card resting inside the margin still has a distance to travel to it,
        // worked out from the card's own measured rectangle - so a poll that
        // shifted that rectangle by any amount scrolled the card by the same
        // amount, and the focused card rode it up and down. The unfocused cards
        // beside it never asked to be brought into view, which is why only one
        // card moved. A card that is off the viewport is landed exactly as it
        // always was: the margin rule below still decides that.
        if (offset >= 0f && trailingEdge <= containerSize) return 0f
        return when {
            // Past the bottom margin: scroll forward just far enough to clear it.
            trailingEdge > containerSize - margin -> trailingEdge - (containerSize - margin)
            // Above the top one: back off by the same amount.
            offset < margin -> offset - margin
            // Already clear of both: no scroll at all, which is also what keeps
            // the spring quiet as focus moves around the visible part of a row.
            else -> 0f
        }
    }
}

// ── A league's cards ────────────────────────────────────────────────────

@Composable
private fun LeagueBody(
    section: LeagueSection,
    /**
     * Whether the section's LIVE beat is running. False while a sheet is over
     * the hub; the data is untouched either way (see [rememberLivePulse]).
     */
    pulseEnabled: Boolean = true,
    matches: Map<String, List<IptvChannel>>,
    favoriteKeys: Set<String>,
    listState: LazyGridState,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onOpenTournamentDetail: (TournamentEvent) -> Unit,
    onPlayChannels: (gameId: String?, tournamentId: String?, List<IptvChannel>) -> Unit,
    onEditFavorites: (SportsGame) -> Unit,
) {
    // The viewer's own games lead each group, everything else keeping the order
    // the group itself asks for. Applied AFTER the partition and after the date
    // sort, because both reorder: sorting the section first (which the hub used
    // to do in the ViewModel) left a followed team's game wherever its kick-off
    // happened to fall, so the star was only ever at the top by luck. The pure
    // rule is stable, so favorites keep the date/feed order among themselves and
    // the rest keep it among themselves - see [SportsGameOrder].
    // Remembered, not recomputed per recomposition: the hub recomposes on every
    // live pulse and on every focus move, and three filters plus three stable
    // sorts over a league's card list is real work for an answer that only
    // changes when the poll or the favorites do (audit runner-up). `section` is
    // a fresh instance each poll, so the inputs, not the instance, are the key.
    val live = remember(section.games, favoriteKeys) {
        SportsGameOrder.favoritesFirst(
            section.games.filter { it.state == GameState.LIVE },
            favoriteKeys
        )
    }
    val upcoming = remember(section.games, favoriteKeys) {
        SportsGameOrder.favoritesFirst(
            section.games.filter { it.state == GameState.UPCOMING }.sortedBy { it.dateMs },
            favoriteKeys
        )
    }
    val final = remember(section.games, favoriteKeys) {
        SportsGameOrder.favoritesFirst(
            section.games.filter { it.state == GameState.FINAL }.sortedByDescending { it.dateMs },
            favoriteKeys
        )
    }

    // The league's identity for the two scroll rules below. `section` is a new
    // instance on every 30-second poll, so it can never be a key; and both
    // pseudo-sections the hub draws through this body carry a stable path of
    // their own (SportsLeagues.FAVORITES, SportsSearchRules.RESULTS_LEAGUE), so
    // neither can look like "the same league" as a real one.
    val leaguePath = section.league.path

    // Scroll to the live games when they first arrive. The section is drawn
    // before the ESPN poll returns, so its top is UPCOMING; when the live
    // games land a moment later they insert ABOVE what the viewer is looking
    // at, the list holds the position it had, and the LIVE NOW block sits off
    // the top of the screen - the hub reads as having no live games at all
    // ("the upcoming games show than i have to scroll up to see the live
    // games", 2026-10-10).
    //
    // Only the arrival is answered, and only while the viewer has not moved:
    // yanking the scroll out from under someone already browsing upcoming is
    // worse than missing the live block.
    //
    // "Has not moved" cannot be asked of the list once the games have landed.
    // The grid anchors its scroll position to the first visible item's KEY,
    // so the rows that insert above push firstVisibleItemIndex up (0 -> n)
    // with no input from the viewer, and this effect - launched with the
    // arrival - already sees that anchored index. A guard of
    // `firstVisibleItemIndex == 0` would therefore fail on the very insert it
    // exists to answer. So the top is sampled BEFORE the arrival instead:
    // while the section has no live games this remembers whether the list is
    // still sitting at the top, and the sampler is gone - its answer latched -
    // by the time the rows arrive, so their insertion cannot rewrite it. A
    // viewer's own scroll does rewrite it, which is the one thing the guard is
    // for. Starting false ("nothing has said the list is at the top") also
    // keeps a restored scroll position safe: a body composed with live games
    // already in hand is never scrolled.
    //
    // Keyed on the league, though, and the sampler below is keyed with it: the
    // verdict belongs to one league's list. Unkeyed, the sample survived a tab
    // switch - the guard is at the same call site for every league drawn by the
    // `else` branch - and a new league that already had live games never ran the
    // sampler at all, so the guard kept the last league's "the viewer scrolled"
    // verdict forever. The sampler HAS to restart with the guard rather than
    // only the guard being replaced: a running collector writes through the
    // state instance it captured, so leaving it on `listState` alone would have
    // it updating a state the new composition no longer reads.
    var atTopBeforeLive by remember(leaguePath) { mutableStateOf(false) }
    if (live.isEmpty()) {
        LaunchedEffect(listState, leaguePath) {
            snapshotFlow {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            }.collect { atTopBeforeLive = it }
        }
    }
    LaunchedEffect(live.isNotEmpty()) {
        if (live.isNotEmpty() && atTopBeforeLive) {
            listState.scrollToItem(0)
        }
    }

    // ── Entering a league starts at its top ─────────────────────────
    //
    // One list state is shared by every tab (it is owned by the hub above), so
    // the index the viewer left one league at is carried into the next one
    // wholesale - and it is an index into a different list. A league entered
    // here therefore starts at its own top: the LIVE NOW block and the games
    // the tab was opened for, rather than some offset into its upcoming games.
    //
    // The null initial is load-bearing, but it is not a licence to skip the
    // scroll outright. A FRESH hub composition - Back from the player disposes
    // this screen - begins with lastLeaguePath null, and there the restore from
    // hubScrollIndex/hubScrollOffset is honoured only when the league is NOT
    // live: the remembered index describes an earlier visit, so when games are
    // live now, "where I was reading" is stale and live outranks it (field,
    // 2026-10-18: return to the hub on a live league and the LIVE NOW block
    // sits above the viewport, with the stale index winning over the games in
    // hand). Neither the sampler nor the arrival effect can repair that - the
    // games were already present on the first composition, so there is no
    // empty-to-non-empty transition to answer.
    //
    // Entry with no live games still restores exactly as before (return from a
    // game mid-list, nothing live -> untouched), and a switch always starts at
    // the new league's top.
    //
    // Keyed on the path and not on `section`, which is a new instance on every
    // 30-second poll and would yank the scroll mid-browse.
    //
    // Unconditional, not only when live games are present: the inherited index
    // came from another league's list, so it is never meaningful in this one.
    //
    // The arrival sampler above is deliberately untouched: it governs a game
    // going live while the viewer is already IN this league, where yanking the
    // scroll out from under a browsing viewer is still wrong. It now always
    // samples a list this effect has put at the top, so its answer is trivially
    // correct for the arrival it exists for.
    var lastLeaguePath by remember { mutableStateOf<String?>(null) }
    // Where the D-pad was, and where it has to land after a switch. A switch
    // replaces every item key in the grid (they are the games' own ids), so the
    // focused card's node is disposed and its focus goes with it - and a focus
    // left pointing at a card the list has just scrolled away from is the TV
    // way this goes wrong. [firstCardFocus] is attached to whichever card leads
    // the new league, so the cue lands on a card the viewer can see.
    val firstCardFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(leaguePath, listState) {
        val switched = lastLeaguePath != null && lastLeaguePath != leaguePath
        // Fresh entry with live games in hand: live outranks the restored
        // scroll. The restore is the ViewModel's memory of an earlier visit;
        // when the league is live now, "where I was reading" is stale.
        val freshWithLive = lastLeaguePath == null && live.isNotEmpty()
        lastLeaguePath = leaguePath
        if (!switched && !freshWithLive) return@LaunchedEffect
        // Read BEFORE the scroll and not after: a tab press comes from the tab
        // row, so that flag is false there and the cards are left alone - and
        // reading it here keeps the answer from depending on how much the
        // scroll itself suspended for. A fresh composition never holds grid
        // focus yet, so the repair no-ops there through this same gate.
        val repairFocus = gridHasFocus
        listState.scrollToItem(0)
        if (!repairFocus) return@LaunchedEffect
        // The first card is a freshly composed item, so it is not attached
        // until it has been laid out: retry across frames and treat the RETURN
        // VALUE as the test (an unattached requester answers false rather than
        // throwing - see the hub entry focus above).
        var landed = false
        var attempts = 0
        while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
            awaitFrame()
            landed = runCatching { firstCardFocus.requestFocus() }.getOrDefault(false)
            attempts++
        }
    }

    // Two cards to a row unless the display cannot hold them side by side.
    val gameColumns = if (
        LocalConfiguration.current.screenWidthDp >= TWO_COLUMN_MIN_WIDTH_DP
    ) 2 else 1

    // ONE pulse for the whole league, shared by every live dot it draws. Each
    // live card used to run its own infinite transition - the same beat, but N
    // animation clocks, each recomposing its own card on its own frame.
    val livePulse = rememberLivePulse(pulseEnabled = pulseEnabled)

    // Which group draws the league's first card, so the one requester can be
    // attached where a switch would actually need it. The grid's own order is
    // the order these are tested in.
    val liveLeads = live.isNotEmpty()
    val tournamentsLead = !liveLeads && section.tournaments.isNotEmpty()
    val upcomingLeads = !liveLeads && !tournamentsLead && upcoming.isNotEmpty()
    val finalLeads = !liveLeads && !tournamentsLead && !upcomingLeads && final.isNotEmpty()

    HubGrid(
        listState = listState,
        columns = gameColumns,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { gridHasFocus = it.hasFocus }
    ) {
        if (live.isNotEmpty()) {
            item(key = "header-live", span = { GridItemSpan(maxLineSpan) }) {
                SportsSectionHeader(title = "LIVE NOW", accent = true, count = live.size)
            }
            // Live games keep a row to themselves: the clock, the badge and the
            // score are why they are at the top, and half a row cannot carry
            // them. In the grid that is a full-line span, not a column count.
            gameItems(
                games = live,
                fullWidth = true,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites,
                focusRequester = firstCardFocus
            )
        }

        // Tournaments (golf, F1) are their own events, not head-to-head games,
        // and they sort with whatever is on now. A leaderboard wants the width,
        // so they span the whole line whatever the display.
        if (section.tournaments.isNotEmpty()) {
            item(key = "header-tournaments", span = { GridItemSpan(maxLineSpan) }) {
                SportsSectionHeader(
                    title = "TOURNAMENTS",
                    accent = section.tournaments.any { it.state == GameState.LIVE },
                    count = section.tournaments.size
                )
            }
            items(
                items = section.tournaments,
                key = { it.id },
                span = { GridItemSpan(maxLineSpan) }
            ) { event ->
                TournamentCard(
                    event = event,
                    channels = matches[event.id].orEmpty(),
                    livePulse = livePulse,
                    lineupMissing = lineupMissing,
                    matchingDone = matchingDone,
                    onPlayChannels = onPlayChannels,
                    onOpenDetail = onOpenTournamentDetail,
                    modifier = if (tournamentsLead && event.id == section.tournaments.first().id) {
                        Modifier.focusRequester(firstCardFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }

        if (upcoming.isNotEmpty()) {
            item(key = "header-upcoming", span = { GridItemSpan(maxLineSpan) }) {
                SportsSectionHeader(title = "UPCOMING", count = upcoming.size)
            }
            gameItems(
                games = upcoming,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites,
                focusRequester = firstCardFocus.takeIf { upcomingLeads }
            )
        }

        if (final.isNotEmpty()) {
            item(key = "header-final", span = { GridItemSpan(maxLineSpan) }) {
                SportsSectionHeader(title = "FINAL", count = final.size)
            }
            gameItems(
                games = final,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites,
                focusRequester = firstCardFocus.takeIf { finalLeads }
            )
        }
    }
}

/**
 * One game card per grid item, keyed by the GAME's own id.
 *
 * The key is the whole fix. A game's identity no longer depends on which
 * section it is in or which cards sit beside it, so the 30s poll that moves it
 * between live, upcoming and final - or inserts a new game ahead of it in the
 * date sort - changes only where the item appears, never its key. LazyColumn /
 * LazyVerticalGrid anchor focus and scroll to item keys, so the focused card
 * stays composed and keeps focus through the move; the old row keys, built from
 * the row's members, were rewritten by exactly that repartition.
 *
 * [fullWidth] spans the item across the whole line, for the LIVE section's
 * cards and nothing else: the clock, the badge and the score want the width.
 * Upcoming and final cards take a cell each, so a short last row still holds
 * cards the same width as a full one - a grid cell never stretches to fill the
 * leftover space the way a weighted row did.
 *
 * [focusRequester] is handed to the FIRST card only, and only by the group that
 * leads the league (see [LeagueBody]): it is where the D-pad lands after a
 * league switch, so it belongs on the card at the top of the grid rather than
 * on one of the group's own.
 */
private fun LazyGridScope.gameItems(
    games: List<SportsGame>,
    fullWidth: Boolean = false,
    matches: Map<String, List<IptvChannel>>,
    livePulse: State<Float>,
    favoriteKeys: Set<String>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onEditFavorites: (SportsGame) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val firstId = games.firstOrNull()?.id
    items(
        items = games,
        key = { it.id },
        span = if (fullWidth) {
            { GridItemSpan(maxLineSpan) }
        } else {
            null
        }
    ) { game ->
        GameCard(
            game = game,
            channel = matches.primaryChannel(game.id),
            livePulse = livePulse,
            favoriteKeys = favoriteKeys,
            lineupMissing = lineupMissing,
            matchingDone = matchingDone,
            onOpenDetail = onOpenDetail,
            onEditFavorites = { onEditFavorites(game) },
            modifier = if (focusRequester != null && game.id == firstId) {
                Modifier.fillMaxWidth().focusRequester(focusRequester)
            } else {
                Modifier.fillMaxWidth()
            }
        )
    }
}

@Composable
private fun SportsSectionHeader(
    title: String,
    accent: Boolean = false,
    count: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // 12dp of the gap above comes from the list's own spacing, so the
        // heading sits 24dp below the section before it and 12dp above the
        // first card - the two gaps used to be within a couple of dp of each
        // other, which is why a heading read as part of the card under it.
        modifier = Modifier.padding(top = CARD_GAP_DP.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) KBAccent else KBTextLo
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = KBTextLo,
                modifier = Modifier
                    .background(KBSurfaceRaised, KBShapePill)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// ── Cards ────────────────────────────────────────────────

/**
 * The head of the ordered feed list for [id], or null when the playlist has
 * nothing for that game.
 *
 * One place because three card paths ask the same question, and "which feed
 * does a card play" must have exactly one answer - the head of the list the
 * ViewModel published, never a re-sort here.
 */
private fun Map<String, List<IptvChannel>>.primaryChannel(id: String): IptvChannel? =
    this[id]?.firstOrNull()

/**
 * The notice for a hub with no lineup to match against.
 *
 * Not an error and not an empty state: the games are real, the scores are
 * real, and the only missing thing is the playlist read - which is a step the
 * viewer can retry from here rather than being told to go somewhere else.
 */
@Composable
private fun LineupMissingNotice(onRetry: () -> Unit) {
    KBCard(
        onClick = onRetry,
        focusedScale = KBFocusRow,
        shape = KBShapePanel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "NO LINEUP LOADED",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your playlist could not be read, so no game can be matched to a " +
                    "channel yet. Open Live TV once to load it, or press here to try again.",
                style = MaterialTheme.typography.bodySmall,
                color = KBTextHi
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "LOAD LINEUP",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBAccent
            )
        }
    }
}

// ── Cards ───────────────────────────────────────────────────────────────

@Composable
private fun GameCard(
    game: SportsGame,
    channel: IptvChannel?,
    livePulse: State<Float>,
    favoriteKeys: Set<String>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onEditFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Week 6" where the feed numbers the week, the venue otherwise. The note
    // chip is suppressed when it says the same thing, which NFL notes often do.
    val context = game.week?.takeIf { it.isNotBlank() } ?: game.venue?.takeIf { it.isNotBlank() }
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // The ball, on the card too: the situation row below names where
                // the ball is and the down, and never which side has it, so
                // without this the card asked the viewer to know from the
                // yardage alone - while the sheet they could open from it drew
                // the answer. Same shared rule, asked of each side, exactly as
                // the sheet's header asks it; a feed that named no possession
                // resolves to neither side and draws no ball.
                TeamColumn(
                    team = game.away,
                    favorite = game.away.favoriteKey in favoriteKeys,
                    possession = SportsDetailRules.possesses(game, game.away),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "@",
                    style = MaterialTheme.typography.titleSmall,
                    color = KBTextLo,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp)
                )
                TeamColumn(
                    team = game.home,
                    favorite = game.home.favoriteKey in favoriteKeys,
                    possession = SportsDetailRules.possesses(game, game.home),
                    alignEnd = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // A LIVE card reserves both of these rows, filled or not. That is
            // the whole bobbing fix: the score lands the moment a game starts
            // and the situation line comes and goes with the down, and a row
            // that appears mid-poll remeasures the card, moves every row below
            // it, and shoves the cards under it in the section - which is why
            // only live cards moved, only some of them, and only sometimes.
            // Upcoming and final games keep the conditional rows: their data
            // never changes, so a reserved empty row would be dead space.
            val liveSlots = game.state == GameState.LIVE
            ScoreRow(game = game, reserveSlot = liveSlots)
            SituationLine(game = game, reserveSlot = liveSlots)

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameStatusLine(game = game, livePulse = livePulse)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NoteChip(
                        text = game.note?.takeIf { note ->
                            context == null || !note.equals(context, ignoreCase = true)
                        }
                    )
                    BroadcastChip(name = game.broadcastNames.firstOrNull())
                }
            }

            ContextLine(week = game.week, venue = game.venue)

            Spacer(modifier = Modifier.height(8.dp))

            ChannelLine(
                channel = channel,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone
            )
        }
    }

    // Every game opens the sheet, playable or not: a card the playlist cannot
    // carry still has a matchup worth reading, and the sheet is where "not in
    // your playlist" is explained. It is the sheet's WATCH button that is gated
    // on a matched channel, not the card - and the sheet is one press, with the
    // long press still following a team.
    KBCard(
        onClick = { onOpenDetail(game) },
        onLongClick = onEditFavorites,
        // The guide's own row step, not a poster tile's: a game card is a row of
        // information that happens to be two columns wide.
        //
        // ...except on a LIVE card, which takes no focus scale at all. The
        // card's content is now stable across a poll (see the reserved rows
        // above), so what was left of the bob was this: the scale animation
        // runs on the focused card, and a recomposition from the 30s poll
        // restarts it - 1.0 -> 1.02, then settle, on every update, while the
        // unfocused cards beside it sat still at 1.0 and never moved. Only live
        // cards get that recomposition (an upcoming or final card's data does
        // not change), so only live cards pay for it. The 2% is imperceptible
        // next to the accent border and glow that already mark focus, and those
        // are unchanged.
        focusedScale = if (game.state == GameState.LIVE) 1f else KBFocusRow,
        shape = KBShapeCard,
        modifier = modifier.fillMaxWidth()
    ) {
        body()
    }
}

@Composable
private fun TeamColumn(
    team: SportsTeam,
    favorite: Boolean,
    alignEnd: Boolean = false,
    /**
     * Whether the feed says this side has the ball. The sheet's header asks it
     * of both sides and so does the game card's, through the one shared rule
     * (see [SportsDetailRules.possesses] and [PossessionBall]); the default is
     * the quiet column, which is what any caller that has no possession to
     * report gets.
     */
    possession: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * The mark's plate size. The sheet's header runs it at 40dp to buy the
     * scrolling middle a stat row back; a card keeps the 48dp default.
     */
    markSize: Dp = 48.dp,
) {
    // The mark over the name, not beside it: with two cards sharing a row the
    // side-by-side form left the crest floating in the middle of its half, and
    // the code that identifies the team needs to sit under the crest it
    // belongs to.
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        TeamMark(team = team, size = markSize)
        Spacer(modifier = Modifier.height(6.dp))
        // The code line, with the ball in front of it on the side that has it.
        // A Row only so the marker can sit beside the code rather than under
        // it; with no possession it holds one Text and lays it out exactly as
        // the bare Text did, at either end.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (possession) {
                PossessionBall()
                Spacer(modifier = Modifier.width(6.dp))
            }
            // "TB · 4-1": the code and the season record on one line, which is how
            // a scoreboard says it. The record is simply absent where the feed
            // carries none (tennis, MMA) and the line is the code alone.
            //
            // A ranked side reads "#5 TB" - the ranking is part of the code,
            // not a chip beside it, so it costs no height and an unranked or
            // pro side prints exactly the line it always did. Only the college
            // feeds carry a ranking at all (see [SportsTeam.rank]).
            val code = listOfNotNull(
                team.rank?.let { "#$it" },
                team.abbreviation.ifBlank { team.displayName }
            ).joinToString(" ")
            Text(
                // The star is the whole "this is one of yours" signal, so it sits on
                // the code the way a broadcast mark sits on a channel: no second
                // row, no badge to decode.
                text = listOfNotNull(
                    code,
                    team.record?.trim()?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").let { if (favorite) "★ $it" else it },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (favorite) KBAccent else KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The ball, beside the code of the side that has it.
 *
 * A drawn accent oval rather than a glyph, for the reason the sheet's other
 * marks are drawn: an emoji's weight, colour and font belong to the platform, so
 * the one mark on this plate the app could not control would be the one saying
 * something as load-bearing as who has the ball. Sized between the LIVE dot and
 * the type beside it, so it reads as a marker on the code rather than as a
 * bullet in front of it.
 *
 * Drawn ONLY where the feed named a possession that resolved to one of this
 * game's sides (see [SportsDetailRules.possesses]) - the absence of the ball is
 * meaningful, so it is never guessed at.
 */
@Composable
private fun PossessionBall(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 11.dp, height = 8.dp)
            .clip(KBShapePill)
            .background(KBAccent)
            // The ball is information, and it is drawn outside the text it
            // belongs to: a TalkBack pass over the header has to hear it.
            .semantics { contentDescription = "In possession" }
    )
}

/**
 * The content each reserved row is measured against.
 *
 * Sized as the widest thing the row can be asked to hold - a two-digit score, a
 * one-line situation - though only the line count reaches the measurement: a
 * line's height is a property of the style and the font, not of the glyphs in
 * it, which is what makes these two rows pinnable at all.
 */
private const val RESERVED_SCORE_SAMPLE = "00"
private const val RESERVED_SITUATION_SAMPLE = "3rd & 7"

/**
 * The height of one reserved row's slot, measured in the very style the row will
 * draw its value in - so an empty slot and a filled one are the same height by
 * construction, on any device, at any font scale.
 *
 * Measured rather than written down as a dp constant, deliberately. The theme
 * defines every type slot and only the body styles set a lineHeight (see
 * KBStreamTypography), so the score row's height is whatever Oswald's own font
 * metrics give 28sp at the viewer's font scale - there IS no constant to write.
 * A number written down would be right at one font scale and wrong at the next,
 * and wrong in the way that matters here: shorter than the score that lands in
 * it, which is the bobbing this exists to stop. Nothing needs re-verifying when
 * the style changes either - the slot follows it.
 *
 * Memoized per style/sample/weight/density, and the measurer keeps its own small
 * layout cache, so a card pays for one measure of one short line.
 */
@Composable
private fun rememberRowSlotHeight(
    style: TextStyle,
    sample: String,
    fontWeight: FontWeight? = null
): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style, sample, fontWeight) {
        val measured = measurer.measure(
            text = AnnotatedString(sample),
            style = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style,
            softWrap = false,
            maxLines = 1
        )
        with(density) { measured.size.height.toDp() }
    }
}

/**
 * The two scores, each under its own side and larger than anything else on the
 * card - the score is what the card is for.
 *
 * A game that has not started draws no row at all rather than two empty
 * columns: "no score yet" and "0-0" must not look alike. A live score is set
 * heavier than a final's, so the one that is moving right now reads first from
 * across the room.
 *
 * [reserveSlot] is the live card's answer to a feed that has not attached a
 * score yet: the row keeps the height the score itself will occupy, so the
 * score arriving on the next poll fills a row that is already there instead of
 * creating one (see the slots note in [GameCard] and [rememberRowSlotHeight]).
 */
@Composable
private fun ScoreRow(game: SportsGame, reserveSlot: Boolean = false) {
    val away = game.away.score?.takeIf { it.isNotBlank() }
    val home = game.home.score?.takeIf { it.isNotBlank() }
    if (away == null && home == null && !reserveSlot) return
    val live = game.state == GameState.LIVE
    // The slot, at the height the score will occupy when it lands. Measured in
    // the SAME style and weight [ScoreText] draws with below, because Oswald's
    // Bold and SemiBold do not share metrics and the live score is the bold one.
    val slotHeight = rememberRowSlotHeight(
        style = MaterialTheme.typography.headlineLarge,
        sample = RESERVED_SCORE_SAMPLE,
        fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .then(if (reserveSlot) Modifier.heightIn(min = slotHeight) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScoreText(
            score = away,
            live = live,
            color = scoreColor(game, game.away),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        ScoreText(
            score = home,
            live = live,
            color = scoreColor(game, game.home),
            alignEnd = true,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The live situation line, when the feed carries one: "3rd & 7 · Ball on NE 32",
 * "Top 5th · 1 out".
 *
 * Without [reserveSlot] a game with no situation draws no row at all - not a
 * blank one - which is what every upcoming and final card wants and what this
 * line did before the reserved slots existed. With it (a live card) the row is
 * always drawn, at the height the line will occupy once the feed names a down,
 * because this line is one of the slots live data churn moves; the card must not
 * change size when the line arrives (see [rememberRowSlotHeight]). The empty
 * state draws no stand-in text at all - not a placeholder a viewer could read,
 * and not a guess at what the line will say - the fixed height is the whole
 * reservation.
 */
@Composable
private fun SituationLine(game: SportsGame, reserveSlot: Boolean = false) {
    val situation = game.situation?.takeIf { it.isNotBlank() }
    if (situation == null && !reserveSlot) return
    val slotHeight = rememberRowSlotHeight(
        style = MaterialTheme.typography.bodySmall,
        sample = RESERVED_SITUATION_SAMPLE
    )
    Text(
        text = situation.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = KBTextLo,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .then(if (reserveSlot) Modifier.heightIn(min = slotHeight) else Modifier)
    )
}

@Composable
private fun ScoreText(
    score: String?,
    live: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    Text(
        text = score.orEmpty(),
        style = MaterialTheme.typography.headlineLarge,
        fontFamily = OswaldFamily,
        fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        modifier = modifier
    )
}

/**
 * A team's mark: the real crest where the feed has one, and the initials on a
 * raised plate where it does not.
 *
 * Boxed rather than bare so every card's text starts at the same x whether or
 * not a logo exists - a league of mixed tennis players and clubs would
 * otherwise have its names ragged down the column. Same placeholder/error
 * handling as the guide's channel logos: the plate IS the placeholder.
 */
@Composable
private fun TeamMark(team: SportsTeam, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(KBShapeChip)
            // The plate wears the club's own colour where ESPN publishes one a
            // crest can be seen on, so the mark reads as team-branded rather
            // than as a grey tile; everybody else keeps the plain raised plate.
            .background(clubTint(team.colorHex) ?: KBSurfaceRaised)
            .border(1.dp, KBTextLo.copy(alpha = 0.18f), KBShapeChip),
        contentAlignment = Alignment.Center
    ) {
        val mark = team.logoUrl
        if (mark.isNullOrBlank()) {
            // No mark in the feed at all - a person, or a club ESPN carries no
            // crest for.
            TeamInitials(team = team)
        } else {
            // The shape the add-on tiles already use: the letter is the plate's
            // content while the crest loads, and stays if it never arrives. A
            // dead URL used to leave the plate empty, which is the one outcome
            // this tile must never have.
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(mark)
                    .crossfade(false)
                    .build(),
                contentDescription = team.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp),
                loading = { TeamInitials(team = team) },
                error = { TeamInitials(team = team) }
            )
        }
    }
}

/** The letter tile: two initials on the plate, the app's fallback for a mark. */
@Composable
private fun TeamInitials(team: SportsTeam) {
    Text(
        text = initials(team),
        color = KBTextHi,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

/**
 * ESPN's club colour as a plate tint, or null when it cannot be used.
 *
 * Null for anything that is not a 6-digit hex - and for colours dark enough to
 * disappear into the app's own dark surface, which is the case the raw value
 * has to be refused over rather than trusted: ESPN returns `000000` for clubs
 * whose real colour it does not know, and a black plate would hide the crest
 * this exists to show.
 */
private fun clubTint(colorHex: String?): Color? {
    val clean = colorHex?.trim()?.removePrefix("#") ?: return null
    if (clean.length != 6) return null
    val value = clean.toLongOrNull(16) ?: return null
    val red = ((value shr 16) and 0xFF) / 255f
    val green = ((value shr 8) and 0xFF) / 255f
    val blue = (value and 0xFF) / 255f
    if (red + green + blue < 0.35f) return null
    return Color(red, green, blue).copy(alpha = 0.28f)
}

private fun initials(team: SportsTeam): String {
    val source = team.abbreviation.ifBlank { team.displayName }
    return source.split(' ', '-')
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { source.take(2).uppercase() }
}

/**
 * The score's colour: live scores are the story and read bright, a final is
 * history and dims, and a game that has not started shows nothing at all
 * rather than a misleading "0".
 */
private fun scoreColor(game: SportsGame, team: SportsTeam): Color {
    if (team.score.isNullOrBlank()) return KBTextLo.copy(alpha = 0.45f)
    return when (game.state) {
        GameState.LIVE -> KBTextHi
        GameState.FINAL -> KBTextHi.copy(alpha = 0.72f)
        GameState.UPCOMING -> KBTextHi
    }
}

@Composable
private fun GameStatusLine(game: SportsGame, livePulse: State<Float>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (game.state) {
            GameState.LIVE -> {
                LiveDot(pulse = livePulse)
                Text(
                    text = game.statusDetail.ifBlank { "LIVE" },
                    style = MaterialTheme.typography.labelLarge,
                    color = KBDanger,
                    fontWeight = FontWeight.SemiBold
                )
            }
            GameState.UPCOMING -> Text(
                // The sheet's own start-time string, shared rather than
                // respelled, so the card and the sheet can never disagree.
                text = SportsDetailRules.statusLine(game),
                style = MaterialTheme.typography.bodySmall,
                color = KBTextHi
            )
            GameState.FINAL -> Text(
                text = "Final",
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo
            )
        }
    }
}

/**
 * The LIVE beat, computed once per league and handed to every dot it draws.
 *
 * The pulse is what separates "on now" from "on later" at a glance from across
 * the room - and it is the section's pulse, not each card's. Every live card
 * used to own an infinite transition, so a slate with six live games ran six
 * animation clocks pulsing the same beat, each recomposing its own card on its
 * own frame. One shared value means one clock and one recomposition scope for
 * the whole section.
 *
 * Honours reduced motion (the app's own preference) by holding the dot at full
 * strength instead of animating it - read here, so a reduced-motion viewer has
 * no animation running at all rather than a per-card one that ignores it.
 *
 * Returned as a State rather than today's value, and [pulseEnabled] holds the
 * beat while a sheet is over the hub: a value handed down as a plain Float is
 * read by every card the parameter reaches, so a slate of live games recomposed
 * every one of those cards on every animation frame. As a State the read happens
 * in the dot (see [LiveDot]) and nowhere else, so a frame invalidates the dots
 * and leaves the cards - their text, their marks, their layout - untouched.
 */
@Composable
private fun rememberLivePulse(pulseEnabled: Boolean = true): State<Float> {
    // The state a reduced-motion viewer sees, reused as the paused state: the
    // dot holds at full strength and, because nothing is animating, nothing
    // reads a changing value either. Composed before the early return so the
    // paused and running pulses occupy the same slot.
    val held = remember { mutableStateOf(1f) }
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion || !pulseEnabled) return held
    val transition = rememberInfiniteTransition(label = "sportsLive")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "sportsLivePulse"
    )
}

/**
 * The pulsing red live dot. [pulse] is the section's shared clock, so the dots
 * on a slate of live games breathe together instead of drifting apart.
 *
 * The value is read HERE, in the dot's own recompose scope, and not by the card
 * that hands it down: reading it a level up is what made every live card
 * recompose 60 times a second to redraw a 9dp circle. The card now passes the
 * [State] through untouched and is skipped by the frame it used to be rebuilt
 * on.
 */
@Composable
private fun LiveDot(pulse: State<Float>, modifier: Modifier = Modifier) {
    val alpha = pulse.value
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(KBDanger.copy(alpha = alpha))
    )
}

@Composable
private fun BroadcastChip(name: String?) {
    if (name.isNullOrBlank()) return
    Text(
        text = name.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KBTextLo,
        modifier = Modifier
            .background(KBSurfaceRaised, KBShapeChip)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * ESPN's own context for an event, as a chip beside the network: "ALDS - Game
 * 4" on a postseason game, "Preseason" on an exhibition. It is the one line
 * that says WHY this fixture is on now - the date and the network cannot - and
 * the feed already carries it, so it costs no request.
 */
@Composable
private fun NoteChip(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KBAccent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(KBAccent.copy(alpha = 0.14f), KBShapeChip)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * The line that places the event inside its own season: "Week 6" where the feed
 * numbers the week (football), the venue otherwise - the stadium and its city
 * ("Rocket Arena · Cleveland, OH"), the track on a Grand Prix.
 *
 * Nothing is drawn when the feed carries neither, so a card is never left with
 * a blank row. The week wins where both exist because a schedule is read in
 * weeks and a home stadium is the same building all season.
 */
@Composable
private fun ContextLine(week: String?, venue: String?) {
    val context = week?.takeIf { it.isNotBlank() }
        ?: venue?.takeIf { it.isNotBlank() }
        ?: return
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = context,
        style = MaterialTheme.typography.labelSmall,
        color = KBTextLo.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The bridge, stated plainly: the channel the game is on, or the honest
 * admission that this playlist does not carry it.
 */
@Composable
private fun ChannelLine(
    channel: IptvChannel?,
    lineupMissing: Boolean,
    matchingDone: Boolean,
) {
    if (channel == null) {
        // Three different facts, three different sentences. While matching is
        // still running the hub has no answer to give about the channel yet,
        // and saying "not in your playlist" for the 40-60s a large playlist
        // takes is what made a slow hub read as a broken one - so it says it is
        // still looking. Once the pass is done, "Not in your playlist" is a
        // claim about the viewer's lineup and only true when there IS a lineup
        // to compare against; a hub that could not read one says that instead,
        // because accusing every game of missing is how it reads as broken
        // rather than unloaded.
        Text(
            text = when {
                !matchingDone -> "Finding channel…"
                lineupMissing -> "Lineup not loaded"
                else -> "Not in your playlist"
            },
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo.copy(alpha = 0.8f),
            // One line, always. This is the third slot live data churn moves:
            // "Finding channel…" becomes "Not in your playlist" on the poll
            // that finishes the match, and a two-line sentence where a one-line
            // ellipsis was would remeasure the whole card - the same bob the
            // reserved rows above it exist to stop.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = "▶", style = MaterialTheme.typography.labelSmall, color = KBAccent)
        Text(
            text = channel.displayName.ifBlank { channel.name }.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KBAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TournamentCard(
    event: TournamentEvent,
    channels: List<IptvChannel>,
    livePulse: State<Float>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onPlayChannels: (gameId: String?, tournamentId: String?, List<IptvChannel>) -> Unit,
    onOpenDetail: (TournamentEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = channels.firstOrNull()
    val playable = channel != null

    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = event.name.ifBlank { event.league },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (event.state == GameState.LIVE) LiveDot(pulse = livePulse)
                    Text(
                        // The shared rule, which is what the GAME card and both
                        // sheets draw. Spelled out here, an undated upcoming
                        // event with no statusDetail (a TBD tee time) fell to
                        // `event.statusDetail` and drew an EMPTY line - while
                        // its own sheet said "Today" for the same event.
                        text = SportsDetailRules.statusLine(event),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (event.state == GameState.LIVE) KBDanger else KBTextHi
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NoteChip(text = event.note)
                    BroadcastChip(name = event.broadcastNames.firstOrNull())
                }
            }

            ContextLine(week = null, venue = event.venue)

            if (event.leaders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Leaderboard(leaders = event.leaders)
            }

            Spacer(modifier = Modifier.height(8.dp))
            ChannelLine(
                channel = channel,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone
            )
        }
    }

    if (playable) {
        KBCard(
            // The whole ordered list, head first: the tournament plays on the
            // feed the matcher chose and falls to the rest if it will not open.
            // No ids: this card plays WITHOUT raising a sheet, so a Back from
            // the player has no sheet to reopen and the hub is what it should
            // be - the hub. (The sheet's own WATCH is the path that carries
            // them; see Screen.Sports.)
            //
            // Deliberate divergence from the GAME card, which always raises its
            // sheet on OK: a tournament field is not a matchup and its card has
            // no score to read, so the press is spent on the one thing it can
            // do - start the feed. A viewer who wants the sheet presses OK on a
            // card the playlist cannot carry (the non-playable branch below
            // opens it), or reads the card's own line, which is the same
            // [SportsDetailRules.statusLine] the sheet draws.
            onClick = { onPlayChannels(null, null, channels) },
            focusedScale = KBFocusRow,
            shape = KBShapeCard,
            modifier = modifier.fillMaxWidth()
        ) {
            body()
        }
    } else {
        // The SAME container as the playable branch, because a card the playlist
        // cannot carry is still a card. A bare Surface has no onClick and so is
        // not focusable at all: the D-pad could see the card and never land on
        // it or press it, which is what "the tournaments are dead" was. The
        // press opens the detail sheet instead of playing - the game cards' own
        // answer (see [GameCard]) - and it is never gated on matching, so a
        // press during the 40-60s pass has somewhere to go.
        KBCard(
            onClick = { onOpenDetail(event) },
            focusedScale = KBFocusRow,
            shape = KBShapeCard,
            modifier = modifier.fillMaxWidth()
        ) {
            body()
        }
    }
}

@Composable
private fun Leaderboard(leaders: List<Leader>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        leaders.forEachIndexed { index, leader ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LeaderFlag(leader = leader)
                Text(
                    text = "${index + 1}. ${leader.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == 0) KBTextHi else KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = leader.score,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = OswaldFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = if (index == 0) KBAccent else KBTextLo,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/**
 * The one badge a leaderboard row can wear: a golfer's or driver's country
 * flag, which is all ESPN publishes for a person. Omitted entirely when the row
 * has none - a row of empty plates would read as missing data rather than as
 * "this sport carries no badge".
 */
@Composable
private fun LeaderFlag(leader: Leader) {
    val flag = leader.flagUrl
    if (flag.isNullOrBlank()) return
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(KBShapeChip)
            .background(KBSurfaceRaised),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = flag,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ── Following teams ────────────────────────────────────────────────────

/**
 * The follow list for one game: its two sides, each a toggle.
 *
 * A dialog rather than a star per card because the card is one D-pad stop and
 * has to stay one: two stars on every card would double the stops on a slate
 * and put the thing a viewer presses most (the game) next to the thing they
 * press rarely (the follow). Opened by a long press, the way the guide's
 * channel menu is.
 */
@Composable
private fun FavoriteTeamsDialog(
    game: SportsGame,
    favoriteKeys: Set<String>,
    onToggle: (SportsTeam, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    // Handed to the first row, so opening the dialog puts focus INSIDE it
    // rather than leaving it on the card that raised it.
    val firstRow = remember { FocusRequester() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        KBDialogPanel(title = "Follow teams", width = 460.dp) {
            // The first row is only focusable once the dialog has laid it out,
            // which is a frame or more after this effect starts: the return
            // value is the success test (an unattached requester answers false
            // rather than throwing) and the request is retried across frames
            // until it lands. Read as a one-shot, a lost race left the D-pad
            // on whatever raised the dialog - see the hub entry focus below.
            LaunchedEffect(Unit) {
                var landed = false
                var attempts = 0
                while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
                    awaitFrame()
                    landed = runCatching { firstRow.requestFocus() }
                        .getOrDefault(false)
                    attempts++
                }
            }

            Text(
                text = game.name,
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            listOf(game.away, game.home).forEachIndexed { index, team ->
                FavoriteTeamRow(
                    team = team,
                    followed = team.favoriteKey in favoriteKeys,
                    onToggle = { on -> onToggle(team, on) },
                    focusRequester = firstRow.takeIf { index == 0 }
                )
            }

            Text(
                text = "Your teams get a FAVORITES tab of their own.",
                style = MaterialTheme.typography.labelSmall,
                color = KBTextLo
            )

            KBButton(label = "DONE", onClick = onClose)
        }
    }
}

@Composable
private fun FavoriteTeamRow(
    team: SportsTeam,
    followed: Boolean,
    onToggle: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    KBCard(
        onClick = { onToggle(!followed) },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised, KBShapeChip)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TeamMark(team = team)
                Text(
                    text = team.displayName.ifBlank { team.abbreviation },
                    style = MaterialTheme.typography.bodyMedium,
                    color = KBTextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (followed) "FOLLOWING" else "FOLLOW",
                style = MaterialTheme.typography.labelSmall,
                color = if (followed) KBVoid else KBTextLo,
                modifier = Modifier
                    .background(if (followed) KBAccent else KBSurfaceRaised, KBShapePill)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

// ── Standings ───────────────────────────────────────────────────────────

/** Width of the code column in a table row; the header aligns to it. */
private val STANDINGS_ABBREV_WIDTH = 52.dp

/** Width of one stat column. Fixed, so every row's numbers line up. */
private val STANDINGS_STAT_WIDTH = 46.dp

/**
 * The GAMES / STANDINGS choice for the selected league.
 *
 * Drawn only for a league the feed publishes a table for, so there is never a
 * STANDINGS view that can only be empty - see [SportsLeague.hasStandings].
 */
@Composable
private fun LeagueViewToggle(
    selected: LeagueView,
    onSelect: (LeagueView) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LeagueViewChip("GAMES", selected == LeagueView.GAMES) { onSelect(LeagueView.GAMES) }
        LeagueViewChip("STANDINGS", selected == LeagueView.STANDINGS) {
            onSelect(LeagueView.STANDINGS)
        }
    }
}

@Composable
private fun LeagueViewChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // The league TABS' own pill (see the league rail's tab): same screen, same
    // shape, same job - but at labelSmall and 12x6 this pair read a size and a
    // step smaller than the tabs directly above it, and its unselected label
    // (KBTextLo) was dimmer than theirs.
    KBCard(onClick = onClick, focusedScale = KBFocusChip, shape = KBShapePill) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) KBVoid else KBTextHi,
            modifier = Modifier
                .background(if (selected) KBAccent else KBSurfaceRaised, KBShapePill)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * The selected league's table, grouped by division (or conference where the
 * feed does not split one), with a heading and a column header once per group.
 *
 * Its own section, fetched separately from the scoreboard: standings never
 * drive game-card logic, and a failure shows the cached table or an empty
 * state - never a crash and never the scoreboard's error card.
 */
@Composable
private fun StandingsBody(
    league: SportsLeague,
    groups: List<StandingGroup>?,
    loading: Boolean,
    failed: Boolean,
    listState: LazyGridState,
    /** The followed-team keys, so a row can show and toggle its state. */
    favoriteKeys: Set<String> = emptySet(),
    /** Follow/unfollow one team, keyed exactly like a game side. */
    onToggleTeam: ((SportsTeam, Boolean) -> Unit)? = null,
    /**
     * Two-column mode's focus reporting: (this column's state, does it hold
     * focus). Null in callers that do not offer the two-column body.
     */
    onColumnFocusChanged: ((LazyGridState, Boolean) -> Unit)? = null,
    onRetry: () -> Unit,
) {
    when {
        groups == null && loading -> KBStatusMessage(
            message = "Loading standings…",
            loading = true,
            modifier = Modifier.fillMaxSize()
        )

        groups == null && failed -> KBStatusMessage(
            title = "Couldn't reach ESPN",
            message = "Standings for ${league.label} are unavailable right now.",
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize()
        )

        groups.isNullOrEmpty() -> KBStatusMessage(
            title = "No standings",
            message = "No standings for ${league.label} right now.",
            icon = KB_STATUS_ICON_EMPTY,
            modifier = Modifier.fillMaxSize()
        )

        else -> {
            val columns = SportsStandingsLayout.columns(
                groups = groups,
                widthDp = LocalConfiguration.current.screenWidthDp
            )
            if (columns.size == 2) {
                // Two conferences side by side, each stacking its own divisions
                // - the same rows for half the vertical scroll. The two columns
                // scroll independently, so each takes a scroll state of its own;
                // they share one gutter with the game grid beside it.
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp)
                ) {
                    columns.forEach { columnGroups ->
                        // One scroll state per column, and the two report which
                        // of them holds the D-pad so the screen's scroll
                        // fallback knows which list a Down/Up should move.
                        val columnState = rememberLazyGridState()
                        StandingsColumn(
                            groups = columnGroups,
                            listState = columnState,
                            leaguePath = league.path,
                            favoriteKeys = favoriteKeys,
                            onToggleTeam = onToggleTeam,
                            onColumnFocusChanged = onColumnFocusChanged,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                // One list, and the SCREEN's own scroll state: the D-pad's
                // scroll fallback is built on that state, and a single-column
                // body is the case it has to keep working for.
                StandingsColumn(
                    groups = columns.first(),
                    listState = listState,
                    leaguePath = league.path,
                    favoriteKeys = favoriteKeys,
                    onToggleTeam = onToggleTeam,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * One scrolling column of the table: a heading and a column header once per
 * group, then its rows.
 *
 * Split out of [StandingsBody] because the two-column body draws this twice -
 * once per conference - while the single-column body draws it once with the
 * screen's own [listState].
 */
@Composable
private fun StandingsColumn(
    groups: List<StandingGroup>,
    listState: LazyGridState,
    /** The league this table belongs to; scopes a row's follow key. */
    leaguePath: String?,
    favoriteKeys: Set<String> = emptySet(),
    onToggleTeam: ((SportsTeam, Boolean) -> Unit)? = null,
    /**
     * Reports (this column's state, hasFocus) as focus enters and leaves the
     * column, so the screen can ride its scroll fallback on the column the
     * viewer is actually in (see StandingsBody). Null for the one-column body,
     * which already uses the screen's own state.
     */
    onColumnFocusChanged: ((LazyGridState, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    HubList(
        listState = listState,
        modifier = modifier.then(
            if (onColumnFocusChanged == null) {
                Modifier
            } else {
                Modifier.onFocusChanged {
                    onColumnFocusChanged(listState, it.hasFocus)
                }
            }
        )
    ) {
        groups.forEach { group ->
            item(key = "std-head-" + group.name) {
                StandingsGroupHeader(name = group.name, count = group.entries.size)
            }
            item(key = "std-cols-" + group.name) { StandingsColumnHeader() }
            itemsIndexed(
                group.entries,
                key = { _, entry ->
                    "std-" + group.name + "-" + entry.abbreviation + "-" + entry.displayName
                }
            ) { _, entry ->
                // The row is a real action now: OK follows/unfollows the team it
                // names. Before, onClick was empty, so the one D-pad stop the
                // table offered did nothing at all (see standingsTeam).
                val team = standingsTeam(leaguePath, entry)
                val followed = team.favoriteKey in favoriteKeys
                StandingsRow(
                    entry = entry,
                    followed = followed,
                    onToggleFollow = {
                        onToggleTeam?.invoke(team, !followed)
                    }
                )
            }
        }
    }
}

@Composable
private fun StandingsGroupHeader(name: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = CARD_GAP_DP.dp)
    ) {
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = KBAccent
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            modifier = Modifier
                .background(KBSurfaceRaised, KBShapePill)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** The W/L/T/PCT/GB/STRK labels, drawn once above each group's rows. */
@Composable
private fun StandingsColumnHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(24.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Spacer(modifier = Modifier.width(STANDINGS_ABBREV_WIDTH))
        Text(
            text = "TEAM",
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            modifier = Modifier.weight(1f)
        )
        listOf("W", "L", "T", "PCT", "GB", "STRK").forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = KBTextLo,
                textAlign = TextAlign.End,
                modifier = Modifier.width(STANDINGS_STAT_WIDTH)
            )
        }
    }
}

@Composable
private fun StandingsRow(
    entry: StandingEntry,
    followed: Boolean,
    onToggleFollow: () -> Unit
) {
    KBCard(
        // Navigable like a game card, and it does something: OK follows the
        // team the row names. The old empty lambda left a full D-pad stop
        // (border + glow) that answered the press with nothing.
        onClick = onToggleFollow,
        focusedScale = KBFocusRow,
        shape = KBShapeCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandingMark(entry = entry)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                // The star and the accent are what make the toggle visible: the
                // row was wired to follow/unfollow and then drew nothing for the
                // state, so OK looked like it had done nothing at all (the game
                // cards signal a followed team exactly this way).
                text = if (followed) "★ ${entry.abbreviation}" else entry.abbreviation,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (followed) KBAccent else KBTextHi,
                maxLines = 1,
                modifier = Modifier.width(STANDINGS_ABBREV_WIDTH)
            )
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StandingsStat(entry.wins.toString())
            StandingsStat(entry.losses.toString())
            StandingsStat(entry.ties.toString())
            StandingsStat(standingsText(entry.winPercent))
            StandingsStat(standingsText(entry.gamesBehind))
            StandingsStat(standingsText(entry.streak))
        }
    }
}

@Composable
private fun StandingsStat(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = OswaldFamily,
        color = KBTextHi,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(STANDINGS_STAT_WIDTH)
    )
}

/** A blank feed value reads as an em dash, never as an empty column. */
private fun standingsText(value: String): String = value.takeIf { it.isNotBlank() } ?: "—"

/**
 * The compact crest a table row wears. The card's 48dp mark would dominate a
 * row, so the plate is smaller here - same crest, same letter fallback.
 */
@Composable
private fun StandingMark(entry: StandingEntry) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(KBShapeChip)
            .background(KBSurfaceRaised)
            .border(1.dp, KBTextLo.copy(alpha = 0.18f), KBShapeChip),
        contentAlignment = Alignment.Center
    ) {
        val mark = entry.logoUrl
        if (mark.isNullOrBlank()) {
            StandingsInitials(entry = entry)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(mark)
                    .crossfade(false)
                    .build(),
                contentDescription = entry.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp),
                loading = { StandingsInitials(entry = entry) },
                error = { StandingsInitials(entry = entry) }
            )
        }
    }
}

@Composable
private fun StandingsInitials(entry: StandingEntry) {
    Text(
        text = entry.abbreviation.take(2).uppercase(),
        color = KBTextHi,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold
    )
}

// ── The game detail sheet ───────────────────────────────────────────────

/**
 * One tournament's own sheet, raised by a press on a card the playlist cannot
 * carry.
 *
 * The game sheet's twin, and it exists for the same reason: a card the playlist
 * does not hold still says something worth reading, and the press has to have an
 * answer. What it adds over the card is the room to say WHY - [ChannelLine]
 * states which of the three facts this is (a matching pass still running, no
 * lineup to compare against, or a playlist that simply does not carry it),
 * which the game cards have always had and tournaments never did.
 *
 * It fetches nothing: it renders the already-loaded [TournamentEvent] and the
 * match map the hub already holds. A tournament the matcher HAS found - the
 * match can land while the sheet is open - gets the same focused one-press
 * WATCH the game sheet gives, through the hub's existing launch; with no feed
 * the row is a plain, non-focusable surface carrying the reason instead of the
 * word WATCH.
 */
@Composable
private fun TournamentDetailSheet(
    event: TournamentEvent,
    channels: List<IptvChannel>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    /**
     * Reports a feed the viewer chose themselves, so the correction survives to
     * the next round. The tournament twin of the game sheet's own callback; see
     * `SportsChannelMemory`.
     */
    onManualPick: (IptvChannel) -> Unit = {},
    onPlay: (List<IptvChannel>) -> Unit,
    onClose: () -> Unit,
) {
    val watchButton = remember { FocusRequester() }
    val channel = channels.firstOrNull()
    val backups = channels.drop(1)
    // The game sheet's own explicit D-pad walk, for the same reason: the pills
    // live in a scroll container where directional search is a guess.
    val backupButtons = remember(backups.size) { List(backups.size) { FocusRequester() } }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // The game sheet's dock, scrim and all: the plate keeps its 24dp inset
        // through its own padding so the veil covers the whole window, and the
        // hub behind a tournament sheet is no more readable behind it than it is
        // behind a game's.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SHEET_SCRIM_ALPHA))
            )
            // Bounded against the window, the game sheet's own remedy for the
            // same class of bug: a plate that wrapped its content grew taller
            // than the screen and then SCROLLED, cropping the heading off the
            // top edge.
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.92f)
                    .focusGroup()
                    .background(KBSurface, KBShapePanel)
                    .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "TOURNAMENT",
                    color = KBAccent,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = event.name.ifBlank { event.league },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = SportsDetailRules.statusLine(event),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (event.state == GameState.LIVE) KBDanger else KBTextLo
                    )
                    Text(
                        // The model carries the ESPN path ("golf/pga"), so the
                        // label the hub already knows is what a viewer reads; a
                        // path it does not know is shown as it came rather than
                        // hidden.
                        text = (SportsLeagues.byPath(event.league)?.label ?: event.league)
                            .uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KBTextLo
                    )
                }

                // The same scrolling body the game sheet uses, so the last
                // backup feed's focus ring is landed clear of the bottom edge
                // here too.
                SheetScrollBody {
                    // Every row is already filtered to the non-blanks, so a stop
                    // with no venue and no note simply draws fewer rows.
                    SportsDetailRules.detailRows(event).forEach { row ->
                        DetailRow(label = row.label, value = row.value)
                    }

                    if (event.leaders.isNotEmpty()) {
                        Text(
                            text = "LEADERS",
                            style = MaterialTheme.typography.labelSmall,
                            color = KBTextLo,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Leaderboard(leaders = event.leaders)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // The bridge, with the room to explain itself: this is the
                    // line the card compresses, and the whole reason a sheet
                    // exists for a tournament with no feed.
                    Text(
                        text = "CHANNEL",
                        style = MaterialTheme.typography.labelSmall,
                        color = KBTextLo
                    )
                    ChannelLine(
                        channel = channel,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone
                    )

                    // The other feeds the playlist holds for this tournament -
                    // the same rows the game sheet offers. Picking one is the
                    // viewer correcting the matcher, and the game sheet's own
                    // pattern is what records it (see `onManualPick`); with no
                    // backup there is no heading, so the sheet never promises a
                    // choice it does not have.
                    if (backups.isNotEmpty()) {
                        Text(
                            text = "BACKUP CHANNELS",
                            style = MaterialTheme.typography.labelSmall,
                            color = KBTextLo,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        backups.forEachIndexed { index, backup ->
                            KBCard(
                                onClick = {
                                    onManualPick(backup)
                                    onPlay(listOf(backup) + channels.filter { it.id != backup.id })
                                },
                                focusedScale = KBFocusRow,
                                shape = KBShapePill,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(backupButtons[index])
                                    // Pill to pill, and the LAST pill hands Down to
                                    // the pinned WATCH under this scroll body. The
                                    // action is a sibling of the body, so the
                                    // platform's own directional search cannot
                                    // reach it from in here - the same reason the
                                    // game sheet links its footer by hand.
                                    .focusDownTo(
                                        backupButtons.getOrNull(index + 1)
                                            ?: watchButton.takeIf { channel != null }
                                    )
                            ) {
                                BackupChannelLabel(channel = backup)
                            }
                        }
                    }
                }

                 // ---- PINNED FOOTER: the WATCH action, always visible ----
                 // This sheet was the last one drawing WATCH INSIDE the scroll
                 // body, under the detail rows, the leaders and the channel
                 // line - and focusing it on open scrolled the header away,
                 // which is the exact fault the game sheet's pinned footer was
                 // written to fix (see GameDetailSheet). Same remedy: the action
                 // lives outside the scroll container, and Up hands to the LAST
                 // backup pill because the pills are inside it.
                 if (channel != null) {
                     KBCard(
                         // The whole ordered list, head first - the same
                         // launch a matched card takes.
                         onClick = { onPlay(channels) },
                         focusedScale = KBFocusRow,
                         shape = KBShapePill,
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(top = 10.dp)
                             .focusRequester(watchButton)
                             .focusUpTo(backupButtons.lastOrNull())
                     ) {
                         WatchButtonLabel(
                             label = SportsDetailRules.watchLabel(true),
                             enabled = true
                         )
                     }
                     // WATCH is the sheet's default stop, and the plate it sits
                     // on composes the footer a frame after the sheet opens: the
                     // request is retried until the node is attached and the
                     // ANSWER is what ends it, so a lost race cannot leave the
                     // D-pad on the card behind the scrim (see the hub entry
                     // focus).
                     LaunchedEffect(Unit) {
                         var landed = false
                         var attempts = 0
                         while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
                             awaitFrame()
                             landed = runCatching { watchButton.requestFocus() }
                                 .getOrDefault(false)
                             attempts++
                         }
                     }
                 } else {
                     // Not focusable: a disabled action must not be a D-pad
                     // stop. It carries the reason instead of the action's name,
                     // from the same shared rule the game sheet uses.
                     Surface(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(top = 10.dp),
                         shape = KBShapePill,
                         colors = SurfaceDefaults.colors(
                             containerColor = KBSurfaceRaised,
                             contentColor = KBTextLo
                         )
                     ) {
                         WatchButtonLabel(
                             label = SportsDetailRules.watchLabel(false, lineupMissing, matchingDone),
                             enabled = false
                         )
                     }
                 }
             }
         }
     }
}

/**
 * The full matchup for one game, raised by a tap on its card.
 *
 * A bottom sheet rather than a centred dialog: it is the card, opened - the
 * same facts in more room - so it rises from the bottom edge and leaves the hub
 * visible above it. It fetches nothing (it renders the already-loaded
 * [SportsGame]), so there is never a spinner in it, and its one action is
 * Watch, which hands the matched channel to the same launch a card tap used to
 * take. With no matched channel the button is disabled and says so.
 *
 * A scrim now sits between it and the hub. The hub behind is a live screen -
 * pulsing dots, polling scores, cards whose rows appear with the next down - and
 * reading stats through all of it was reading two screens at once. The scrim
 * dims what is behind (the pulse is separately held, see [rememberLivePulse])
 * and the hub keeps polling underneath it, so the slate is as current when the
 * sheet closes as it was when it opened.
 *
 * Laid out as three parts, not one column. The header (crests, matchup, score)
 * and the WATCH action are both pinned - the header first because a focused
 * button used to scroll the crests out through the plate's top edge, and the
 * action last because a full stats block under the header left the scroll body
 * with no visible room, so WATCH and every backup feed below it were on screen
 * only in the sense that they were laid out. Between them only the body scrolls:
 * with the stats at the top of that body and the action outside it, opening the
 * sheet focuses WATCH without scrolling anything, which is what keeps the stats
 * where they are and the action reachable.
 */
@Composable
private fun GameDetailSheet(
    game: SportsGame,
    channels: List<IptvChannel>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    /**
     * The hub's summary for this game, or null when it has none. The sheet draws
     * the stats section from it and nothing else: no fetch, no spinner, no error
     * state - a game ESPN gives no stats for (live or finished) is simply the
     * sheet as it was before the section existed (see `SportsDetailRules.hasStats`).
     */
    summary: EspnGameSummary? = null,
    onManualPick: (IptvChannel) -> Unit = {},
    onPlay: (List<IptvChannel>) -> Unit,
    onClose: () -> Unit,
) {
    val watchButton = remember { FocusRequester() }
    val channel = channels.firstOrNull()
    val backups = channels.drop(1)
    // One requester per backup pill, so each row can name the row under it. The
    // list can grow while the sheet is open - the matcher's pass can land after
    // the sheet was raised - hence the size in the key rather than a remember
    // with no keys. Declared above the three-way split because the pinned
    // footer's WATCH hands Up to the LAST pill and so has to reach the same
    // requesters the scrolling body uses.
    val backupButtons = remember(backups.size) { List(backups.size) { FocusRequester() } }
    // The top of the scrolling middle, as a D-pad stop. The stats, the detail
    // rows and the leaders above the pills are plain Text, so nothing up there
    // can take focus: without this anchor, Up from the first pill had no stop to
    // move to, and the scroll stayed where it was - the field report was a
    // viewer who had scrolled down to the backups and could not get back to the
    // stats. The anchor draws nothing and answers no press; it exists only so
    // that Up has somewhere to go, and focusing it brings its own 1dp into view
    // at the top of the body (see [DetailSheetBody] and [SheetScrollBody]).
    val topAnchor = remember { FocusRequester() }
    // One requester per team-stat row, so the comparison rows are D-pad stops
    // like the backup pills: focus is the only thing that scrolls this body, so
    // without a stop per row the stats below the fold could never be brought
    // into view. Keyed on the row count like the pills are, because a summary
    // can land while the sheet is open. Zero rows with no stats section, and
    // every chain then falls back to exactly the behaviour of before.
    val stats = summary?.takeIf { SportsDetailRules.hasStats(it) }
    val statRows = remember(stats?.teamStats?.size ?: 0) {
        List(stats?.teamStats?.size ?: 0) { FocusRequester() }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // The dock fills the window and the plate keeps its inset through its
        // own padding, so the scrim below covers the whole window instead of
        // stopping short of it - a dimmed plate with a brighter frame around it
        // would read as a mistake rather than as a veil. The plate's size and
        // position are unchanged: it is still 72% by 92% of the window less its
        // inset, on the bottom edge.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // The veil itself. Drawn - not clickable: dismissing on a tap
            // outside the plate is the dialog window's own behaviour and stays
            // exactly as it was (see onDismissRequest above).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SHEET_SCRIM_ALPHA))
            )
            // Bounded against the window (the leagues panel's own remedy for
            // the same class of bug): a plate that wrapped its content grew
            // taller than the screen and then SCROLLED, and the scroll cropped
            // the crests off the top edge.
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.92f)
                    .focusGroup()
                    .background(KBSurface, KBShapePanel)
                    .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "MATCHUP",
                    color = KBAccent,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = SportsDetailRules.matchupTitle(game),
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Header: away mark + code + record | @ | home mark + code +
                // record, the card's own sides in a wider plate - and the ball
                // on the side the feed says has it. The card never marks one,
                // so the sheet is the only place possession is drawn.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamColumn(
                        team = game.away,
                        favorite = false,
                        possession = SportsDetailRules.possesses(game, game.away),
                        modifier = Modifier.weight(1f),
                        markSize = 40.dp
                    )
                    Text(
                        text = "@",
                        style = MaterialTheme.typography.titleSmall,
                        color = KBTextLo,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    TeamColumn(
                        team = game.home,
                        favorite = false,
                        possession = SportsDetailRules.possesses(game, game.home),
                        alignEnd = true,
                        modifier = Modifier.weight(1f),
                        markSize = 40.dp
                    )
                }

                Text(
                    text = SportsDetailRules.statusLine(game),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (game.state == GameState.LIVE) KBDanger else KBTextLo
                )

                Text(
                    text = SportsDetailRules.scoreLine(game),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = OswaldFamily,
                    fontWeight = FontWeight.Bold,
                    color = KBTextHi,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // ---- SCROLLING MIDDLE (weight 1f, via [DetailSheetBody]) ----
                // The sheet is a pinned header, this scrolling middle, and a
                // pinned footer. The crests, the matchup and the score are the
                // header and cannot be scrolled away; the stats, the venue rows,
                // the leaders and the backup feeds all live here; the one action
                // is pinned below (see the footer).
                DetailSheetBody(
                    game = game,
                    summary = summary,
                    channels = channels,
                    backupButtons = backupButtons,
                    statRows = statRows,
                    topAnchor = topAnchor,
                    onManualPick = onManualPick,
                    onPlay = onPlay
                )

                // ---- PINNED FOOTER: the WATCH action, always visible ----
                // It used to be the last row INSIDE the scroll body, under a full
                // stats block and the pinned header, which left the body with no
                // visible room: the field report was a sheet whose WATCH button
                // and backup rows could not be seen or reached at all. Outside
                // the scroll container it is always laid out, and focusing it on
                // open cannot scroll the body - which is also why the stats at
                // the top of the body stay exactly where they are.
                if (channel != null) {
                    KBCard(
                        // The whole ordered list, head first: the one-press path is
                        // the feed the matcher chose, and the rest ride along
                        // behind it so the player's own ladder has somewhere to
                        // fall when that feed will not open.
                        onClick = { onPlay(channels) },
                        focusedScale = KBFocusRow,
                        shape = KBShapePill,
                        // WATCH now sits BELOW the body, so it hands Up to the
                        // LAST backup pill (see [focusUpTo]): the pills are inside
                        // a scroll container, and leaving that step to directional
                        // search is what left them unreachable.
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .focusRequester(watchButton)
                            .focusUpTo(backupButtons.lastOrNull() ?: statRows.lastOrNull())
                    ) {
                        WatchButtonLabel(
                            label = SportsDetailRules.watchLabel(true),
                            enabled = true
                        )
                    }
                    // Same verified retry as the game sheet's footer: the
                    // tournament sheet's WATCH is its default stop, and the
                    // request has to be confirmed rather than assumed.
                    LaunchedEffect(Unit) {
                        var landed = false
                        var attempts = 0
                        while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
                            awaitFrame()
                            landed = runCatching { watchButton.requestFocus() }
                                .getOrDefault(false)
                            attempts++
                        }
                    }
                } else {
                    // Not focusable: a disabled action must not be a D-pad stop.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = KBShapePill,
                        colors = SurfaceDefaults.colors(
                            containerColor = KBSurfaceRaised,
                            contentColor = KBTextLo
                        )
                    ) {
                        WatchButtonLabel(
                            label = SportsDetailRules.watchLabel(false, lineupMissing, matchingDone),
                            enabled = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * One sheet's scrolling body: the rows that cannot be pinned, in a Column that
 * keeps the same focus landing the hub's card lists use.
 *
 * Every sheet that lists focusable cards needs this. A scroll container clips to
 * its own bounds, and Compose's default landing leaves the focused card's LAYOUT
 * rectangle flush with the edge - inside the 2dp accent border and 12dp glow it
 * draws outside that rectangle - so the LAST backup feed's focus ring was
 * sheared off flat along the bottom of the scroll.
 *
 * The trailing spacer is not decoration: the list cannot scroll a row up to the
 * landing margin unless there is slack past it, so without it the last row is
 * stuck flush with the bottom whatever the landing asks for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.SheetScrollBody(
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val bringIntoViewSpec = remember(density) {
        SportsCardBringIntoViewSpec(insetPx = with(density) { KBFocusRowInset.toPx() })
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
            Spacer(modifier = Modifier.height(KBFocusRowInset))
        }
    }
}

/**
 * Hand this stop's D-pad Down to [next], explicitly.
 *
 * The backup feeds are drawn inside a scroll container, and directional search
 * across one is a guess: from one pill the nearest focusable below is whichever
 * the layout happens to put there, which is not necessarily the next one, and on
 * the field report it was none of them - Down did nothing at all, so every feed
 * below the top of the list was unreachable. Chaining each stop to the next
 * makes the order the rows are drawn in the order the D-pad walks in, and leaves
 * every OTHER direction to the search that already worked. The tournament sheet
 * uses it to lead its own WATCH into the first pill, which is the same list.
 *
 * A null [next] changes nothing: the last pill keeps the platform's own
 * behaviour below it rather than being wired to itself, and a sheet with no
 * backups is exactly the sheet it was.
 *
 * This is a focus CHAIN, not a scroll fix: a focused pill that is still off the
 * bottom of the body is [SheetScrollBody]'s landing margin failing to do its
 * job, which is a different fault with a different remedy.
 */
private fun Modifier.focusDownTo(next: FocusRequester?): Modifier =
    if (next == null) this else focusProperties { down = next }

/**
 * Hand this stop's D-pad Up to [previous], explicitly - the mirror of
 * [focusDownTo], for the stop that now sits BELOW the list it belongs to.
 *
 * Since the WATCH action moved to the sheet's pinned footer (see
 * [GameDetailSheet]) it is drawn after the scrolling body, so Up from it has to
 * reach the LAST backup pill - the one nearest it - rather than whichever
 * focusable directional search happens to find, which for the same reason as
 * Down from a pill is a guess. Everything else keeps the search that already
 * worked: a null [previous] changes nothing, and the other directions are
 * untouched.
 */
private fun Modifier.focusUpTo(previous: FocusRequester?): Modifier =
    if (previous == null) this else focusProperties { up = previous }

/**
 * The scrolling MIDDLE of the game sheet: the live stats, the venue rows, the
 * leaders and the backup feeds.
 *
 * Split out of [GameDetailSheet] so the pinned footer below it stays readable -
 * see there for why the WATCH action is not part of this list any more - and
 * drawn through [SheetScrollBody] so its last row gets the focus landing the
 * hub's card lists have.
 */
@Composable
private fun ColumnScope.DetailSheetBody(
    game: SportsGame,
    summary: EspnGameSummary?,
    channels: List<IptvChannel>,
    backupButtons: List<FocusRequester>,
    /**
     * One requester per team-stat row, in the order [StatsSection] draws them.
     * The rows are the body's other focusables now (see [StatCompareRow]); the
     * chains that reach them live in [StatsSection], and the anchor and the
     * first pill name the ends of this list.
     */
    statRows: List<FocusRequester>,
    /**
     * The invisible stop at the top of this scroll (see [GameDetailSheet]).
     * Passed in rather than remembered here because the first backup pill -
     * drawn below, inside this same body - has to name it as its Up target (see
     * [focusUpTo]), and the requester has to be the one the sheet declared.
     */
    topAnchor: FocusRequester,
    onManualPick: (IptvChannel) -> Unit,
    onPlay: (List<IptvChannel>) -> Unit,
) {
    val backups = channels.drop(1)
    SheetScrollBody {
        // The anchor: 1dp, invisible and non-interactive, the first thing the
        // body draws so that its own bounds put the scroll at the top when it is
        // focused. It is not a control - nothing happens when it is pressed -
        // and it is not a stop the user can SEE, because it draws nothing to
        // focus. `focusable()` is what makes it a focus TARGET at all: a
        // requester with nothing to request is exactly the dead Up this fixes,
        // since focusDownTo/focusUpTo and the anchor's own requester all resolve
        // against a focus node. Every direction but the two the pills and this
        // anchor name is left to the platform's search.
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(topAnchor)
                .focusDownTo(statRows.firstOrNull() ?: backupButtons.firstOrNull())
                .focusProperties { canFocus = true }
                .focusable()
        )

        // The stats are the TOP of the scroll now, not part of the pinned
        // header: WATCH is pinned BELOW this body (see [GameDetailSheet]), so
        // focusing it on open cannot scroll anything, and the block is on screen
        // from the first frame. Drawn only when there is something in it, so a
        // game with no summary reads exactly as it did before this section
        // existed - and drawn for a FINISHED game too, since the hub keeps a
        // just-ended slate on the board and the box score is exactly what a
        // viewer reopens it for.
        summary?.takeIf { SportsDetailRules.hasStats(it) }?.let { stats ->
            StatsSection(
                game = game,
                summary = stats,
                rowRequesters = statRows,
                topAnchor = topAnchor,
                backupButtons = backupButtons
            )
        }

        // Every row is already filtered to the non-blanks, so a game with no
        // venue and no leaders simply draws fewer rows.
        SportsDetailRules.detailRows(game).forEach { row ->
            DetailRow(label = row.label, value = row.value)
        }

        val leaderLines = SportsDetailRules.leaderLines(game)
        if (leaderLines.isNotEmpty()) {
            Text(
                text = "LEADERS",
                style = MaterialTheme.typography.labelSmall,
                color = KBTextLo,
                modifier = Modifier.padding(top = 4.dp)
            )
            leaderLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // WATCH is no longer a row here: it is the sheet's pinned footer, drawn
        // outside this scroll (see [GameDetailSheet]). With a full stats block
        // above it and the header above that, the body was left with no visible
        // room and the action could neither be seen nor reached; pinned below,
        // it cannot be pushed off the sheet by anything the body draws. What
        // remains here is the secondary reads.

        // The other feeds the playlist holds for this game - the second channel
        // the guide is airing it on, the network's alternates, the team's own
        // regional network. Drawn only when there is one: a "backups" heading
        // over an empty list would promise a choice that does not exist.
        if (backups.isNotEmpty()) {
            Text(
                text = "BACKUP CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                color = KBTextLo,
                modifier = Modifier.padding(top = 4.dp)
            )
            backups.forEachIndexed { index, backup ->
                KBCard(
                    // The chosen feed goes first and the rest follow in their
                    // own order, so picking a backup is a switch rather than a
                    // decision to lose the other feeds.
                    onClick = {
                        onManualPick(backup)
                        onPlay(listOf(backup) + channels.filter { it.id != backup.id })
                    },
                    focusedScale = KBFocusRow,
                    shape = KBShapePill,
                    // Pill to pill, in the order they are drawn; the last one
                    // keeps the platform's own search below it. The FIRST one
                    // additionally names the body's top anchor as the stop above
                    // it - the pills are the only focusable rows in this scroll,
                    // and above them sit nothing but non-focusable Text, so
                    // without that link Up from the first pill went nowhere and
                    // the stats above it could not be scrolled back to.
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(backupButtons[index])
                        .focusDownTo(backupButtons.getOrNull(index + 1))
                        .then(
                            if (index == 0) Modifier.focusUpTo(statRows.lastOrNull() ?: topAnchor)
                            else Modifier
                        )
                ) {
                    BackupChannelLabel(channel = backup)
                }
            }
        }
    }
}

/**
 * The sheet's stats block: win probability, the team comparison, then the last
 * play - in that order, because that is the order a viewer reads a game in (who
 * is winning, how, and what just happened).
 *
 * Serves a game in play AND one that has just ended, which is why the heading
 * comes from [SportsDetailRules.statsHeading] rather than being a literal: the
 * same numbers under a finished game say FINAL STATS.
 *
 * Every part is optional and is skipped on its own: a soccer summary has stats
 * and no win probability, a football one may have all three, and a league ESPN
 * covers thinly has none - in which case the caller has already decided not to
 * call this at all (see `SportsDetailRules.hasStats`).
 */
@Composable
private fun StatsSection(
    game: SportsGame,
    summary: EspnGameSummary,
    rowRequesters: List<FocusRequester>,
    topAnchor: FocusRequester,
    backupButtons: List<FocusRequester>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = SportsDetailRules.statsHeading(game),
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (game.state != GameState.FINAL) {
            // A finished game's probability is 0 or 100 by definition - the bar
            // is noise under FINAL STATS, and dropping it gives the comparison
            // rows the room back.
            summary.homeWinProbability?.let { chance ->
                WinProbabilityBar(game = game, homePercent = chance)
            }
        }

        summary.teamStats.forEachIndexed { index, (label, awayValue, homeValue) ->
            StatCompareRow(
                away = awayValue,
                label = label,
                home = homeValue,
                focusRequester = rowRequesters[index],
                focusUpTo = rowRequesters.getOrNull(index - 1) ?: topAnchor,
                focusDownTo = rowRequesters.getOrNull(index + 1) ?: backupButtons.firstOrNull()
            )
        }

        summary.lastPlay?.takeIf { it.isNotBlank() }?.let { play ->
            Text(
                text = "Last: $play",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = KBTextLo,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One line of win probability: the away side's colour on the left, the home
 * side's on the right, and the home chance printed beside it ("NE 68%").
 *
 * The bar IS the two chances - its split is the number - and the label names the
 * home side, because "68%" on its own does not say whose.
 */
@Composable
private fun WinProbabilityBar(game: SportsGame, homePercent: Int) {
    val home = homePercent.coerceIn(0, 100)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(KBShapeChip)
                .background(KBSurfaceRaised)
        ) {
            // Both segments keep a minimum weight, so a 100% bar still shows the
            // sliver of the other side rather than collapsing the row.
            Box(
                modifier = Modifier
                    .weight((100 - home).coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .background(teamBarColor(game.away.colorHex, KBTextLo))
            )
            Box(
                modifier = Modifier
                    .weight(home.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .background(teamBarColor(game.home.colorHex, KBAccent))
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${game.home.abbreviation.ifBlank { game.home.displayName }.uppercase()} " +
                "$homePercent%",
            style = MaterialTheme.typography.labelSmall,
            color = KBTextHi
        )
    }
}

/**
 * One team-stat row: away value, label, home value - the score header's own
 * left-to-right order, so each number sits under the side it belongs to.
 */
@Composable
private fun StatCompareRow(
    away: String,
    label: String,
    home: String,
    focusRequester: FocusRequester,
    focusUpTo: FocusRequester?,
    focusDownTo: FocusRequester?,
) {
    // A deliberately quiet focus treatment: a tint, not a button - no scale and
    // no border, so a focused row reads as reachable rather than as pressable.
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeChip)
            .background(if (focused) KBSurfaceRaised else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focusRequester)
            .focusable()
            .focusUpTo(focusUpTo)
            .focusDownTo(focusDownTo)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = away,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = KBTextHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.4f)
        )
        Text(
            text = home,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = KBTextHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A solid team colour for the probability bar, or [fallback] when the feed sent
 * none the app can draw. The same hex parsing the crest plates use, but opaque:
 * a bar has nothing behind it for a translucent fill to show through, and a
 * near-black club colour would read as a hole in the bar.
 */
private fun teamBarColor(colorHex: String?, fallback: Color): Color {
    val clean = colorHex?.trim()?.removePrefix("#") ?: return fallback
    if (clean.length != 6) return fallback
    val value = clean.toLongOrNull(16) ?: return fallback
    val red = ((value shr 16) and 0xFF) / 255f
    val green = ((value shr 8) and 0xFF) / 255f
    val blue = (value and 0xFF) / 255f
    if (red + green + blue < 0.35f) return fallback
    return Color(red, green, blue)
}

/** One backup feed's row: the channel's own name, on the same pill as Watch. */
@Composable
private fun BackupChannelLabel(channel: IptvChannel) {
    Text(
        text = channel.displayName.ifBlank { channel.name }.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = KBTextHi,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapePill)
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = KBTextHi,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WatchButtonLabel(label: String, enabled: Boolean) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) KBVoid else KBTextLo,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) KBAccent else KBSurfaceRaised, KBShapePill)
            .padding(vertical = 12.dp)
    )
}

// ── The league toggles ──────────────────────────────────────────────────

/**
 * Per-row focus anchors for the league panel.
 *
 * Reordering a league rebuilds the list, which disposes the focused move arrow
 * and used to drop D-pad focus back to the top of the list: the viewer then had
 * to scroll to the league they were moving before pressing the arrow again.
 * After a move the SAME league's SAME stop is re-focused (see
 * [LeagueTogglesPanel]), so repeated presses just keep working. The row's
 * toggle is the fallback for when that stop is disabled at an end of the list.
 */
private class LeagueRowFocus {
    enum class Slot { UP, DOWN }

    val toggle = FocusRequester()
    val up = FocusRequester()
    val down = FocusRequester()
}

/**
 * The hub's own league picker - the single place league visibility is set.
 *
 * Every league in the catalog is listed, in catalog order, with its state.
 * A league that is off is not fetched and has no tab: the section is absent
 * rather than empty, so turning one on is what makes it exist.
 */
@Composable
private fun LeagueTogglesPanel(
    enabled: Set<String>,
    order: List<String>,
    reminders: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onToggleReminders: (Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onClearMemory: () -> Unit,
    onClose: () -> Unit,
) {
    // Handed to the first row, so opening the panel puts focus INSIDE it
    // instead of leaving it on the LEAGUES button that raised it.
    val firstRow = remember { FocusRequester() }

    // Per-row focus anchors plus the (league, stop) the viewer last pressed a
    // move arrow on. Reordering rebuilds the list, which disposes the focused
    // arrow and used to drop D-pad focus - the viewer then had to scroll back
    // to the league they were moving before pressing again. The effect below
    // re-aims at the SAME league's SAME stop once the order changes, so
    // repeated presses just keep working. Keying the rows by path (see the
    // loop below) is what lets the anchors survive the rebuild.
    val rowFocus = remember { mutableMapOf<String, LeagueRowFocus>() }
    var moveFocus by remember {
        mutableStateOf<Pair<String, LeagueRowFocus.Slot>?>(null)
    }

    // A real dialog WINDOW (the way the home manager and the PIN prompt are
    // raised), not a scrim painted over the hub's own layout - which is what
    // this used to be, and both reported faults came from that.
    //
    //  * The screen behind it was still in the focus tree, so the D-pad could
    //    walk out of the panel and go on moving through the rails behind the
    //    scrim: "the leagues screen loses focus to the screen behind it". A
    //    dialog window is its own focus root, so nothing behind it can be
    //    reached at all.
    //  * The plate's height was a fixed run of dp (a 420dp list plus its
    //    chrome), so on a box reporting a short logical height - 720p at 1.5x
    //    density is only 480dp - the panel ran off the bottom of the screen
    //    and took the DONE button with it, which is the truncated pill. The
    //    plate is sized against the window and the list takes the slack, so
    //    DONE is on it whatever the screen height is.
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        KBDialogPanel(
            title = "Leagues",
            width = 620.dp,
            modifier = Modifier.fillMaxHeight(0.88f)
        ) {
            // The first row is only focusable once the dialog has laid it out,
            // which is a frame or more after this effect starts: the return
            // value is the success test (an unattached requester answers false
            // rather than throwing) and the request is retried across frames
            // until it lands. Read as a one-shot, a lost race left the D-pad
            // on whatever raised the dialog - see the hub entry focus below.
            LaunchedEffect(Unit) {
                var landed = false
                var attempts = 0
                while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
                    awaitFrame()
                    landed = runCatching { firstRow.requestFocus() }
                        .getOrDefault(false)
                    attempts++
                }
            }

            // Restore after a reorder: the order changed, so the arrow the
            // viewer was pressing was disposed with the rebuilt row. Put focus
            // back on the SAME league's move stop - or on that league's toggle
            // when the stop is now disabled because it reached an end.
            LaunchedEffect(order) {
                val target = moveFocus ?: return@LaunchedEffect
                val focus = rowFocus[target.first] ?: return@LaunchedEffect
                val ordered = SportsLeagues.ordered(order)
                val index = ordered.indexOfFirst { it.path == target.first }
                if (index < 0) return@LaunchedEffect
                val requester = when {
                    target.second == LeagueRowFocus.Slot.UP && index > 0 -> focus.up
                    target.second == LeagueRowFocus.Slot.DOWN &&
                        index < ordered.lastIndex -> focus.down
                    index == 0 -> firstRow
                    else -> focus.toggle
                }
                // This effect exists to catch a focus drop, and the rebuilt row
                // it aims at is not necessarily attached yet: requesting once
                // and discarding the answer meant the fix silently did not run
                // when it was needed most. Retried across frames, and the
                // return value is what says the D-pad actually arrived.
                var landed = false
                var attempts = 0
                while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {
                    awaitFrame()
                    landed = runCatching { requester.requestFocus() }
                        .getOrDefault(false)
                    attempts++
                }
                // Consume the target: the next press sets it again before its
                // move, and an unrelated order change (a sync pull) must not
                // yank focus back to the last league that was moved.
                moveFocus = null
            }

            Text(
                text = "Only enabled leagues are fetched and shown. " +
                    "Use ▲ ▼ to set the tab order.",
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo
            )

            // Whatever height is left after the heading, that line and DONE.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The catalog in the viewer's own order: the same list the
                // tabs are drawn from, so what is arranged here is exactly
                // what the hub shows, in that order.
                val orderedLeagues = remember(order) { SportsLeagues.ordered(order) }
                orderedLeagues.forEachIndexed { index, league ->
                    // Keyed by path so a move carries the row's whole group -
                    // including its focus anchors - to the new index instead of
                    // rebinding the group at the old index to another league.
                    key(league.path) {
                        val leagueFocus = remember { LeagueRowFocus() }
                        rowFocus[league.path] = leagueFocus
                        LeagueToggleRow(
                            league = league,
                            checked = league.path in enabled,
                            onToggle = { on -> onToggle(league.path, on) },
                            canMoveUp = index > 0,
                            canMoveDown = index < orderedLeagues.lastIndex,
                            onMoveUp = { onMove(league.path, -1) },
                            onMoveDown = { onMove(league.path, 1) },
                            focusRequester = firstRow.takeIf { index == 0 }
                                ?: leagueFocus.toggle,
                            rowFocus = leagueFocus,
                            onMoveFocus = { slot -> moveFocus = league.path to slot }
                        )
                    }
                }
            }

            // The reminder switch lives here rather than on the hub itself: it
            // is a preference about the hub, and this is the hub's one settings
            // panel. Following a team is the opt-in, so this is on by default.
            Text(
                text = "GAME REMINDERS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBTextLo
            )
            ReminderToggleRow(checked = reminders, onToggle = onToggleReminders)

            // Forgetting what the hub has been taught lives here beside the
            // league toggles because it is the same kind of thing: a preference
            // about the hub. One row, and it is the visible counterpart to the
            // memory the matcher now keeps (see [SportsChannelMemory]).
            Text(
                text = "CHANNEL MEMORY",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBTextLo
            )
            ClearMemoryRow(onClear = onClearMemory)

            KBButton(label = "DONE", onClick = onClose)
        }
    }
}

@Composable
private fun LeagueToggleRow(
    league: SportsLeague,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    focusRequester: FocusRequester? = null,
    rowFocus: LeagueRowFocus,
    onMoveFocus: (LeagueRowFocus.Slot) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The toggle keeps the row's whole width minus the two move stops, as
        // it had before they existed - and it is still the row's first focus
        // target, which is what focus on open lands on.
        KBCard(
            onClick = { onToggle(!checked) },
            modifier = Modifier
                .weight(1f)
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeChip)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = league.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KBTextHi
                )
                Text(
                    text = if (checked) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (checked) KBVoid else KBTextLo,
                    modifier = Modifier
                        .background(if (checked) KBAccent else KBSurfaceRaised, KBShapePill)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        // Siblings of the toggle, not children of it: a nested focusable inside
        // a focusable card is a D-pad dead zone, and these two have to be
        // reachable on their own to be usable with a remote.
        LeagueMoveButton(
            up = true,
            enabled = canMoveUp,
            onClick = {
                // Record the stop BEFORE the move lands, so the panel can put
                // focus back on this same arrow after the list rebuilds.
                onMoveFocus(LeagueRowFocus.Slot.UP)
                onMoveUp()
            },
            focusRequester = rowFocus.up
        )
        LeagueMoveButton(
            up = false,
            enabled = canMoveDown,
            onClick = {
                onMoveFocus(LeagueRowFocus.Slot.DOWN)
                onMoveDown()
            },
            focusRequester = rowFocus.down
        )
    }
}

/**
 * One reorder stop beside a league's toggle: ▲ moves it up a place, ▼ down.
 *
 * Disabled at the ends of the list, and rendered as a plain non-focusable plate
 * there rather than a focusable dead stop - the same rule the sheet's WATCH
 * button follows when a game has no channel, so the D-pad never lands on a
 * control that cannot do anything.
 */
@Composable
private fun LeagueMoveButton(
    up: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val glyph = if (up) "▲" else "▼"
    if (!enabled) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(KBSurfaceRaised, KBShapePill),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.labelSmall,
                color = KBTextLo.copy(alpha = 0.3f)
            )
        }
        return
    }
    KBCard(
        onClick = onClick,
        shape = KBShapePill,
        focusedScale = KBFocusChip,
        modifier = Modifier
            .size(40.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.labelSmall,
                color = KBTextHi
            )
        }
    }
}

/**
 * The reminder switch, shaped like every other toggle in this panel. It is on
 * by default, because following a team is itself the opt-in; the worker is
 * only ever armed while at least one team is followed, so leaving it on costs
 * nothing to a viewer who follows nobody.
 */
@Composable
private fun ReminderToggleRow(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    KBCard(onClick = { onToggle(!checked) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised, KBShapeChip)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Notify me when followed teams play",
                style = MaterialTheme.typography.bodyMedium,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (checked) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                color = if (checked) KBVoid else KBTextLo,
                modifier = Modifier
                    .background(if (checked) KBAccent else KBSurfaceRaised, KBShapePill)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * The one row that forgets the matcher's learned team -> channel picks.
 *
 * A press clears the memory and the row reports "CLEARED" for a moment, so a
 * remote press with no other visible effect is still acknowledged. The memory
 * is only read at the start of a match pass, so nothing behind the panel
 * changes now - the next refresh starts from the heuristics again.
 */
@Composable
private fun ClearMemoryRow(onClear: () -> Unit) {
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(cleared) {
        if (cleared) {
            kotlinx.coroutines.delay(1_500)
            cleared = false
        }
    }
    KBCard(
        onClick = {
            onClear()
            cleared = true
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised, KBShapeChip)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Forget learned channel picks",
                style = MaterialTheme.typography.bodyMedium,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (cleared) "CLEARED" else "CLEAR",
                style = MaterialTheme.typography.labelSmall,
                color = if (cleared) KBVoid else KBTextLo,
                modifier = Modifier
                    .background(if (cleared) KBAccent else KBSurfaceRaised, KBShapePill)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}
