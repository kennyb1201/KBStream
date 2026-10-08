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
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.OswaldFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIVE_REFRESH_MS = 30_000L

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
 * Playback is not this screen's business. A card hands its matched channel to
 * [onPlayChannel], which is the same launch a guide click takes.
 */
@Composable
fun SportsHubScreen(
    viewModel: SportsHubViewModel = viewModel(),
    modifier: Modifier = Modifier,
    onPlayChannel: (IptvChannel) -> Unit = {},
) {
    val enabled by viewModel.enabledLeagues.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val selectedPath by viewModel.selectedLeaguePath.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteTeamKeys.collectAsStateWithLifecycle()
    val favoritesSection by viewModel.favoritesSection.collectAsStateWithLifecycle()

    val leagues = remember(enabled) { SportsLeagues.enabled(enabled) }
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

    // The live tick. It lives in the composition, so it stops the moment the
    // hub leaves the screen - which is the whole "stop on close" requirement.
    LaunchedEffect(Unit) {
        while (true) {
            delay(LIVE_REFRESH_MS)
            viewModel.refreshLive()
        }
    }

    var showLeagues by remember { mutableStateOf(false) }

    // The game whose teams are being followed. A long press on a card raises
    // this: the card itself stays one focus stop, and the two teams - the
    // things a viewer actually follows - are chosen inside the dialog.
    var favoriteEditor by remember { mutableStateOf<SportsGame?>(null) }

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
                        else -> return@onKeyEvent false
                    }
                    // Consumed either way: a move already happened on the first
                    // branch, and the scroll is this press's answer on the
                    // second - letting the platform handle it too would move
                    // focus AND scroll.
                    focusManager.moveFocus(direction) || scrollByPage(direction)
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

            Spacer(modifier = Modifier.height(16.dp))

            val favoritesTab = selectedPath == SportsLeagues.FAVORITES.path
            val section = if (favoritesTab) {
                favoritesSection
            } else {
                sections.firstOrNull { it.league.path == selectedPath }
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
                    title = "No favourite teams yet",
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
                        onPlayChannel = onPlayChannel,
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
                    onPlayChannel = onPlayChannel,
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
                onToggle = { path, on -> viewModel.setLeagueEnabled(path, on) },
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
    matches: Map<String, IptvChannel>,
    favoriteKeys: Set<String>,
    listState: LazyListState,
    onPlayChannel: (IptvChannel) -> Unit,
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
                onPlayChannel = onPlayChannel,
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
                    channel = matches[event.id],
                    livePulse = livePulse,
                    onPlayChannel = onPlayChannel,
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
                onPlayChannel = onPlayChannel,
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
                onPlayChannel = onPlayChannel,
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
    matches: Map<String, IptvChannel>,
    livePulse: Float,
    favoriteKeys: Set<String>,
    onPlayChannel: (IptvChannel) -> Unit,
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
                        channel = matches[game.id],
                        livePulse = livePulse,
                        favoriteKeys = favoriteKeys,
                        onPlayChannel = onPlayChannel,
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

// ── Cards ───────────────────────────────────────────────────────────────

@Composable
private fun GameCard(
    game: SportsGame,
    channel: IptvChannel?,
    livePulse: Float,
    favoriteKeys: Set<String>,
    onPlayChannel: (IptvChannel) -> Unit,
    onEditFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playable = channel != null
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

            ChannelLine(channel = channel)
        }
    }

    if (playable) {
        KBCard(
            onClick = { onPlayChannel(channel) },
            // A long press follows a team instead of playing the game: two
            // different intents, one press each. A card the playlist cannot
            // carry has no press at all to offer, so it stays out of the focus
            // walk - see the D-pad fallback in the screen above, which is what
            // keeps a run of them from stranding the viewer.
            onLongClick = onEditFavorites,
            // The guide's own row step, not a poster tile's: a game card is a
            // row of information that happens to be two columns wide.
            focusedScale = KBFocusRow,
            shape = KBShapeCard,
            modifier = modifier.fillMaxWidth()
        ) {
            body()
        }
    } else {
        // Not focusable on purpose: a card that cannot be played must not cost
        // the D-pad a stop, or a run of them makes the grid feel broken.
        Surface(
            modifier = modifier.fillMaxWidth(),
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
                text = upcomingLabel(game),
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

/** "Tue, 7:30 PM" in the device's own zone, plus ESPN's own detail if it adds anything. */
private fun upcomingLabel(game: SportsGame): String {
    val when_ = if (game.dateMs > 0L) {
        DateFormats.time(game.dateMs, DateFormats.WEEKDAY_CLOCK_12H)
    } else {
        ""
    }
    val detail = game.statusDetail.trim()
    // ESPN's "7:30 PM ET" duplicates the clock we just formatted; a detail that
    // is only a time adds nothing, so it is only shown when it says something
    // else (a delayed start, a suspension).
    return when {
        when_.isBlank() -> detail.ifBlank { "Today" }
        detail.isBlank() || detail.contains("PM", ignoreCase = true) ||
            detail.contains("AM", ignoreCase = true) -> when_
        else -> "$when_ • $detail"
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
private fun ChannelLine(channel: IptvChannel?) {
    if (channel == null) {
        Text(
            text = "Not in your playlist",
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
    channel: IptvChannel?,
    livePulse: Float,
    onPlayChannel: (IptvChannel) -> Unit,
) {
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
            ChannelLine(channel = channel)
        }
    }

    if (playable) {
        KBCard(
            onClick = { onPlayChannel(channel) },
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
    onToggle: (String, Boolean) -> Unit,
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
                text = "Only enabled leagues are fetched and shown.",
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
                SportsLeagues.ALL.forEachIndexed { index, league ->
                    LeagueToggleRow(
                        league = league,
                        checked = league.path in enabled,
                        onToggle = { on -> onToggle(league.path, on) },
                        focusRequester = firstRow.takeIf { index == 0 }
                    )
                }
            }

            KBButton(label = "DONE", onClick = onClose)
        }
    }
}

@Composable
private fun LeagueToggleRow(
    league: SportsLeague,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    KBCard(
        onClick = { onToggle(!checked) },
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
}
