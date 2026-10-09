package com.kennyb1201.kbstream.ui.sports

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.Leader
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.StandingEntry
import com.kennyb1201.kbstream.data.sports.StandingGroup
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
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
 */
@Composable
fun SportsHubScreen(
    viewModel: SportsHubViewModel = viewModel(),
    modifier: Modifier = Modifier,
    onPlayChannels: (List<IptvChannel>) -> Unit = {},
) {
    val enabled by viewModel.enabledLeagues.collectAsStateWithLifecycle()
    val leagueOrder by viewModel.leagueOrder.collectAsStateWithLifecycle()
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

    // Keep a tab selected as the catalog changes: the first enabled league is
    // the sensible default, and a league that was just switched off must not
    // stay the selection (its tab is gone).
    LaunchedEffect(tabs) {
        val current = selectedPath
        if (current == null || tabs.none { it.path == current }) {
            leagues.firstOrNull()?.let { viewModel.selectLeague(it.path) }
        }
    }

    // One list state for the league body, owned here rather than inside it, so
    // the D-pad fallback below can scroll the cards even when focus is still up
    // on the tab row.
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // A game the playlist cannot carry is deliberately NOT focusable - a run of
    // dead cards should not cost the D-pad a stop - so focus alone cannot walk
    // past one, and a section of them has no focus target below it for the
    // LazyColumn to scroll to. That is the "it won't scroll down into the
    // games" the viewer hits on a slate their playlist only half covers.
    //
    // So: Down/Up first gets the platform's own move - which is what a press
    // that HAS a target still does - and only when there is nothing to focus
    // does the press become a scroll.
    val scrollByPage: (FocusDirection) -> Boolean = { direction ->
        val info = listState.layoutInfo
        val viewport = info.viewportSize.height -
            info.beforeContentPadding - info.afterContentPadding
        val canScroll = if (direction == FocusDirection.Down) {
            listState.canScrollForward
        } else {
            listState.canScrollBackward
        }
        if (!canScroll || viewport <= 0) {
            false
        } else {
            val row = info.visibleItemsInfo.lastOrNull()?.size ?: viewport
            val step = sportsScrollStep(rowHeight = row, viewportHeight = viewport)
            scrollScope.launch {
                listState.animateScrollBy(
                    if (direction == FocusDirection.Down) step.toFloat() else -step.toFloat()
                )
            }
            true
        }
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
                onSelect = viewModel::selectLeague,
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
                        section = results,
                        matches = matches,
                        favoriteKeys = favoriteKeys,
                        listState = listState,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone,
                        onOpenDetail = { detailGame = it },
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
                        section = section,
                        matches = matches,
                        favoriteKeys = favoriteKeys,
                        listState = listState,
                        onOpenDetail = { detailGame = it },
                        onPlayChannels = onPlayChannels,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone,
                        onEditFavorites = { favoriteEditor = it },
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
                    section = section,
                    matches = matches,
                    favoriteKeys = favoriteKeys,
                    listState = listState,
                    lineupMissing = lineupMissing,
                    matchingDone = matchingDone,
                    onOpenDetail = { detailGame = it },
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
                // Picking a feed OTHER than the matched head is the viewer
                // correcting the matcher; record it so the next game with
                // either team opens there. See [SportsChannelMemory].
                onManualPick = { chosen -> viewModel.rememberChannel(game, chosen.id) },
                onPlay = { feeds ->
                    detailGame = null
                    onPlayChannels(feeds)
                },
                onClose = { detailGame = null },
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
            KBPageTitle(text = "SPORTS")
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
            LeagueTab(
                label = league.label,
                selected = league.path == selectedPath,
                onClick = { onSelect(league.path) },
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
) {
    KBCard(
        onClick = onClick,
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

// ── A league's cards ────────────────────────────────────────────────────

@Composable
private fun LeagueBody(
    section: LeagueSection,
    matches: Map<String, List<IptvChannel>>,
    favoriteKeys: Set<String>,
    listState: LazyListState,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onPlayChannels: (List<IptvChannel>) -> Unit,
    onEditFavorites: (SportsGame) -> Unit,
) {
    val live = section.games.filter { it.state == GameState.LIVE }
    val upcoming = section.games
        .filter { it.state == GameState.UPCOMING }
        .sortedBy { it.dateMs }
    val final = section.games
        .filter { it.state == GameState.FINAL }
        .sortedByDescending { it.dateMs }

    // Two cards to a row unless the display cannot hold them side by side.
    val gameColumns = if (
        LocalConfiguration.current.screenWidthDp >= TWO_COLUMN_MIN_WIDTH_DP
    ) 2 else 1

    // ONE pulse for the whole league, shared by every live dot it draws. Each
    // live card used to run its own infinite transition - the same beat, but N
    // animation clocks, each recomposing its own card on its own frame.
    val livePulse = rememberLivePulse()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (live.isNotEmpty()) {
            item(key = "header-live") {
                SportsSectionHeader(title = "LIVE NOW", accent = true, count = live.size)
            }
            // Live games keep a row to themselves: the clock, the badge and the
            // score are why they are at the top, and half a row cannot carry
            // them.
            gameRows(
                games = live,
                columns = 1,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites
            )
        }

        // Tournaments (golf, F1) are their own events, not head-to-head games,
        // and they sort with whatever is on now. A leaderboard wants the width,
        // so they are one to a row whatever the display.
        if (section.tournaments.isNotEmpty()) {
            item(key = "header-tournaments") {
                SportsSectionHeader(
                    title = "TOURNAMENTS",
                    accent = section.tournaments.any { it.state == GameState.LIVE },
                    count = section.tournaments.size
                )
            }
            items(section.tournaments, key = { it.id }) { event ->
                TournamentCard(
                    event = event,
                    channels = matches[event.id].orEmpty(),
                    livePulse = livePulse,
                    lineupMissing = lineupMissing,
                    matchingDone = matchingDone,
                    onPlayChannels = onPlayChannels,
                )
            }
        }

        if (upcoming.isNotEmpty()) {
            item(key = "header-upcoming") {
                SportsSectionHeader(title = "UPCOMING", count = upcoming.size)
            }
            gameRows(
                games = upcoming,
                columns = gameColumns,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites
            )
        }

        if (final.isNotEmpty()) {
            item(key = "header-final") {
                SportsSectionHeader(title = "FINAL", count = final.size)
            }
            gameRows(
                games = final,
                columns = gameColumns,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites
            )
        }
    }
}

/**
 * The game cards of one section, laid out [columns] to a row with a
 * [GRID_GUTTER_DP] gutter and one [CARD_GAP_DP] gap between rows.
 *
 * Rows are built here rather than through a lazy grid because the list is a
 * single lazy column of SECTIONS - headers, games, tournaments, headers - and a
 * LazyVerticalGrid cannot carry the headers that way without nesting two
 * scrollers. Chunking a section's games keeps one scroll container and one
 * focus walk, which is what the D-pad order depends on: rows in order, left to
 * right inside a row.
 *
 * A short last row keeps its cards the same width as a full one. Stretching a
 * lone final card across the whole row would read a different layout rather
 * than the last line of the same one.
 */
private fun LazyListScope.gameRows(
    games: List<SportsGame>,
    columns: Int,
    matches: Map<String, List<IptvChannel>>,
    livePulse: Float,
    favoriteKeys: Set<String>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onEditFavorites: (SportsGame) -> Unit,
) {
    games.chunked(columns).forEach { row ->
        item(key = "row-" + row.joinToString("-") { it.id }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp)
            ) {
                row.forEach { game ->
                    GameCard(
                        game = game,
                        channel = matches.primaryChannel(game.id),
                        livePulse = livePulse,
                        favoriteKeys = favoriteKeys,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone,
                        onOpenDetail = onOpenDetail,
                        onEditFavorites = { onEditFavorites(game) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
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
    livePulse: Float,
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
                TeamColumn(
                    team = game.away,
                    favorite = game.away.favoriteKey in favoriteKeys,
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
                    alignEnd = true,
                    modifier = Modifier.weight(1f)
                )
            }

            ScoreRow(game = game)

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
        focusedScale = KBFocusRow,
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
    modifier: Modifier = Modifier,
) {
    // The mark over the name, not beside it: with two cards sharing a row the
    // side-by-side form left the crest floating in the middle of its half, and
    // the code that identifies the team needs to sit under the crest it
    // belongs to.
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        TeamMark(team = team)
        Spacer(modifier = Modifier.height(6.dp))
        // "TB · 4-1": the code and the season record on one line, which is how
        // a scoreboard says it. The record is simply absent where the feed
        // carries none (tennis, MMA) and the line is the code alone.
        Text(
            // The star is the whole "this is one of yours" signal, so it sits on
            // the code the way a broadcast mark sits on a channel: no second
            // row, no badge to decode.
            text = listOfNotNull(
                team.abbreviation.ifBlank { team.displayName },
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

/**
 * The two scores, each under its own side and larger than anything else on the
 * card - the score is what the card is for.
 *
 * A game that has not started draws no row at all rather than two empty
 * columns: "no score yet" and "0-0" must not look alike. A live score is set
 * heavier than a final's, so the one that is moving right now reads first from
 * across the room.
 */
@Composable
private fun ScoreRow(game: SportsGame) {
    val away = game.away.score?.takeIf { it.isNotBlank() }
    val home = game.home.score?.takeIf { it.isNotBlank() }
    if (away == null && home == null) return
    val live = game.state == GameState.LIVE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
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
private fun TeamMark(team: SportsTeam, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
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
private fun GameStatusLine(game: SportsGame, livePulse: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (game.state) {
            GameState.LIVE -> {
                LiveDot(alpha = livePulse)
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
 */
@Composable
private fun rememberLivePulse(): Float {
    val reducedMotion = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "sportsLive")
    val animated by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "sportsLivePulse"
    )
    return if (reducedMotion) 1f else animated
}

/**
 * The pulsing red live dot. [alpha] is the section's shared pulse, so the dots
 * on a slate of live games breathe together instead of drifting apart.
 */
@Composable
private fun LiveDot(alpha: Float, modifier: Modifier = Modifier) {
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
            color = KBTextLo.copy(alpha = 0.8f)
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
    livePulse: Float,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onPlayChannels: (List<IptvChannel>) -> Unit,
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
                    if (event.state == GameState.LIVE) LiveDot(alpha = livePulse)
                    Text(
                        text = when {
                            event.state == GameState.LIVE ->
                                event.statusDetail.ifBlank { "LIVE" }
                            event.state == GameState.FINAL -> "Final"
                            event.dateMs > 0L ->
                                DateFormats.time(event.dateMs, DateFormats.WEEKDAY_CLOCK_12H)
                            else -> event.statusDetail
                        },
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
            onClick = { onPlayChannels(channels) },
            focusedScale = KBFocusRow,
            shape = KBShapeCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            body()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KBShapeCard,
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface,
                contentColor = KBTextHi
            )
        ) {
            Box(modifier = Modifier.fillMaxWidth()) { body() }
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
            LaunchedEffect(Unit) {
                runCatching { firstRow.requestFocus() }
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
    KBCard(onClick = onClick, focusedScale = KBFocusChip, shape = KBShapePill) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) KBVoid else KBTextLo,
            modifier = Modifier
                .background(if (selected) KBAccent else KBSurfaceRaised, KBShapePill)
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
    listState: LazyListState,
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
                        StandingsColumn(
                            groups = columnGroups,
                            listState = rememberLazyListState(),
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
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
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
            ) { _, entry -> StandingsRow(entry = entry) }
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
private fun StandingsRow(entry: StandingEntry) {
    KBCard(
        // Navigable like a game card, with no tap action of its own: an empty
        // lambda keeps the row a D-pad stop without giving it a destination.
        onClick = {},
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
                text = entry.abbreviation,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBTextHi,
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
 * The full matchup for one game, raised by a tap on its card.
 *
 * A bottom sheet rather than a centred dialog: it is the card, opened - the
 * same facts in more room - so it rises from the bottom edge and leaves the hub
 * visible above it. It fetches nothing (it renders the already-loaded
 * [SportsGame]), so there is never a spinner in it, and its one action is
 * Watch, which hands the matched channel to the same launch a card tap used to
 * take. With no matched channel the button is disabled and says so.
 */
@Composable
private fun GameDetailSheet(
    game: SportsGame,
    channels: List<IptvChannel>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onManualPick: (IptvChannel) -> Unit = {},
    onPlay: (List<IptvChannel>) -> Unit,
    onClose: () -> Unit,
) {
    val watchButton = remember { FocusRequester() }
    val channel = channels.firstOrNull()
    val backups = channels.drop(1)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Bounded against the window (the leagues panel's own remedy for
            // the same class of bug): a plate that wrapped its content grew
            // taller than the screen and then SCROLLED, and the scroll cropped
            // the crests off the top edge.
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.92f)
                    .focusGroup()
                    .background(KBSurface, KBShapePanel)
                    .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                // record, the card's own sides in a wider plate.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamColumn(team = game.away, favorite = false, modifier = Modifier.weight(1f))
                    Text(
                        text = "@",
                        style = MaterialTheme.typography.titleSmall,
                        color = KBTextLo,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    TeamColumn(
                        team = game.home,
                        favorite = false,
                        alignEnd = true,
                        modifier = Modifier.weight(1f)
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

                // The crests, the matchup and the score are PINNED - only the
                // body below them scrolls. That split is the actual fix: the
                // sheet focuses WATCH the moment it opens, Compose brings the
                // focused button into view by scrolling, and scrolling the whole
                // column carried the crest row out through the plate's top edge,
                // which is the cropped logos. A pinned header cannot be scrolled
                // away.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Every row is already filtered to the non-blanks, so a game
                    // with no venue and no leaders simply draws fewer rows.
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

                    Spacer(modifier = Modifier.height(4.dp))

                    if (channel != null) {
                        KBCard(
                            // The whole ordered list, head first: the one-press
                            // path is the feed the matcher chose, and the rest
                            // ride along behind it so the player's own ladder
                            // has somewhere to fall when that feed will not open.
                            onClick = { onPlay(channels) },
                            focusedScale = KBFocusRow,
                            shape = KBShapePill,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(watchButton)
                        ) {
                            WatchButtonLabel(
                                label = SportsDetailRules.watchLabel(true),
                                enabled = true
                            )
                        }
                        LaunchedEffect(Unit) { runCatching { watchButton.requestFocus() } }
                    } else {
                        // Not focusable: a disabled action must not be a D-pad stop.
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
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

                    // The other feeds the playlist holds for this game - the
                    // second channel the guide is airing it on, the network's
                    // alternates, the team's own regional network. Drawn only
                    // when there is one: a "backups" heading over an empty list
                    // would promise a choice that does not exist.
                    if (backups.isNotEmpty()) {
                        Text(
                            text = "BACKUP CHANNELS",
                            style = MaterialTheme.typography.labelSmall,
                            color = KBTextLo,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        backups.forEach { backup ->
                            KBCard(
                                // The chosen feed goes first and the rest follow
                                // in their own order, so picking a backup is a
                                // switch rather than a decision to lose the
                                // other feeds.
                                onClick = {
                                    onManualPick(backup)
                                    onPlay(listOf(backup) + channels.filter { it.id != backup.id })
                                },
                                focusedScale = KBFocusRow,
                                shape = KBShapePill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BackupChannelLabel(channel = backup)
                            }
                        }
                    }
                }
            }
        }
    }
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
            LaunchedEffect(Unit) {
                runCatching { firstRow.requestFocus() }
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
                    LeagueToggleRow(
                        league = league,
                        checked = league.path in enabled,
                        onToggle = { on -> onToggle(league.path, on) },
                        canMoveUp = index > 0,
                        canMoveDown = index < orderedLeagues.lastIndex,
                        onMoveUp = { onMove(league.path, -1) },
                        onMoveDown = { onMove(league.path, 1) },
                        focusRequester = firstRow.takeIf { index == 0 }
                    )
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
        LeagueMoveButton(up = true, enabled = canMoveUp, onClick = onMoveUp)
        LeagueMoveButton(up = false, enabled = canMoveDown, onClick = onMoveDown)
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
private fun LeagueMoveButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
        modifier = Modifier.size(40.dp)
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
