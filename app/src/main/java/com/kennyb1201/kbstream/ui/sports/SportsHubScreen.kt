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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset
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

private const val LIVE_REFRESH_MS = 30_000L

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

    val leagues = remember(enabled) { SportsLeagues.enabled(enabled) }

    // Keep a tab selected as the catalog changes: the first enabled league is
    // the sensible default, and a league that was just switched off must not
    // stay the selection (its tab is gone).
    LaunchedEffect(leagues) {
        val current = selectedPath
        if (current == null || leagues.none { it.path == current }) {
            leagues.firstOrNull()?.let { viewModel.selectLeague(it.path) }
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp)
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
                leagues = leagues,
                selectedPath = selectedPath,
                onSelect = viewModel::selectLeague,
            )

            Spacer(modifier = Modifier.height(16.dp))

            val section = sections.firstOrNull { it.league.path == selectedPath }
            when {
                section == null -> KBStatusMessage(
                    message = "Loading scores…",
                    loading = true,
                    modifier = Modifier.fillMaxSize()
                )

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
                    onPlayChannel = onPlayChannel,
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
                text = "Live scores • tap a game to watch it on your playlist",
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
    onPlayChannel: (IptvChannel) -> Unit,
) {
    val live = section.games.filter { it.state == GameState.LIVE }
    val upcoming = section.games
        .filter { it.state == GameState.UPCOMING }
        .sortedBy { it.dateMs }
    val final = section.games
        .filter { it.state == GameState.FINAL }
        .sortedByDescending { it.dateMs }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (live.isNotEmpty()) {
            item(key = "header-live") {
                SportsSectionHeader(title = "LIVE NOW", accent = true, count = live.size)
            }
            items(live, key = { it.id }) { game ->
                GameCard(game = game, channel = matches[game.id], onPlayChannel = onPlayChannel)
            }
        }

        // Tournaments (golf, F1) are their own events, not head-to-head games,
        // and they sort with whatever is on now.
        if (section.tournaments.isNotEmpty()) {
            item(key = "header-tournaments") {
                SportsSectionHeader(title = "TOURNAMENTS", accent = section.tournaments.any { it.state == GameState.LIVE })
            }
            items(section.tournaments, key = { it.id }) { event ->
                TournamentCard(
                    event = event,
                    channel = matches[event.id],
                    onPlayChannel = onPlayChannel,
                )
            }
        }

        if (upcoming.isNotEmpty()) {
            item(key = "header-upcoming") {
                SportsSectionHeader(title = "UPCOMING")
            }
            items(upcoming, key = { it.id }) { game ->
                GameCard(game = game, channel = matches[game.id], onPlayChannel = onPlayChannel)
            }
        }

        if (final.isNotEmpty()) {
            item(key = "header-final") {
                SportsSectionHeader(title = "FINAL")
            }
            items(final, key = { it.id }) { game ->
                GameCard(game = game, channel = matches[game.id], onPlayChannel = onPlayChannel)
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

// ── Cards ───────────────────────────────────────────────────────────────

@Composable
private fun GameCard(
    game: SportsGame,
    channel: IptvChannel?,
    onPlayChannel: (IptvChannel) -> Unit,
) {
    val playable = channel != null
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(
                    team = game.away,
                    scoreColor = scoreColor(game, game.away),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "@",
                    style = MaterialTheme.typography.titleSmall,
                    color = KBTextLo,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                TeamColumn(
                    team = game.home,
                    scoreColor = scoreColor(game, game.home),
                    alignEnd = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameStatusLine(game = game)
                BroadcastChip(name = game.broadcastNames.firstOrNull())
            }

            Spacer(modifier = Modifier.height(8.dp))

            ChannelLine(channel = channel)
        }
    }

    if (playable) {
        KBCard(
            onClick = { onPlayChannel(channel) },
            focusedScale = 1.02f,
            shape = KBShapeCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            body()
        }
    } else {
        // Not focusable on purpose: a card that cannot be played must not cost
        // the D-pad a stop, or a run of them makes the grid feel broken.
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
private fun TeamColumn(
    team: SportsTeam,
    scoreColor: Color,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!alignEnd) TeamMark(team = team)
        Column(
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
            modifier = if (alignEnd) Modifier.padding(end = 12.dp) else Modifier.padding(start = 12.dp)
        ) {
            Text(
                text = team.abbreviation.ifBlank { team.displayName },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = team.score ?: team.record.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = OswaldFamily,
                fontWeight = FontWeight.SemiBold,
                color = scoreColor,
                maxLines = 1
            )
        }
        if (alignEnd) TeamMark(team = team)
    }
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
            .size(44.dp)
            .clip(KBShapeChip)
            .background(KBSurfaceRaised)
            .border(1.dp, KBTextLo.copy(alpha = 0.18f), KBShapeChip),
        contentAlignment = Alignment.Center
    ) {
        if (!team.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = team.logoUrl,
                contentDescription = team.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(34.dp)
                    .padding(2.dp)
            )
        } else {
            Text(
                text = initials(team),
                color = KBTextHi,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
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
private fun GameStatusLine(game: SportsGame) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (game.state) {
            GameState.LIVE -> {
                LiveDot()
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
 * The pulsing red live badge.
 *
 * The pulse is what separates "on now" from "on later" at a glance on a TV
 * across the room. It honours reduced motion (the app's own preference) by
 * holding the dot at full strength instead of animating it.
 */
@Composable
private fun LiveDot(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "sportsLive")
    val animated by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "sportsLivePulse"
    )
    val alpha = if (reducedMotion) 1f else animated
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
    onPlayChannel: (IptvChannel) -> Unit,
) {
    val playable = channel != null

    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
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
                    if (event.state == GameState.LIVE) LiveDot()
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
                BroadcastChip(name = event.broadcastNames.firstOrNull())
            }

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
            focusedScale = 1.02f,
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}. ${leader.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == 0) KBTextHi else KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .clip(KBShapeCard)
                .background(KBSurface)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapeCard)
                .padding(22.dp)
                .focusGroup()
        ) {
            Text(
                text = "LEAGUES",
                style = MaterialTheme.typography.titleMedium,
                color = KBAccent,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Only enabled leagues are fetched and shown.",
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            Column(
                modifier = Modifier
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SportsLeagues.ALL.forEach { league ->
                    LeagueToggleRow(
                        league = league,
                        checked = league.path in enabled,
                        onToggle = { on -> onToggle(league.path, on) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            KBButton(label = "DONE", onClick = onClose)
        }
    }
}

@Composable
private fun LeagueToggleRow(
    league: SportsLeague,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    KBCard(
        onClick = { onToggle(!checked) },
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
