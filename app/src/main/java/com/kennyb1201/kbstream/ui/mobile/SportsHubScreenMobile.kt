package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.data.sports.standingsTeam
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.sports.GameDetailRow
import com.kennyb1201.kbstream.ui.sports.LeagueSection
import com.kennyb1201.kbstream.ui.sports.LeagueView
import com.kennyb1201.kbstream.ui.sports.LineupStatus
import com.kennyb1201.kbstream.ui.sports.SportsDetailRules
import com.kennyb1201.kbstream.ui.sports.SportsGameOrder
import com.kennyb1201.kbstream.ui.sports.SportsHubViewModel
import com.kennyb1201.kbstream.ui.sports.SportsReopenRules
import com.kennyb1201.kbstream.ui.sports.SportsSearchRules
import com.kennyb1201.kbstream.ui.sports.SportsStandingsLayout
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.OswaldFamily

/*
 * The sports hub, for a finger.
 *
 * Same activity-scoped [SportsHubViewModel] as the TV page, so the enabled
 * leagues, the tab order, the follow set, the 30-second poll, the standings
 * cache and the channel memory are one set of facts for both form factors. What
 * changes is the shape:
 *
 *  - The league tabs and the search field are one row each at the top; the slate
 *    below scrolls as a single column (a phone has no room for two cards, and a
 *    live card was already full-width on TV).
 *  - A tap opens the card's own sheet - the same matchup, stats, venue rows,
 *    leaders and backup feeds the TV sheet shows - with WATCH pinned at the
 *    bottom; a long press opens the follow dialog, exactly as on the D-pad.
 *  - The leagues panel and the follow dialog become ordinary dialogs, since
 *    both are already modal on TV.
 *
 * The rules are imported, not re-derived: [SportsDetailRules] for every string
 * the card and the sheet share, [SportsGameOrder] for the favourites-first
 * partition, [SportsSearchRules] for what the field searches, [SportsReopenRules]
 * for the return-from-the-player sheet, [SportsStandingsLayout] for the table's
 * columns. A phone slate therefore means exactly what a TV slate means.
 */

@Composable
fun SportsHubScreenMobile(
    viewModel: SportsHubViewModel = viewModel(),
    modifier: Modifier = Modifier,
    onPlayChannels: (gameId: String?, tournamentId: String?, List<IptvChannel>) -> Unit =
        { _, _, _ -> },
    initialDetailGameId: String? = null,
    initialDetailTournamentId: String? = null,
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
    val detailSummary by viewModel.detailSummary.collectAsStateWithLifecycle()
    val lineupMissing = lineupStatus == LineupStatus.MISSING

    val leagues = remember(enabled, leagueOrder) { SportsLeagues.enabled(enabled, leagueOrder) }
    val tabs = remember(leagues) { listOf(SportsLeagues.FAVORITES) + leagues }

    // The same fresh-hub default as the TV page: the viewer's own teams when
    // they follow anyone, the first enabled league otherwise. Keyed on the tabs
    // alone, so a star toggled while the hub is open cannot move the selection.
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

    // Seeded from the ViewModel's memory of where the hub was left, because the
    // player is a Screen in this activity and opening a game disposes this page.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.hubScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.hubScrollOffset
    )

    DisposableEffect(Unit) {
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

    var searchQuery by remember { mutableStateOf("") }
    var detailGame by remember { mutableStateOf<SportsGame?>(null) }
    var detailTournament by remember { mutableStateOf<TournamentEvent?>(null) }
    var favoriteEditor by remember { mutableStateOf<SportsGame?>(null) }
    var showLeagues by remember { mutableStateOf(false) }

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

    val sheetOpen = detailGame != null || detailTournament != null

    // The sheet's fetch policy, exactly as on TV: a live game's summary is read
    // while its sheet is open, and closing it stops the tick.
    LaunchedEffect(detailGame) {
        val open = detailGame
        if (open == null) viewModel.closeDetail() else viewModel.openDetail(open)
    }

    // The phone's Back ladder inside the search: a query is a view of the hub,
    // so Back returns to the full slate before it leaves the screen. The dialogs
    // handle their own dismissal (a Dialog consumes Back).
    BackHandler(enabled = searchQuery.isNotEmpty()) { searchQuery = "" }

    MobileTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SportsHeaderMobile(
                    onLeaguesClick = { showLeagues = true },
                    onRefreshClick = viewModel::refresh
                )

                if (leagues.isEmpty()) {
                    SportsStatusMobile(
                        title = "No leagues enabled",
                        message = "Turn on a league to see its games and the channel " +
                            "carrying them.",
                        actionLabel = "CHOOSE LEAGUES",
                        onAction = { showLeagues = true },
                        modifier = Modifier.fillMaxSize()
                    )
                    return@Column
                }

                LeagueTabsMobile(
                    leagues = tabs,
                    selectedPath = selectedPath,
                    onSelect = { path ->
                        if (path != selectedPath) {
                            viewModel.forgetHubScroll()
                            viewModel.selectLeague(path)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                KBTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search teams or events…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (lineupMissing) {
                    LineupMissingNoticeMobile(
                        onRetry = viewModel::retryLineup,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                val favoritesTab = selectedPath == SportsLeagues.FAVORITES.path
                val selectedLeague = leagues.firstOrNull { it.path == selectedPath }
                val section = if (favoritesTab) {
                    favoritesSection
                } else {
                    sections.firstOrNull { it.league.path == selectedPath }
                }

                if (SportsSearchRules.isActive(searchQuery)) {
                    val searchSections = if (favoritesTab) {
                        listOf(favoritesSection)
                    } else {
                        sections
                    }
                    val searchLeague = if (favoritesTab) {
                        SportsLeagues.FAVORITES
                    } else {
                        SportsSearchRules.RESULTS_LEAGUE
                    }
                    val results =
                        SportsSearchRules.results(searchSections, searchQuery, searchLeague)
                    if (results == null) {
                        SportsStatusMobile(
                            title = "No games found",
                            message = "Nothing matching \"${searchQuery.trim()}\" is on " +
                                "the loaded schedule.",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            SearchResultsLineMobile(
                                games = results.games.size,
                                events = results.tournaments.size,
                                leagues = SportsSearchRules
                                    .leaguesWithHits(searchSections, searchQuery)
                            )
                        }
                        LeagueBodyMobile(
                            section = results,
                            matches = matches,
                            favoriteKeys = favoriteKeys,
                            listState = listState,
                            lineupMissing = lineupMissing,
                            matchingDone = matchingDone,
                            pulseEnabled = !sheetOpen,
                            onOpenDetail = { detailGame = it },
                            onOpenTournamentDetail = { detailTournament = it },
                            onPlayChannels = onPlayChannels,
                            onEditFavorites = { favoriteEditor = it }
                        )
                    }
                    return@Column
                }

                val standingsAvailable = !favoritesTab && selectedLeague?.hasStandings == true
                if (standingsAvailable) {
                    LeagueViewToggleMobile(
                        selected = leagueView,
                        onSelect = viewModel::setView,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val standingsLeague = selectedLeague
                    .takeIf { standingsAvailable && leagueView == LeagueView.STANDINGS }
                if (standingsLeague != null) {
                    StandingsBodyMobile(
                        league = standingsLeague,
                        groups = standings[standingsLeague.path],
                        loading = standingsLeague.path in standingsLoading,
                        failed = standingsLeague.path in standingsFailed,
                        favoriteKeys = favoriteKeys,
                        onToggleTeam = { team, on -> viewModel.setTeamFavorite(team, on) },
                        onRetry = viewModel::retryStandings
                    )
                    return@Column
                }

                when {
                    section == null -> SportsStatusMobile(
                        title = "Loading scores",
                        message = "Fetching today's games…",
                        modifier = Modifier.fillMaxSize()
                    )

                    favoritesTab && favoriteKeys.isEmpty() -> SportsStatusMobile(
                        title = "No favorite teams yet",
                        message = "Long-press a game on any league tab and pick the teams " +
                            "you follow.",
                        modifier = Modifier.fillMaxSize()
                    )

                    favoritesTab -> when {
                        section.games.isNotEmpty() -> LeagueBodyMobile(
                            section = section,
                            matches = matches,
                            favoriteKeys = favoriteKeys,
                            listState = listState,
                            lineupMissing = lineupMissing,
                            matchingDone = matchingDone,
                            pulseEnabled = !sheetOpen,
                            onOpenDetail = { detailGame = it },
                            onOpenTournamentDetail = { detailTournament = it },
                            onPlayChannels = onPlayChannels,
                            onEditFavorites = { favoriteEditor = it }
                        )

                        sections.any { it.failed } -> SportsStatusMobile(
                            title = "Couldn't reach ESPN",
                            message = "Scores are unavailable right now.",
                            actionLabel = "TRY AGAIN",
                            onAction = viewModel::refresh,
                            modifier = Modifier.fillMaxSize()
                        )

                        else -> SportsStatusMobile(
                            title = "Nothing on",
                            message = "None of your teams are playing right now.",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    section.failed && section.games.isEmpty() &&
                        section.tournaments.isEmpty() -> SportsStatusMobile(
                        title = "Couldn't reach ESPN",
                        message = "Scores for ${section.league.label} are unavailable " +
                            "right now.",
                        actionLabel = "TRY AGAIN",
                        onAction = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()
                    )

                    section.games.isEmpty() && section.tournaments.isEmpty() ->
                        SportsStatusMobile(
                            title = "Nothing on",
                            message = "No ${section.league.label} games on the schedule " +
                                "right now.",
                            modifier = Modifier.fillMaxSize()
                        )

                    else -> LeagueBodyMobile(
                        section = section,
                        matches = matches,
                        favoriteKeys = favoriteKeys,
                        listState = listState,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone,
                        pulseEnabled = !sheetOpen,
                        onOpenDetail = { detailGame = it },
                        onOpenTournamentDetail = { detailTournament = it },
                        onPlayChannels = onPlayChannels,
                        onEditFavorites = { favoriteEditor = it }
                    )
                }
            }

            if (isLoading && sections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(KBVoid)
                ) {
                    SportsStatusMobile(
                        title = "Loading sports",
                        message = "Fetching today's games…",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (showLeagues) {
            LeaguesPanelMobile(
                enabled = enabled,
                order = leagueOrder,
                reminders = gameReminders,
                onToggle = viewModel::setLeagueEnabled,
                onToggleReminders = viewModel::setGameReminders,
                onMove = viewModel::moveLeague,
                onClearMemory = viewModel::clearChannelMemory,
                onClose = { showLeagues = false }
            )
        }

        favoriteEditor?.let { game ->
            FavoriteTeamsDialogMobile(
                game = game,
                favoriteKeys = favoriteKeys,
                onToggle = { team, on -> viewModel.setTeamFavorite(team, on) },
                onClose = { favoriteEditor = null }
            )
        }

        detailGame?.let { game ->
            GameDetailSheetMobile(
                game = game,
                channels = matches[game.id].orEmpty(),
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                summary = detailSummary,
                onManualPick = { chosen -> viewModel.rememberChannel(game, chosen.id) },
                onPlay = { feeds ->
                    detailGame = null
                    onPlayChannels(game.id, null, feeds)
                },
                onClose = { detailGame = null }
            )
        }

        detailTournament?.let { event ->
            TournamentDetailSheetMobile(
                event = event,
                channels = matches[event.id].orEmpty(),
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onManualPick = { chosen ->
                    viewModel.rememberTournamentChannel(event, chosen.id)
                },
                onPlay = { feeds ->
                    detailTournament = null
                    onPlayChannels(null, event.id, feeds)
                },
                onClose = { detailTournament = null }
            )
        }
    }
}

// ── Header, tabs, search ───────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SportsHeaderMobile(
    onLeaguesClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(
            text = "SPORTS HUB",
            color = KBAccent,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Live scores • tap to watch • long-press a game to follow its teams",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            KBButton(label = "REFRESH", onClick = onRefreshClick)
            KBButton(label = "LEAGUES", onClick = onLeaguesClick)
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun LeagueTabsMobile(
    leagues: List<SportsLeague>,
    selectedPath: String?,
    onSelect: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(items = leagues, key = { _, league -> league.path }) { _, league ->
            val selected = league.path == selectedPath
            KBCard(onClick = { onSelect(league.path) }, shape = KBShapePill) {
                Text(
                    text = league.label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) KBVoid else KBTextHi,
                    maxLines = 1,
                    modifier = Modifier
                        .background(
                            if (selected) KBAccent else KBSurfaceRaised,
                            KBShapePill
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchResultsLineMobile(games: Int, events: Int, leagues: Int) {
    val total = games + events
    Text(
        text = "$total RESULT${if (total == 1) "" else "S"} · " +
            "$leagues LEAGUE${if (leagues == 1) "" else "S"}",
        style = MaterialTheme.typography.labelSmall,
        color = KBTextLo,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun LeagueViewToggleMobile(
    selected: LeagueView,
    onSelect: (LeagueView) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        LeagueViewChipMobile("GAMES", selected == LeagueView.GAMES) {
            onSelect(LeagueView.GAMES)
        }
        LeagueViewChipMobile("STANDINGS", selected == LeagueView.STANDINGS) {
            onSelect(LeagueView.STANDINGS)
        }
    }
}

@Composable
private fun LeagueViewChipMobile(label: String, selected: Boolean, onClick: () -> Unit) {
    KBCard(onClick = onClick, shape = KBShapePill) {
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

@Composable
private fun LineupMissingNoticeMobile(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    KBCard(onClick = onRetry, shape = KBShapeCard, modifier = modifier.fillMaxWidth()) {
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

@Composable
private fun SportsStatusMobile(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .fillMaxWidth()
            .background(KBSurface, KBShapeCard)
            .padding(18.dp)
    ) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            KBButton(label = actionLabel, onClick = onAction)
        }
    }
}

// ── The slate ──────────────────────────────────────────────────────────

@Composable
private fun LeagueBodyMobile(
    section: LeagueSection,
    matches: Map<String, List<IptvChannel>>,
    favoriteKeys: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    pulseEnabled: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onOpenTournamentDetail: (TournamentEvent) -> Unit,
    onPlayChannels: (gameId: String?, tournamentId: String?, List<IptvChannel>) -> Unit,
    onEditFavorites: (SportsGame) -> Unit
) {
    // The TV page's own partition and its own favourites-first order, so the
    // groups on a phone are the groups on TV and a followed team leads each one.
    val live = SportsGameOrder.favoritesFirst(
        section.games.filter { it.state == GameState.LIVE },
        favoriteKeys
    )
    val upcoming = SportsGameOrder.favoritesFirst(
        section.games.filter { it.state == GameState.UPCOMING }.sortedBy { it.dateMs },
        favoriteKeys
    )
    val final = SportsGameOrder.favoritesFirst(
        section.games.filter { it.state == GameState.FINAL }.sortedByDescending { it.dateMs },
        favoriteKeys
    )

    // One pulse for the whole league, held while a sheet is over it.
    val livePulse = rememberLivePulseMobile(enabled = pulseEnabled)

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (live.isNotEmpty()) {
            item(key = "header-live") {
                SportsSectionHeaderMobile(title = "LIVE NOW", accent = true, count = live.size)
            }
            gameItemsMobile(
                games = live,
                matches = matches,
                livePulse = livePulse,
                favoriteKeys = favoriteKeys,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone,
                onOpenDetail = onOpenDetail,
                onEditFavorites = onEditFavorites
            )
        }

        if (section.tournaments.isNotEmpty()) {
            item(key = "header-tournaments") {
                SportsSectionHeaderMobile(
                    title = "TOURNAMENTS",
                    accent = section.tournaments.any { it.state == GameState.LIVE },
                    count = section.tournaments.size
                )
            }
            items(items = section.tournaments, key = { it.id }) { event ->
                TournamentCardMobile(
                    event = event,
                    channels = matches[event.id].orEmpty(),
                    livePulse = livePulse,
                    lineupMissing = lineupMissing,
                    matchingDone = matchingDone,
                    onPlayChannels = onPlayChannels,
                    onOpenDetail = onOpenTournamentDetail
                )
            }
        }

        if (upcoming.isNotEmpty()) {
            item(key = "header-upcoming") {
                SportsSectionHeaderMobile(title = "UPCOMING", count = upcoming.size)
            }
            gameItemsMobile(
                games = upcoming,
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
                SportsSectionHeaderMobile(title = "FINAL", count = final.size)
            }
            gameItemsMobile(
                games = final,
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
 * One card per game, keyed by the GAME's own id so the 30-second poll moving it
 * between live, upcoming and final never re-keys it (the TV grid's rule, which
 * a phone list needs for the same reason).
 */
private fun LazyListScope.gameItemsMobile(
    games: List<SportsGame>,
    matches: Map<String, List<IptvChannel>>,
    livePulse: State<Float>,
    favoriteKeys: Set<String>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: (SportsGame) -> Unit,
    onEditFavorites: (SportsGame) -> Unit
) {
    items(items = games, key = { it.id }) { game ->
        GameCardMobile(
            game = game,
            channel = matches[game.id]?.firstOrNull(),
            livePulse = livePulse,
            favoriteKeys = favoriteKeys,
            lineupMissing = lineupMissing,
            matchingDone = matchingDone,
            onOpenDetail = { onOpenDetail(game) },
            onEditFavorites = { onEditFavorites(game) }
        )
    }
}

@Composable
private fun SportsSectionHeaderMobile(title: String, accent: Boolean = false, count: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCardMobile(
    game: SportsGame,
    channel: IptvChannel?,
    livePulse: State<Float>,
    favoriteKeys: Set<String>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onOpenDetail: () -> Unit,
    onEditFavorites: () -> Unit
) {
    val context = game.week?.takeIf { it.isNotBlank() }
        ?: game.venue?.takeIf { it.isNotBlank() }

    KBCard(
        onClick = onOpenDetail,
        onLongClick = onEditFavorites,
        shape = KBShapeCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            TeamRowMobile(
                game = game,
                team = game.away,
                favorite = game.away.favoriteKey in favoriteKeys
            )
            Spacer(modifier = Modifier.height(8.dp))
            TeamRowMobile(
                game = game,
                team = game.home,
                favorite = game.home.favoriteKey in favoriteKeys
            )

            game.situation?.takeIf { it.isNotBlank() }?.let { situation ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = situation,
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameStatusLineMobile(game = game, livePulse = livePulse)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                NoteChipMobile(
                    text = game.note?.takeIf { note ->
                        context == null || !note.equals(context, ignoreCase = true)
                    }
                )
                BroadcastChipMobile(name = game.broadcastNames.firstOrNull())
            }

            ContextLineMobile(week = game.week, venue = game.venue)

            Spacer(modifier = Modifier.height(8.dp))

            ChannelLineMobile(
                channel = channel,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone
            )
        }
    }
}

@Composable
private fun TeamRowMobile(game: SportsGame, team: SportsTeam, favorite: Boolean) {
    val possession = SportsDetailRules.possesses(game, team)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamMarkMobile(team = team, size = 34.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (possession) {
                PossessionBallMobile()
                Spacer(modifier = Modifier.width(6.dp))
            }
            val code = listOfNotNull(
                team.rank?.let { "#$it" },
                team.abbreviation.ifBlank { team.displayName }
            ).joinToString(" ")
            Text(
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
        Text(
            text = team.score?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = OswaldFamily,
            fontWeight = FontWeight.Bold,
            color = scoreColor(game, team),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun GameStatusLineMobile(game: SportsGame, livePulse: State<Float>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (game.state) {
            GameState.LIVE -> {
                LiveDotMobile(pulse = livePulse)
                Text(
                    text = game.statusDetail.ifBlank { "LIVE" },
                    style = MaterialTheme.typography.labelLarge,
                    color = KBDanger,
                    fontWeight = FontWeight.SemiBold
                )
            }

            GameState.UPCOMING -> Text(
                text = SportsDetailRules.statusLine(game),
                style = MaterialTheme.typography.bodySmall,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            GameState.FINAL -> Text(
                text = "Final",
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo
            )
        }
    }
}

@Composable
private fun TeamMarkMobile(team: SportsTeam, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(KBShapeChip)
            .background(clubTintMobile(team.colorHex) ?: KBSurfaceRaised)
            .border(1.dp, KBTextLo.copy(alpha = 0.18f), KBShapeChip),
        contentAlignment = Alignment.Center
    ) {
        val mark = team.logoUrl
        if (mark.isNullOrBlank()) {
            TeamInitialsMobile(team = team)
        } else {
            // The letter tile is the plate's content while the crest loads, and
            // stays if it never arrives - a dead URL must not empty the tile.
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(mark)
                    .crossfade(false)
                    .build(),
                contentDescription = team.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size - 8.dp),
                loading = { TeamInitialsMobile(team = team) },
                error = { TeamInitialsMobile(team = team) }
            )
        }
    }
}

@Composable
private fun TeamInitialsMobile(team: SportsTeam) {
    Text(
        text = initialsMobile(team),
        color = KBTextHi,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PossessionBallMobile() {
    Box(
        modifier = Modifier
            .size(width = 11.dp, height = 8.dp)
            .clip(KBShapePill)
            .background(KBAccent)
    )
}

@Composable
private fun NoteChipMobile(text: String?) {
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

@Composable
private fun BroadcastChipMobile(name: String?) {
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

@Composable
private fun ContextLineMobile(week: String?, venue: String?) {
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
 * The bridge, stated plainly: the channel the game is on, or the honest reason
 * there is none yet. The three sentences are the TV card's own (still looking /
 * no lineup / not carried), so a slow hub never reads as a broken one on either
 * form factor.
 */
@Composable
private fun ChannelLineMobile(
    channel: IptvChannel?,
    lineupMissing: Boolean,
    matchingDone: Boolean
) {
    if (channel == null) {
        Text(
            text = when {
                !matchingDone -> "Finding channel…"
                lineupMissing -> "Lineup not loaded"
                else -> "Not in your playlist"
            },
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo.copy(alpha = 0.8f),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TournamentCardMobile(
    event: TournamentEvent,
    channels: List<IptvChannel>,
    livePulse: State<Float>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onPlayChannels: (gameId: String?, tournamentId: String?, List<IptvChannel>) -> Unit,
    onOpenDetail: (TournamentEvent) -> Unit
) {
    val channel = channels.firstOrNull()

    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = event.name.ifBlank { event.league },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KBTextHi,
                maxLines = 2,
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
                    if (event.state == GameState.LIVE) LiveDotMobile(pulse = livePulse)
                    Text(
                        // The shared status rule, so an undated upcoming event
                        // reads "Today" here exactly as it does on TV.
                        text = SportsDetailRules.statusLine(event),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (event.state == GameState.LIVE) KBDanger else KBTextHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            ContextLineMobile(week = null, venue = event.venue)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                NoteChipMobile(text = event.note)
                BroadcastChipMobile(name = event.broadcastNames.firstOrNull())
            }

            if (event.leaders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LeaderboardMobile(leaders = event.leaders)
            }

            Spacer(modifier = Modifier.height(8.dp))
            ChannelLineMobile(
                channel = channel,
                lineupMissing = lineupMissing,
                matchingDone = matchingDone
            )
        }
    }

    // Playable cards play on tap, as the TV card does; a card the playlist
    // cannot carry opens its sheet instead, which is where the reason is stated.
    KBCard(
        onClick = {
            if (channel != null) onPlayChannels(null, null, channels) else onOpenDetail(event)
        },
        shape = KBShapeCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        body()
    }
}

@Composable
private fun LeaderboardMobile(leaders: List<Leader>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        leaders.forEachIndexed { index, leader ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LeaderFlagMobile(leader = leader)
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

@Composable
private fun LeaderFlagMobile(leader: Leader) {
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

// ── Standings ──────────────────────────────────────────────────────────

@Composable
private fun StandingsBodyMobile(
    league: SportsLeague,
    groups: List<StandingGroup>?,
    loading: Boolean,
    failed: Boolean,
    favoriteKeys: Set<String>,
    onToggleTeam: (SportsTeam, Boolean) -> Unit,
    onRetry: () -> Unit
) {
    when {
        groups == null && loading -> SportsStatusMobile(
            title = "Loading standings",
            message = "Reading the ${league.label} table…",
            modifier = Modifier.fillMaxSize()
        )

        groups == null && failed -> SportsStatusMobile(
            title = "Couldn't reach ESPN",
            message = "Standings for ${league.label} are unavailable right now.",
            actionLabel = "TRY AGAIN",
            onAction = onRetry,
            modifier = Modifier.fillMaxSize()
        )

        groups.isNullOrEmpty() -> SportsStatusMobile(
            title = "No standings",
            message = "No standings for ${league.label} right now.",
            modifier = Modifier.fillMaxSize()
        )

        else -> {
            // A phone is always the one-column body: the two-column split needs
            // 900dp+ of width, which is the TV's own threshold.
            val columns = SportsStandingsLayout.columns(groups, widthDp = 0)
            val shown = columns.firstOrNull().orEmpty()
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                shown.forEach { group ->
                    item(key = "std-head-" + group.name) {
                        StandingsGroupHeaderMobile(
                            name = group.name,
                            count = group.entries.size
                        )
                    }
                    itemsIndexed(
                        items = group.entries,
                        key = { _, entry ->
                            "std-" + group.name + "-" + entry.abbreviation + "-" +
                                entry.displayName
                        }
                    ) { _, entry ->
                        val team = standingsTeam(league.path, entry)
                        val followed = team.favoriteKey in favoriteKeys
                        StandingRowMobile(
                            entry = entry,
                            followed = followed,
                            onToggleFollow = { onToggleTeam(team, !followed) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingsGroupHeaderMobile(name: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp)
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

@Composable
private fun StandingRowMobile(
    entry: StandingEntry,
    followed: Boolean,
    onToggleFollow: () -> Unit
) {
    KBCard(
        onClick = onToggleFollow,
        shape = KBShapeCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StandingMarkMobile(entry = entry)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (followed) "★ ${entry.abbreviation}" else entry.abbreviation,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (followed) KBAccent else KBTextHi,
                    maxLines = 1
                )
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // The six table columns, on one line: a phone cannot hold
                    // six fixed-width columns beside a team name, and dropping
                    // any of them would make the table say less than the TV's.
                    text = "${entry.wins}-${entry.losses}-${entry.ties}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = OswaldFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextHi
                )
                Text(
                    text = "PCT ${standingsTextMobile(entry.winPercent)} · " +
                        "GB ${standingsTextMobile(entry.gamesBehind)} · " +
                        "STRK ${standingsTextMobile(entry.streak)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KBTextLo,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StandingMarkMobile(entry: StandingEntry) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(KBShapeChip)
            .background(KBSurfaceRaised)
            .border(1.dp, KBTextLo.copy(alpha = 0.18f), KBShapeChip),
        contentAlignment = Alignment.Center
    ) {
        val mark = entry.logoUrl
        if (mark.isNullOrBlank()) {
            Text(
                text = entry.abbreviation.take(2).uppercase(),
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        } else {
            AsyncImage(
                model = mark,
                contentDescription = entry.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun standingsTextMobile(value: String): String = value.takeIf { it.isNotBlank() } ?: "—"

// ── Detail sheets ──────────────────────────────────────────────────────

@Composable
private fun GameDetailSheetMobile(
    game: SportsGame,
    channels: List<IptvChannel>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    summary: EspnGameSummary?,
    onManualPick: (IptvChannel) -> Unit,
    onPlay: (List<IptvChannel>) -> Unit,
    onClose: () -> Unit
) {
    val channel = channels.firstOrNull()
    val backups = channels.drop(1)

    Dialog(onDismissRequest = onClose) {
        KBDialogPanel(
            title = "MATCHUP",
            subtitle = SportsDetailRules.matchupTitle(game),
            modifier = Modifier.fillMaxHeight(0.92f)
        ) {
            DetailHeaderMobile(game = game)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                summary?.takeIf { SportsDetailRules.hasStats(it) }?.let { stats ->
                    StatsSectionMobile(game = game, summary = stats)
                }

                SportsDetailRules.detailRows(game).forEach { row ->
                    DetailRowMobile(label = row.label, value = row.value)
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
                            color = KBTextHi
                        )
                    }
                }

                if (backups.isNotEmpty()) {
                    Text(
                        text = "BACKUP CHANNELS",
                        style = MaterialTheme.typography.labelSmall,
                        color = KBTextLo,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    backups.forEach { backup ->
                        KBButton(
                            label = backup.displayName.ifBlank { backup.name }.uppercase(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onManualPick(backup)
                                onPlay(listOf(backup) + channels.filter { it.id != backup.id })
                            }
                        )
                    }
                }
            }

            if (channel != null) {
                KBButton(
                    label = SportsDetailRules.watchLabel(true),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPlay(channels) }
                )
            } else {
                KBButton(
                    label = SportsDetailRules.watchLabel(
                        hasChannel = false,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone
                    ),
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun DetailHeaderMobile(game: SportsGame) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamColumnMobile(
                game = game,
                team = game.away,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "@",
                style = MaterialTheme.typography.titleSmall,
                color = KBTextLo,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            TeamColumnMobile(
                game = game,
                team = game.home,
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
    }
}

@Composable
private fun TeamColumnMobile(
    game: SportsGame,
    team: SportsTeam,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        TeamMarkMobile(team = team)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (SportsDetailRules.possesses(game, team)) {
                PossessionBallMobile()
                Spacer(modifier = Modifier.width(6.dp))
            }
            val code = listOfNotNull(
                team.rank?.let { "#$it" },
                team.abbreviation.ifBlank { team.displayName }
            ).joinToString(" ")
            Text(
                text = listOfNotNull(
                    code,
                    team.record?.trim()?.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatsSectionMobile(game: SportsGame, summary: EspnGameSummary) {
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
            // A finished game's probability is 0 or 100 by definition, so the
            // bar is dropped under FINAL STATS exactly as on TV.
            summary.homeWinProbability?.let { chance ->
                WinProbabilityBarMobile(game = game, homePercent = chance)
            }
        }

        summary.teamStats.forEach { (label, awayValue, homeValue) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised.copy(alpha = 0.45f), KBShapeChip)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = awayValue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextHi,
                    maxLines = 1,
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
                    text = homeValue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextHi,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
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

@Composable
private fun WinProbabilityBarMobile(game: SportsGame, homePercent: Int) {
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
            // sliver of the other side.
            Box(
                modifier = Modifier
                    .weight((100 - home).coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .background(teamBarColorMobile(game.away.colorHex, KBTextLo))
            )
            Box(
                modifier = Modifier
                    .weight(home.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .background(teamBarColorMobile(game.home.colorHex, KBAccent))
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

@Composable
private fun DetailRowMobile(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KBTextLo,
            modifier = Modifier.width(88.dp)
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
private fun TournamentDetailSheetMobile(
    event: TournamentEvent,
    channels: List<IptvChannel>,
    lineupMissing: Boolean,
    matchingDone: Boolean,
    onManualPick: (IptvChannel) -> Unit,
    onPlay: (List<IptvChannel>) -> Unit,
    onClose: () -> Unit
) {
    val channel = channels.firstOrNull()
    val backups = channels.drop(1)

    Dialog(onDismissRequest = onClose) {
        KBDialogPanel(
            title = "TOURNAMENT",
            subtitle = event.name.ifBlank { event.league },
            modifier = Modifier.fillMaxHeight(0.92f)
        ) {
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
                    text = (SportsLeagues.byPath(event.league)?.label ?: event.league)
                        .uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KBTextLo
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SportsDetailRules.detailRows(event).forEach { row: GameDetailRow ->
                    DetailRowMobile(label = row.label, value = row.value)
                }

                if (event.leaders.isNotEmpty()) {
                    Text(
                        text = "LEADERS",
                        style = MaterialTheme.typography.labelSmall,
                        color = KBTextLo,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    LeaderboardMobile(leaders = event.leaders)
                }

                Text(
                    text = "CHANNEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = KBTextLo,
                    modifier = Modifier.padding(top = 6.dp)
                )
                ChannelLineMobile(
                    channel = channel,
                    lineupMissing = lineupMissing,
                    matchingDone = matchingDone
                )

                if (backups.isNotEmpty()) {
                    Text(
                        text = "BACKUP CHANNELS",
                        style = MaterialTheme.typography.labelSmall,
                        color = KBTextLo,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    backups.forEach { backup ->
                        KBButton(
                            label = backup.displayName.ifBlank { backup.name }.uppercase(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onManualPick(backup)
                                onPlay(listOf(backup) + channels.filter { it.id != backup.id })
                            }
                        )
                    }
                }
            }

            if (channel != null) {
                KBButton(
                    label = SportsDetailRules.watchLabel(true),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPlay(channels) }
                )
            } else {
                KBButton(
                    label = SportsDetailRules.watchLabel(
                        hasChannel = false,
                        lineupMissing = lineupMissing,
                        matchingDone = matchingDone
                    ),
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {}
                )
            }
        }
    }
}

// ── Follow teams ───────────────────────────────────────────────────────

@Composable
private fun FavoriteTeamsDialogMobile(
    game: SportsGame,
    favoriteKeys: Set<String>,
    onToggle: (SportsTeam, Boolean) -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        KBDialogPanel(title = "Follow teams") {
            Text(
                text = game.name,
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            listOf(game.away, game.home).forEach { team ->
                val followed = team.favoriteKey in favoriteKeys
                KBCard(
                    onClick = { onToggle(team, !followed) },
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TeamMarkMobile(team = team)
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
                                .background(
                                    if (followed) KBAccent else KBSurfaceRaised,
                                    KBShapePill
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
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

// ── Leagues panel ──────────────────────────────────────────────────────

@Composable
private fun LeaguesPanelMobile(
    enabled: Set<String>,
    order: List<String>,
    reminders: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onToggleReminders: (Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onClearMemory: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        KBDialogPanel(
            title = "Leagues",
            subtitle = "Only enabled leagues are fetched and shown. Use ▲ ▼ to set " +
                "the tab order.",
            modifier = Modifier.fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val ordered = SportsLeagues.ordered(order)
                ordered.forEachIndexed { index, league ->
                    LeagueToggleRowMobile(
                        league = league,
                        checked = league.path in enabled,
                        onToggle = { on -> onToggle(league.path, on) },
                        canMoveUp = index > 0,
                        canMoveDown = index < ordered.lastIndex,
                        onMoveUp = { onMove(league.path, -1) },
                        onMoveDown = { onMove(league.path, 1) }
                    )
                }

                Text(
                    text = "GAME REMINDERS",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextLo,
                    modifier = Modifier.padding(top = 8.dp)
                )
                KBCard(
                    onClick = { onToggleReminders(!reminders) },
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
                            text = "Notify me when followed teams play",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KBTextHi,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (reminders) "ON" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (reminders) KBVoid else KBTextLo,
                            modifier = Modifier
                                .background(
                                    if (reminders) KBAccent else KBSurfaceRaised,
                                    KBShapePill
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = "CHANNEL MEMORY",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextLo,
                    modifier = Modifier.padding(top = 8.dp)
                )
                ClearMemoryRowMobile(onClear = onClearMemory)
            }

            KBButton(label = "DONE", onClick = onClose)
        }
    }
}

@Composable
private fun LeagueToggleRowMobile(
    league: SportsLeague,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KBCard(onClick = { onToggle(!checked) }, modifier = Modifier.weight(1f)) {
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
                    color = KBTextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (checked) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (checked) KBVoid else KBTextLo,
                    modifier = Modifier
                        .background(
                            if (checked) KBAccent else KBSurfaceRaised,
                            KBShapePill
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        KBButton(label = "▲", enabled = canMoveUp, onClick = onMoveUp)
        KBButton(label = "▼", enabled = canMoveDown, onClick = onMoveDown)
    }
}

@Composable
private fun ClearMemoryRowMobile(onClear: () -> Unit) {
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

// ── Shared bits ────────────────────────────────────────────────────────

/**
 * The sharing of one LIVE beat, paused while a sheet covers the slate and held
 * still for a reduced-motion viewer - the TV page's two rules, kept because a
 * phone has even less reason to run a clock nobody can see.
 */
@Composable
private fun rememberLivePulseMobile(enabled: Boolean): State<Float> {
    val held = remember { mutableStateOf(1f) }
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion || !enabled) return held
    val transition = rememberInfiniteTransition(label = "sportsLiveMobile")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "sportsLivePulseMobile"
    )
}

@Composable
private fun LiveDotMobile(pulse: State<Float>, modifier: Modifier = Modifier) {
    val alpha = pulse.value
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(KBDanger.copy(alpha = alpha))
    )
}

/** ESPN's club colour as a plate tint, or null when it is unusable/dark. */
private fun clubTintMobile(colorHex: String?): androidx.compose.ui.graphics.Color? {
    val clean = colorHex?.trim()?.removePrefix("#") ?: return null
    if (clean.length != 6) return null
    val value = clean.toLongOrNull(16) ?: return null
    val red = ((value shr 16) and 0xFF) / 255f
    val green = ((value shr 8) and 0xFF) / 255f
    val blue = (value and 0xFF) / 255f
    if (red + green + blue < 0.35f) return null
    return androidx.compose.ui.graphics.Color(red, green, blue).copy(alpha = 0.28f)
}

private fun initialsMobile(team: SportsTeam): String {
    val source = team.abbreviation.ifBlank { team.displayName }
    return source.split(' ', '-')
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { source.take(2).uppercase() }
}

/** Live scores read bright, finals dim, and an unstarted game shows nothing. */
private fun scoreColor(
    game: SportsGame,
    team: SportsTeam
): androidx.compose.ui.graphics.Color {
    if (team.score.isNullOrBlank()) return KBTextLo.copy(alpha = 0.45f)
    return when (game.state) {
        GameState.LIVE -> KBTextHi
        GameState.FINAL -> KBTextHi.copy(alpha = 0.72f)
        GameState.UPCOMING -> KBTextHi
    }
}

/** A solid team colour for the probability bar, or [fallback] when unusable. */
private fun teamBarColorMobile(
    colorHex: String?,
    fallback: androidx.compose.ui.graphics.Color
): androidx.compose.ui.graphics.Color {
    val clean = colorHex?.trim()?.removePrefix("#") ?: return fallback
    if (clean.length != 6) return fallback
    val value = clean.toLongOrNull(16) ?: return fallback
    val red = ((value shr 16) and 0xFF) / 255f
    val green = ((value shr 8) and 0xFF) / 255f
    val blue = (value and 0xFF) / 255f
    if (red + green + blue < 0.35f) return fallback
    return androidx.compose.ui.graphics.Color(red, green, blue)
}
